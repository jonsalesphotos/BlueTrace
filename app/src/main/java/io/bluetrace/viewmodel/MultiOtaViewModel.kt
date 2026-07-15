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
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.s7.DeviceOtaItem
import io.bluetrace.shared.s7.DeviceOtaStatus
import io.bluetrace.shared.s7.MultiOtaController
import io.bluetrace.shared.s7.OtaFile
import io.bluetrace.shared.s7.OtaPackage
import io.bluetrace.shared.s7.S7FileTrans
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.formatFullStamp
import io.bluetrace.shared.util.formatMb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MultiOtaUiState(
    /** 全队列共用的单个 OTA 包（多设备锁 1 包）。 */
    val pkg: OtaPkgItem? = null,
    val queue: List<DeviceOtaItem> = emptyList(),
    val running: Boolean = false,
) {
    val doneCount: Int get() = queue.count { it.status == DeviceOtaStatus.DONE }
    val failCount: Int get() = queue.count { it.status == DeviceOtaStatus.FAILED }
    val skipCount: Int get() = queue.count { it.status == DeviceOtaStatus.SKIPPED_LOW_BATTERY }
    val queuedCount: Int get() = queue.count { it.status == DeviceOtaStatus.QUEUED }
    val canStart: Boolean get() = pkg != null && queuedCount > 0 && !running
    val hasRetriable: Boolean get() = queue.any { it.retriable } && !running
}

/** 扫描添加表内一行（仅入队、不连接）。 */
data class ScanRow(
    val device: ScannedDevice,
    val supported: Boolean, // 有固件升级面才可加(识别归 Catalog, 去 S7 硬编码)
    val inQueue: Boolean,
    val selected: Boolean,
)

data class ScanSheetState(
    val open: Boolean = false,
    val rows: List<ScanRow> = emptyList(),
)

/**
 * 多设备 OTA VM（顶栏「多设备」开关打开后使用；开关默认关=单设备现状 [OtaTestViewModel]）。
 *
 * **薄壳**：串行编排核在 shared [MultiOtaController]（无 Android 依赖、jvmTest 覆盖、iOS 可复用）。
 * 本层只做平台壳：包加载（Uri→zip 解析→[OtaPackage]）、扫描添加 UI、把 controller 的
 * [MultiOtaController.queue]/[MultiOtaController.running]/[MultiOtaController.opLog] 映射成 Compose 状态，
 * 并把执行日志**逐行落盘**到 `Download/BlueTrace/log/ota/`（每次批量一个文件）。
 * 手动停止的重启指令善后在编排核内、跑 [appScope]（退屏也发得出）。
 */
@OptIn(FlowPreview::class) // ble.scan().sample(1000) 节流（同连接页；FlowPreview）
class MultiOtaViewModel(
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

    private val controller = MultiOtaController(
        ble, registry, clock, zone, viewModelScope,
        lowBatteryPct = configStore.current.ota.lowBatteryPct,
        reconnectScanMs = configStore.current.ota.reconnectScanMs,
        abortScope = appScope,
    )

    val logLines = mutableStateListOf<String>()

    private var loadedPkg: OtaPackage? = null
    private val _pkg = MutableStateFlow<OtaPkgItem?>(null)
    private var runLog: OtaRunLog? = null // 本次批量的落盘日志（log/ota/）

    val state: StateFlow<MultiOtaUiState> =
        combine(controller.queue, controller.running, _pkg) { queue, running, pkg ->
            MultiOtaUiState(pkg = pkg, queue = queue, running = running)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MultiOtaUiState())

    // ---- 扫描添加表（只入队、不连接）----
    private val _scanOpen = MutableStateFlow(false)
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _scanSelected = MutableStateFlow<Set<String>>(emptySet())
    private var scanJob: Job? = null

    val scan: StateFlow<ScanSheetState> =
        combine(_scanOpen, _scanResults, _scanSelected, controller.queue) { open, results, selected, queue ->
            val queueIds = queue.mapTo(HashSet()) { it.device.id }
            val rows = results.asSequence()
                // 无名/参考不显示（同连接页口径）
                .filter { it.name.isNotBlank() && it.name != "(unnamed)" && it.kind != DeviceKind.REFERENCE }
                .map { d ->
                    ScanRow(
                        device = d,
                        // 支持 OTA = 识别到的档案有固件升级面(S7 有; 参考带/纯数据设备无).
                        supported = catalog.identify(d)?.firmwareUpdate != null,
                        inQueue = d.id in queueIds,
                        selected = d.id in selected,
                    )
                }
                // 支持的在上（手边的表即列表最上）；RSSI 5dBm 分桶 + 名称 tiebreak——
                // 1s 采样的 RSSI 抖动不再让行序每秒乱跳（滚动锚点按位置记，见 ScanAddSheet）
                .sortedWith(
                    compareByDescending<ScanRow> { it.supported }
                        .thenByDescending { it.device.rssi / 5 }
                        .thenBy { it.device.name },
                )
                .toList()
            ScanSheetState(open, rows)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanSheetState())

    init {
        log("多设备 OTA 已就绪")
        // 镜像编排核操作日志到终端面板（加本地时间戳）
        viewModelScope.launch { controller.opLog.collect { log(it) } }
    }

    // ---- 烧录包（锁 1 个，全队列共用）----

    fun setPackage(uri: Uri) {
        if (controller.running.value) return
        viewModelScope.launch {
            val r = zipLoader.load(uri)
            val v = r.validation
            when {
                r.error != null -> log("✗ 添加失败：${r.sourceName} — ${r.error}")
                v == null || !v.valid -> log("✗ 校验不通过：${r.sourceName} — ${v?.errors?.joinToString("；") ?: "未知"}")
                r.pkg == null -> log("✗ 解析失败：${r.sourceName}")
                else -> {
                    loadedPkg = r.pkg
                    _pkg.value = OtaPkgItem(r.sourceName, v.fileCount, v.totalSize, v.hasFonts)
                    log("已选包：${r.sourceName}（${v.fileCount} 文件 / ${formatMb(v.totalSize)}${if (v.hasFonts) "" else " · 无字库"}）")
                    v.warnings.forEach { log("⚠ $it") }
                }
            }
        }
    }

    fun clearPackage() {
        if (controller.running.value) return
        loadedPkg = null
        _pkg.value = null
    }

    /** DEBUG：载入内存合成示例包（长按「添加烧录包」触发），供 Mock 多设备演示、绕过 SAF/真实 zip。 */
    fun loadDemoPackage() {
        if (controller.running.value) return
        // 安全门：仅 Mock 后端可用——合成包是垃圾字节，绝不能推给真实手表
        if (ble !is io.bluetrace.shared.ble.mock.MockBleClient) {
            log("示例包仅 Mock 模式可用（当前真实 GATT，已忽略）")
            return
        }
        val pkg = OtaPackage(
            files = listOf(
                OtaFile("ResData.dat", ByteArray(4096) { (it * 7).toByte() }, S7FileTrans.FT_FW),
                OtaFile("fw.dat", ByteArray(3000) { (it * 31 + 5).toByte() }, S7FileTrans.FT_FW),
            ),
        )
        loadedPkg = pkg
        _pkg.value = OtaPkgItem("demo_mock.zip（示例）", pkg.fileCount, pkg.totalBytes, hasFonts = false)
        log("已载入示例包（Mock 演示 · ${pkg.fileCount} 文件 / ${formatMb(pkg.totalBytes)}）")
    }

    // ---- 扫描添加 ----

    fun openScanSheet() {
        if (_scanOpen.value) return
        _scanOpen.value = true
        _scanResults.value = emptyList()
        _scanSelected.value = emptySet()
        scanJob = viewModelScope.launch {
            // sample(1s)：扫描回调很密，节流到 1 次/秒，避免列表按 RSSI 每帧重排跳动难勾选（同连接页）
            // 扫描去识别化: 识别在此投影层经 Catalog 统一打标(参考带过滤/supported 判定据此不变).
            ble.scan().sample(1000).collect { devices -> _scanResults.value = devices.map { catalog.annotate(it) } }
        }
    }

    fun closeScanSheet() {
        scanJob?.cancel(); scanJob = null
        _scanOpen.value = false
    }

    fun toggleScanSelect(id: String) {
        val d = _scanResults.value.firstOrNull { it.id == id } ?: return
        if (catalog.identify(d)?.firmwareUpdate == null) return // 无固件升级面不可选
        if (controller.queue.value.any { it.device.id == id }) return // 已在队列不可选
        _scanSelected.update { if (id in it) it - id else it + id }
    }

    fun confirmScanAdd() {
        val sel = _scanSelected.value
        val toAdd = _scanResults.value.filter { it.id in sel && catalog.identify(it)?.firmwareUpdate != null }
        if (toAdd.isNotEmpty()) {
            controller.addDevices(toAdd)
            log("加入队列 ${toAdd.size} 台")
        }
        closeScanSheet()
    }

    // ---- 队列增删 / 批量执行（委托编排核）----

    fun removeFromQueue(id: String) = controller.removeDevice(id)

    fun startBatch() {
        val pkg = loadedPkg ?: return
        // 前置守卫与编排核同条件：落盘文件须在 startBatch **之前**打开——viewModelScope 是
        // Main.immediate，startBatch 内协程会立即跑到首个挂起点，头几行日志经 collector
        // 同步镜像进 log()，晚开文件就丢头（真机实证）。
        if (controller.running.value || controller.queue.value.none { it.status == DeviceOtaStatus.QUEUED }) return
        closeRunLog()
        runLog = otaLogStore.begin("multi", nowCompact())
        log("执行日志 → ${runLog?.displayPath}")
        log("回连扫描预算 ${configStore.current.ota.reconnectScanMs / 1000}s · 电量门槛 ${configStore.current.ota.lowBatteryPct}%")
        // 不在批量结束时抢关文件（结尾汇总/停止善后行经 SharedFlow 迟到）——下次启动或退屏时关。
        controller.startBatch(pkg)
    }

    fun stopBatch() = controller.stopBatch()

    fun retry(id: String) {
        loadedPkg?.let { controller.retry(id, it) }
    }

    fun retryAllFailed() {
        loadedPkg?.let { controller.retryAllFailed(it) }
    }

    /** 队列汇总行(改调 shared 编排核, 不在 app 重复串): 完成 X · 失败 Y · 跳过 Z · 待升级 W. */
    fun summaryLine(): String = controller.summaryLine()

    // ---- 日志 ----

    fun clearLog() = logLines.clear()

    /** 屏幕终端行 `[HHMMSS]`；落盘行完整本机时间开头。TRANS 逐切片行只落盘（终端不刷屏，行内有进度条）。 */
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
        controller.close()
        scanJob?.cancel()
        closeRunLog()
    }
}
