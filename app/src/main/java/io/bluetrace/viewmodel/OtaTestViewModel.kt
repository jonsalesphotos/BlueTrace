package io.bluetrace.viewmodel

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.data.android.OtaZipLoader
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.s7.OtaPackage
import io.bluetrace.shared.s7.OtaPhase
import io.bluetrace.shared.s7.OtaProgress
import io.bluetrace.shared.s7.OtaProvisioner
import io.bluetrace.shared.s7.OtaResult
import io.bluetrace.shared.s7.S7Console
import io.bluetrace.shared.s7.S7OtaSession
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 已添加的烧录包（摘要；字节留 VM 私有）。 */
data class OtaPkgItem(val sourceName: String, val fileCount: Int, val totalSize: Long, val hasFonts: Boolean)

/** 一轮 OTA 结果。 */
data class OtaIterationResult(val iteration: Int, val pkgLabel: String, val result: OtaResult)

data class OtaTestUiState(
    /** 目标设备（黏性：断联也保留，可重连）。 */
    val device: ScannedDevice? = null,
    val link: LinkState = LinkState.DISCONNECTED,
    /** 连接后自动读到的当前版本。 */
    val version: String? = null,
    val versionReading: Boolean = false,
    /** 已添加的包（0/1/2）；1=单次，2=A→B 循环。 */
    val packages: List<OtaPkgItem> = emptyList(),
    val running: Boolean = false,
    val currentIteration: Int = 0,
    val currentPkgIdx: Int = 0,
    val phase: OtaPhase? = null,
    val progress: OtaProgress? = null,
    val results: List<OtaIterationResult> = emptyList(),
) {
    val connected: Boolean get() = device != null && link == LinkState.CONNECTED
    /** 2 包 → A→B 循环升级（重复到手动中断）。 */
    val loopMode: Boolean get() = packages.size == 2
    val canStart: Boolean get() = packages.isNotEmpty() && connected && !running
    val canAdd: Boolean get() = packages.size < 2 && !running
    val canReconnect: Boolean get() = device != null && !connected && link != LinkState.CONNECTING && !running
}

/**
 * DEBUG「OTA 固件」VM：连接 S7（复用 registry/连接页）→ 「+」添加 1~2 个烧录包 → 刷入。
 * 1 包=单次 OTA；2 包=A→B 交替**循环升级**（重复到手动中断）。连上自动读版本；断联可重连（仿 DUT）。
 * 选包/校验/连接/版本等信息统一进 [logLines]（执行日志）。逻辑在 shared [S7OtaSession]/[OtaProvisioner]。
 */
class OtaTestViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val zipLoader: OtaZipLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(OtaTestUiState())
    val state: StateFlow<OtaTestUiState> = _state

    /** 执行日志（终端面板；上限 300 行）。 */
    val logLines = mutableStateListOf<String>()

    private val loadedPkgs = mutableListOf<OtaPackage>() // 与 state.packages 同序，持字节
    private var runJob: Job? = null
    private var linkJob: Job? = null

    init {
        log("OTA 固件工具已启动")
        viewModelScope.launch {
            registry.connected.collect { list ->
                if (_state.value.running) return@collect
                val target = list.firstOrNull { it.kind != DeviceKind.REFERENCE }
                // 黏性：出现新设备才切换；断联(target=null)保留旧设备，链路由 linkJob 更新为 DISCONNECTED
                if (target != null && target.id != _state.value.device?.id) trackDevice(target)
            }
        }
    }

    private fun trackDevice(target: ScannedDevice) {
        linkJob?.cancel()
        _state.update { it.copy(device = target, link = ble.linkState(target.id).value, version = null) }
        linkJob = viewModelScope.launch {
            var prev: LinkState? = null
            ble.linkState(target.id).collect { l ->
                _state.update { it.copy(link = l) }
                if (l == LinkState.CONNECTED && prev != LinkState.CONNECTED && !_state.value.running) {
                    log("S7 已连接：${target.name}")
                    autoReadVersion(target)
                }
                prev = l
            }
        }
    }

    /** 断联后重连当前设备（仿 DUT 控制台）。 */
    fun reconnect() {
        val device = _state.value.device ?: return
        if (!_state.value.canReconnect) return
        viewModelScope.launch {
            _state.update { it.copy(link = LinkState.CONNECTING) }
            log("重连中：${device.name}")
            ble.connect(device)
            if (ble.linkState(device.id).value == LinkState.CONNECTED) registry.add(device)
            else log("重连失败")
        }
    }

    /** 手动刷新版本（点「当前版本」触发）。 */
    fun refreshVersion() {
        val device = _state.value.device ?: return
        if (!_state.value.connected) return
        doReadVersion(device, auto = false)
    }

    private fun autoReadVersion(device: ScannedDevice) = doReadVersion(device, auto = true)

    private fun doReadVersion(device: ScannedDevice, auto: Boolean) {
        if (_state.value.versionReading || _state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(versionReading = true) }
            val v = try { readDeviceVersion(device) } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
            _state.update { it.copy(version = v, versionReading = false) }
            log("${if (auto) "自动" else "手动"}读取版本：${v ?: "未知"}")
        }
    }

    // ---- 加/删 包 ----

    fun addPackage(uri: Uri) {
        if (!_state.value.canAdd) return
        viewModelScope.launch {
            val r = zipLoader.load(uri)
            val v = r.validation
            when {
                r.error != null -> log("✗ 添加失败：${r.sourceName} — ${r.error}")
                v == null || !v.valid -> log("✗ 校验不通过：${r.sourceName} — ${v?.errors?.joinToString("；") ?: "未知"}")
                r.pkg == null -> log("✗ 解析失败：${r.sourceName}")
                else -> {
                    loadedPkgs.add(r.pkg)
                    val item = OtaPkgItem(r.sourceName, v.fileCount, v.totalSize, v.hasFonts)
                    _state.update { it.copy(packages = it.packages + item) }
                    log("已添加包 ${loadedPkgs.size}：${r.sourceName}（${v.fileCount} 文件 / ${fmtMB(v.totalSize)}${if (v.hasFonts) "" else " · 无字库"}）")
                    v.warnings.forEach { w -> log("⚠ $w") }
                }
            }
        }
    }

    fun removePackage(index: Int) {
        if (_state.value.running) return
        if (index !in loadedPkgs.indices) return
        val name = _state.value.packages.getOrNull(index)?.sourceName ?: ""
        loadedPkgs.removeAt(index)
        _state.update { it.copy(packages = it.packages.filterIndexed { i, _ -> i != index }) }
        log("已移除包：$name")
    }

    // ---- 刷入 ----

    fun start() {
        val st = _state.value
        if (!st.canStart) return
        val device = st.device ?: return
        val pkgs = loadedPkgs.toList()
        val loop = pkgs.size == 2
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true, results = emptyList(), currentIteration = 0, phase = OtaPhase.Downloading) }
            log(if (loop) "===== 开始 A→B 循环升级（手动中断停止）=====" else "===== 开始单次 OTA =====")
            try {
                val results = ArrayList<OtaIterationResult>()
                var i = 0
                while (isActive) {
                    i++
                    val idx = if (loop) (i - 1) % 2 else 0
                    val pkg = pkgs[idx]
                    val label = "包${idx + 1}"
                    _state.update { it.copy(currentIteration = i, currentPkgIdx = idx, phase = OtaPhase.Downloading, progress = null) }
                    log("── 第 $i 轮 · $label（${st.packages[idx].sourceName}）──")
                    val result = runOnce(device, pkg)
                    results.add(OtaIterationResult(i, label, result))
                    _state.update { it.copy(results = results.toList()) }
                    when (result) {
                        is OtaResult.Reconnected -> {
                            _state.update { it.copy(version = result.currentVersion) }
                            registry.add(device)
                            log("第 $i 轮完成 ✓ 版本=${result.currentVersion ?: "未知"}")
                        }
                        is OtaResult.Failed -> { log("第 $i 轮失败 ✗ ${result.reason} → 停止"); break }
                        OtaResult.DoneDownload -> Unit
                    }
                    if (!loop) break // 单次
                }
                log("===== 结束 =====")
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun runOnce(device: ScannedDevice, pkg: OtaPackage): OtaResult {
        val session = S7OtaSession(ble, device.id, viewModelScope, clock)
        val prov = OtaProvisioner(session, ble, device, readVersion = { readDeviceVersion(device) })
        val collectors = listOf(
            viewModelScope.launch { session.opLog.collect { log("· $it") } },
            viewModelScope.launch { prov.opLog.collect { log("» $it") } },
        )
        return try {
            prov.provisionAndReconnect(
                pkg,
                onPhase = { p -> _state.update { it.copy(phase = p) } },
                onProgress = { pr -> _state.update { it.copy(progress = pr) } },
            )
        } finally {
            collectors.forEach { it.cancel() }
        }
    }

    /** 短命 S7Console 读设备软件版本。失败抛异常（provisioner 侧容错）。 */
    private suspend fun readDeviceVersion(device: ScannedDevice): String? {
        val c = S7Console(ble, device.id, viewModelScope, clock, zone)
        c.start()
        return try {
            c.getDeviceInfo().swVer
        } finally {
            c.stop()
        }
    }

    fun stop() {
        runJob?.cancel()
        log("已手动中断")
    }

    fun clearLog() = logLines.clear()

    private fun log(text: String) {
        logLines.add("[${nowHms()}] $text")
        if (logLines.size > 300) logLines.removeRange(0, logLines.size - 300)
    }

    private fun nowHms(): String {
        val p = io.bluetrace.shared.util.epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        return p.timeCompact()
    }

    private fun fmtMB(b: Long): String = if (b >= 1_000_000) "%.1f MB".format(b / 1_000_000.0) else "%.0f KB".format(b / 1_000.0)

    override fun onCleared() {
        runJob?.cancel()
    }
}
