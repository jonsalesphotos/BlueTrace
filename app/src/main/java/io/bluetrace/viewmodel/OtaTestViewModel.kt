package io.bluetrace.viewmodel

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.data.android.ConfigStore
import io.bluetrace.data.android.OtaRunLog
import io.bluetrace.data.android.OtaRunLogStore
import io.bluetrace.data.android.OtaZipLoader
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.device.FwUpdateResult
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.s7.OtaPackage
import io.bluetrace.shared.s7.OtaPhase
import io.bluetrace.shared.s7.OtaProgress
import io.bluetrace.shared.s7.S7Console
import io.bluetrace.shared.s7.S7FirmwareUpdateStrategy
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.formatFullStamp
import io.bluetrace.shared.util.formatMb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 已添加的烧录包(摘要; 字节留 VM 私有).  */
data class OtaPkgItem(val sourceName: String, val fileCount: Int, val totalSize: Long, val hasFonts: Boolean)

/** 一轮 OTA 结果(UI 展示壳: 终态一句话 + 本轮下载平均速度).  */
data class OtaIterationResult(
    val iteration: Int,
    val pkgLabel: String,
    val ok: Boolean,
    /** 终态一句话: "成功(版本 X)" / "失败: <结构化原因>".  */
    val detail: String,
    /** 本轮下载平均速度(B/s; 下载阶段未走完=null 不显示).  */
    val avgBps: Long? = null,
)

data class OtaTestUiState(
    /** 目标设备(黏性: 断联也保留, 可重连).  */
    val device: ScannedDevice? = null,
    val link: LinkState = LinkState.DISCONNECTED,
    /** 已添加的包(0/1/2); 1=单次, 2=A→B 循环.  */
    val packages: List<OtaPkgItem> = emptyList(),
    val running: Boolean = false,
    /** 手动停止的善后进行中(取消旧运行/设备善后/断开): 期间禁止重新开始, 防旧善后误伤新一轮.  */
    val stopping: Boolean = false,
    val currentIteration: Int = 0,
    val currentPkgIdx: Int = 0,
    val phase: OtaPhase? = null,
    val progress: OtaProgress? = null,
    /** 本次运行开始前读到的设备版本(进度卡"版本 A → B"左值; 开始 OTA 时读一次).  */
    val versionBefore: String? = null,
    /** 最近一轮成功回连后读到的版本(进度卡右值; 未出=null).  */
    val versionAfter: String? = null,
    val results: List<OtaIterationResult> = emptyList(),
    /** 最近一次失败的结构化描述(轮次 + 出错指令 / 文件传输失败位置), 驱动错误详情卡.  */
    val lastError: String? = null,
) {
    val connected: Boolean get() = device != null && link == LinkState.CONNECTED
    /** 2 包 → A→B 循环升级(重复到手动中断).  */
    val loopMode: Boolean get() = packages.size == 2
    val canStart: Boolean get() = packages.isNotEmpty() && connected && !running && !stopping
    val canAdd: Boolean get() = packages.size < 2 && !running
    val canReconnect: Boolean get() = device != null && !connected && link != LinkState.CONNECTING && !running
    val canDisconnect: Boolean get() = connected && !running
}

/**
 * DEBUG"OTA 固件"VM: 连接 S7(复用 registry/连接页, 可就地断开/重连)→ "+"添加 1~2 个烧录包 → 刷入.
 * 1 包=单次 OTA; 2 包=A→B 交替**循环升级**(重复到手动中断).
 *
 * 版本读取只两处(用户约定 2026-07-14): **开始 OTA 前读一次**(进度卡左值)+ **回连后读一次**
 * (升级链内置, 进度卡右值/各轮结果); 平时不自动读. **运行自然结束后自动断开**(手动停止走 [stop] 断开).
 *
 * 刷写编排走 [S7FirmwareUpdateStrategy](W4 唯一入口: 吸收 OtaProvisioner/S7OtaSession 链 + 中止善后
 * 门控红线在策略内). 选包/校验/连接/版本等信息统一进 [logLines](执行日志), 并**逐行落盘**到
 * `Download/BlueTrace/log/ota/`(每次运行一个文件, 循环模式全量历史不受内存 300 行限制).
 */
class OtaTestViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val catalog: DeviceProfileCatalog,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val zipLoader: OtaZipLoader,
    private val otaLogStore: OtaRunLogStore,
    private val configStore: ConfigStore,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(OtaTestUiState())
    val state: StateFlow<OtaTestUiState> = _state

    /** 执行日志(终端面板; 上限 300 行).  */
    val logLines = mutableStateListOf<String>()

    private val loadedPkgs = mutableListOf<OtaPackage>() // 与 state.packages 同序，持字节
    private var runJob: Job? = null
    private var linkJob: Job? = null
    private var runLog: OtaRunLog? = null // 本次运行的落盘日志（log/ota/）

    // 当前运行的升级策略(start 即建): stop() 转发 abort() 用(传输态门控由策略内部自持).
    private var currentStrategy: S7FirmwareUpdateStrategy? = null

    // 本轮下载平均速度计时(onOtaPhase 回调驱动: 进 Downloading 记起点, 离开时结算).
    private var downloadStartMs = 0L
    private var lastAvgBps: Long? = null

    init {
        log("OTA 固件工具已启动")
        viewModelScope.launch {
            registry.connected.collect { list ->
                if (_state.value.running) return@collect
                // 本屏是 S7 专属工具(S7 zip loader/S7 策略/S7Console): 只跟踪识别为 S7 的设备——
                // 异构协议设备(如 ZX)被灌 S7 REQ 只会超时, 通用 OTA 分派属后续任务.
                val target = list.firstOrNull { it.kind != DeviceKind.REFERENCE && catalog.identify(it)?.profileId == PROFILE_S7 }
                // 黏性: 出现新设备才切换; 断联(target=null)保留旧设备, 链路由 linkJob 更新为 DISCONNECTED
                if (target != null && target.id != _state.value.device?.id) trackDevice(target)
            }
        }
    }

    private fun trackDevice(target: ScannedDevice) {
        linkJob?.cancel()
        _state.update { it.copy(device = target, link = ble.linkState(target.id).value) }
        linkJob = viewModelScope.launch {
            var prev: LinkState? = null
            ble.linkState(target.id).collect { l ->
                _state.update { it.copy(link = l) }
                if (l == LinkState.CONNECTED && prev != LinkState.CONNECTED && !_state.value.running) {
                    log("S7 已连接：${target.name}") // 版本不自动读（开始 OTA 时才读一次）
                }
                prev = l
            }
        }
    }

    /** 断联后重连当前设备(就地按钮, 仿 DUT 控制台).  */
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

    /** 手动断开当前设备(就地按钮, 仿 DUT 控制台; 设备黏性保留可重连).  */
    fun disconnect() {
        val device = _state.value.device ?: return
        if (!_state.value.canDisconnect) return
        viewModelScope.launch {
            ble.disconnect(device.id)
            registry.remove(device.id)
            log("已断开：${device.name}")
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
                    log("已添加包 ${loadedPkgs.size}：${r.sourceName}（${v.fileCount} 文件 / ${formatMb(v.totalSize)}${if (v.hasFonts) "" else " · 无字库"}）")
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
        closeRunLog() // 上一次自然结束后未关的句柄
        val runLog = otaLogStore.begin(if (loop) "loop" else "single", nowCompact()).also { this.runLog = it }
        // 策略先建(run 前 abort 也可达: 读起始版本阶段手动停止 -> 非传输态语义 = 连接态发 CTRL_RESET)
        currentStrategy = buildStrategy(device, totalBytes = 0L)
        runJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    running = true, results = emptyList(), currentIteration = 0,
                    phase = OtaPhase.Downloading, lastError = null,
                    versionBefore = null, versionAfter = null, progress = null,
                )
            }
            log(if (loop) "===== 开始 A→B 循环升级（手动中断停止）=====" else "===== 开始单次 OTA =====")
            log("执行日志 → ${runLog.displayPath}")
            log("目标 ${device.name}（${device.address}）· 回连扫描预算 ${configStore.current.ota.reconnectScanMs / 1000}s")
            // 起始版本: 开始前读一次(约定的两处读点之一)
            val v0 = runCatchingSuspend { readDeviceVersion(device) }
            _state.update { it.copy(versionBefore = v0) }
            log("起始版本：${v0 ?: "未知"}")
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
                    when (result) {
                        is FwUpdateResult.Success -> {
                            results.add(OtaIterationResult(i, label, ok = true, detail = "成功（版本 ${result.versionAfter ?: "未知"}）", avgBps = lastAvgBps))
                            _state.update { it.copy(results = results.toList(), versionAfter = result.versionAfter) }
                            registry.add(device)
                            log("第 $i 轮完成 ✓ 版本=${result.versionAfter ?: "未知"}${lastAvgBps?.let { b -> " · 平均 ${fmtKbps(b)}" } ?: ""}")
                        }
                        is FwUpdateResult.Failed -> {
                            results.add(OtaIterationResult(i, label, ok = false, detail = "失败：${result.summary}", avgBps = lastAvgBps))
                            _state.update { it.copy(results = results.toList(), lastError = "第 $i 轮 · $label：${result.summary}") }
                            log("第 $i 轮失败 ✗ ${result.summary} → 停止")
                            break
                        }
                    }
                    if (!loop) break // 单次
                }
                log("===== 结束 =====")
                // 运行自然结束(单次完/循环失败停)自动断开(约定); 手动停止路径由 stop() 断开
                runCatchingSuspend { ble.disconnect(device.id) }
                registry.remove(device.id)
                log("已自动断开：${device.name}")
            } finally {
                _state.update { it.copy(running = false) }
                if (isActive) closeRunLog() // 手动停止(取消)路径由 stop() 善后完再关，别在这抢先截断
            }
        }
    }

    /** 每轮一个新策略实例(内部进度/传输态是 per-run 状态); 速度计时随轮重置.  */
    private suspend fun runOnce(device: ScannedDevice, pkg: OtaPackage): FwUpdateResult {
        downloadStartMs = 0L
        lastAvgBps = null
        val strategy = buildStrategy(device, pkg.totalBytes)
        currentStrategy = strategy
        return strategy.run(pkg)
    }

    private fun buildStrategy(device: ScannedDevice, totalBytes: Long) = S7FirmwareUpdateStrategy(
        ble, device, viewModelScope, clock, zone,
        abortScope = appScope, // 中止善后须比 VM 长寿（"中止并离开"销毁 VM 后仍要发得出重启指令）
        reconnectScanMs = configStore.current.ota.reconnectScanMs,
        onLog = { log(it) }, // session="· "/prov="» " 前缀在策略内，TRANS 终端过滤口径不变
        onOtaPhase = { p ->
            // 平均速度: 进 Downloading 记起点, 首次离开时结算(回连等阶段不计入)
            if (p == OtaPhase.Downloading && downloadStartMs == 0L) downloadStartMs = clock.nowMs()
            else if (p != OtaPhase.Downloading && downloadStartMs > 0L && lastAvgBps == null && totalBytes > 0) {
                val elapsed = clock.nowMs() - downloadStartMs
                if (elapsed > 0) lastAvgBps = totalBytes * 1000 / elapsed
            }
            _state.update { it.copy(phase = p) }
        },
        onOtaProgress = { pr -> _state.update { it.copy(progress = pr) } },
    )

    /** 短命 S7Console 读设备软件版本(开始 OTA 前的一次性读点). 失败抛异常(调用侧容错).  */
    private suspend fun readDeviceVersion(device: ScannedDevice): String? {
        val c = S7Console(ble, device.id, viewModelScope, clock, zone)
        c.start()
        return try {
            c.getDeviceInfo().swVer
        } finally {
            c.stop()
        }
    }

    /**
     * 手动停止: 取消运行 → 设备善后(转发 [S7FirmwareUpdateStrategy.abort], 传输态门控/永不 STOP
     * 红线在策略内; 善后结果日志经 onLog 回吐终端)→ 断开本地 GATT.
     * 善后跑 [appScope]——"中止并离开"会立刻销毁本 VM, viewModelScope 上发不出指令.
     *
     * **运行代际防护**: 善后只操作**发起停止那一刻的快照**(job/strategy/runLog), 绝不回读可变成员——
     * 旧善后跑在 app scope 上, 快速重新开始时成员已指向新一轮, 误读会 abort 新策略/关新日志.
     * 另以 [OtaTestUiState.stopping] 关死开始入口, 善后完成前禁止新一轮.
     */
    fun stop() {
        val st = _state.value
        if (!st.running || st.stopping) return
        // 快照本轮身份: 善后全程只用这些局部值
        val device = st.device
        val job = runJob
        val strategy = currentStrategy
        val snapLog = runLog
        log("已手动中断") // 落盘要赶在移交前; 之后的善后行只进屏幕终端(runLog 已移交, 不与新一轮抢句柄)
        runLog = null // 移交所有权给善后（start() 的 closeRunLog 不再碰它）
        _state.update { it.copy(stopping = true) }
        appScope.launch {
            try {
                job?.cancelAndJoin()
                strategy?.abort()
                if (device != null) {
                    try {
                        ble.disconnect(device.id)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                    }
                    registry.remove(device.id)
                }
                snapLog?.close()
            } finally {
                _state.update { it.copy(stopping = false) }
            }
        }
    }

    fun clearLog() = logLines.clear()

    /** 容错: 非取消异常转 null(结构化并发下取消照常上抛).  */
    private suspend fun <T> runCatchingSuspend(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        null
    }

    private fun fmtKbps(bps: Long): String = "${bps / 1000} KB/s"

    /** 屏幕终端行 `[HHMMSS]`; 落盘行完整本机时间开头. TRANS 逐切片行只落盘(终端不刷屏, 屏上有进度条).  */
    private fun log(text: String) {
        runLog?.append("${formatFullStamp(clock.nowMs(), zone.offsetSeconds())} $text") // 落盘全量，不受 300 行内存窗限制
        if (text.startsWith("· TRANS ")) return
        logLines.add("[${nowHms()}] $text")
        if (logLines.size > 300) logLines.removeRange(0, logLines.size - 300)
    }

    private fun closeRunLog() {
        runLog?.close()
        runLog = null
    }

    private fun nowHms(): String {
        val p = io.bluetrace.shared.util.epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        return p.timeCompact()
    }

    private fun nowCompact(): String =
        io.bluetrace.shared.util.epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds()).compact()

    override fun onCleared() {
        runJob?.cancel()
        closeRunLog()
    }
}
