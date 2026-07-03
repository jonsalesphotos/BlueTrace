package io.bluetrace.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.data.android.ExportResult
import io.bluetrace.data.android.MediaStoreExporter
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.domain.DeviceLogStore
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.s7.S7
import io.bluetrace.shared.s7.S7Battery
import io.bluetrace.shared.s7.S7CommandException
import io.bluetrace.shared.s7.S7Console
import io.bluetrace.shared.s7.S7DateTime
import io.bluetrace.shared.s7.S7DeviceInfo
import io.bluetrace.shared.s7.S7Failure
import io.bluetrace.shared.s7.S7Heartbeat
import io.bluetrace.shared.s7.S7OpLine
import io.bluetrace.shared.s7.S7Person
import io.bluetrace.shared.s7.S7SnInfo
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 危险命令（关机/重启/恢复出厂，协议不回包）执行态。 */
sealed interface DangerState {
    data class Waiting(val labelKey: Int) : DangerState
    data class Done(val ok: Boolean) : DangerState
}

/** 一次性提示事件（土 toast）——语义在 VM，文案在 UI 拼（VM 不引 R.string）。 */
sealed interface ConsoleToast {
    data object TimeSynced : ConsoleToast
    data object PersonWritten : ConsoleToast
    data class FindToggled(val on: Boolean) : ConsoleToast
    data object Refreshed : ConsoleToast
    /** 指令失败（错误码短串，如 TIMEOUT / NOT_SUPPORT）。 */
    data class Failed(val code: String) : ConsoleToast
    /** 文件已导出到公共 Download 路径。 */
    data class Exported(val displayPath: String) : ConsoleToast
    data class ExportFailed(val reason: String) : ConsoleToast
}

data class ConsoleUiState(
    val device: ScannedDevice? = null,
    /** 可控候选（已连接的非参考设备）：>1 台时需用户选择（进入前多连场景）。 */
    val candidates: List<ScannedDevice> = emptyList(),
    val link: LinkState = LinkState.DISCONNECTED,
    /** 非 null = 有命令在飞（按钮禁用），值为动作标签（诊断用）。 */
    val busy: String? = null,
    val info: S7DeviceInfo? = null,
    val sn: S7SnInfo? = null,
    val devFunc: Long? = null,
    val bondState: Int? = null,
    val battery: S7Battery? = null,
    val heartbeat: S7Heartbeat? = null,
    val deviceTime: S7DateTime? = null,
    val driftSec: Long? = null,
    val person: S7Person? = null,
    val finding: Boolean = false,
    val logRunning: Boolean = false,
    val logChunks: Int = 0,
    val logBytes: Int = 0,
    /** 本次会话已拉到日志（「查看日志」按钮可用）。 */
    val logAvailable: Boolean = false,
    val danger: DangerState? = null,
    /** 错误码/原因短串（TIMEOUT / NOT_SUPPORT …），UI 套 console_err_fmt。 */
    val error: String? = null,
)

/**
 * 设备维护（DUT）控制台 VM —— 逻辑在 shared [S7Console]，这里只做生命周期与状态拼装。
 * 设备来源：[ConnectionRegistry] 中首个 **B2A 协议设备**（广播特征匹配 [B2aDetect]，
 * 不限 S7 一款、不锁名称/MAC；先在设备连接页连接）。
 */
class DeviceConsoleViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val subjects: SubjectRepository,
    private val exporter: MediaStoreExporter,
    private val logStore: DeviceLogStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state

    /** 一次性 toast 事件（UI collect 后弹土 toast）。 */
    private val _toasts = MutableSharedFlow<ConsoleToast>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val toasts: SharedFlow<ConsoleToast> = _toasts

    /** 操作日志（monospace 面板；上限 200 行防膨胀）。 */
    val opLines = mutableStateListOf<S7OpLine>()

    private var console: S7Console? = null
    private var attachJob: Job? = null

    /** 在飞操作作用域：随 attach 重建——设备切换/断开时取消全部在飞 op，防旧协程污染新状态。 */
    private var opsScope: CoroutineScope = newOpsScope()

    private fun newOpsScope() =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    init {
        viewModelScope.launch {
            registry.connected.collect { list ->
                // 候选 = 已连接的非参考设备（参考心率带不属控制台管辖）
                val candidates = list.filter { it.kind != DeviceKind.REFERENCE }
                _state.update { it.copy(candidates = candidates) }
                val current = _state.value.device
                when {
                    // 当前受控设备仍在册 → 保持不动（黏性选择）
                    current != null && candidates.any { it.id == current.id } -> Unit
                    // 恰好一台 → 自动附着；0 台或多台 → 脱离（多台等用户选择，用户要求）
                    candidates.size == 1 -> attach(candidates.first())
                    else -> attach(null)
                }
            }
        }
    }

    /** 多设备时的手动选择（候选 chips / 选择列表）。 */
    fun selectDevice(id: String) {
        if (id == _state.value.device?.id) return
        _state.value.candidates.firstOrNull { it.id == id }?.let { attach(it) }
    }

    private fun attach(device: ScannedDevice?) {
        console?.stop()
        console = null
        attachJob?.cancel()
        opsScope.cancel() // 取消旧设备的在飞 op/sendPower（含 pullLog），防其回写已重置的状态
        opsScope = newOpsScope()
        _state.value = ConsoleUiState(device = device, candidates = _state.value.candidates)
        opLines.clear()
        if (device == null) return
        val c = S7Console(ble, device.id, viewModelScope, clock, zone)
        console = c
        c.start()
        attachJob = viewModelScope.launch {
            launch {
                // 链路状态跟踪 + 首次转入 CONNECTED 才发 refreshAll：
                // registry.add 先于 connect 完成（CONNECTING 时 write 会被丢弃白吃超时）。
                var refreshed = false
                ble.linkState(device.id).collect { l ->
                    _state.update { it.copy(link = l) }
                    if (l == LinkState.CONNECTED && !refreshed) {
                        refreshed = true
                        refreshAll()
                    }
                }
            }
            launch {
                c.opLog.collect { line ->
                    opLines.add(line)
                    if (opLines.size > 200) opLines.removeRange(0, opLines.size - 200)
                }
            }
            launch { c.heartbeat.collect { hb -> if (hb != null) _state.update { it.copy(heartbeat = hb) } } }
        }
    }

    /** 串行动作壳：busy 互斥 + 错误落 state + 失败 toast。跑在 opsScope（随 attach 取消）。 */
    private fun op(label: String, block: suspend (S7Console) -> Unit) {
        val c = console ?: return
        if (_state.value.busy != null) return
        opsScope.launch {
            _state.update { it.copy(busy = label, error = null) }
            try {
                block(c)
            } catch (e: S7CommandException) {
                _state.update { it.copy(error = failureText(e.failure)) }
                _toasts.tryEmit(ConsoleToast.Failed(failureText(e.failure)))
            } finally {
                // 双保险：console 已被切换（attach 竞态窗口）时不回写新状态
                if (console === c) _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun failureText(f: S7Failure): String = when (f) {
        is S7Failure.Timeout -> "TIMEOUT"
        is S7Failure.DeviceError -> f.name
    }

    /** 逐项读全量（单项失败不阻断其余，首个错误上报）。 */
    fun refreshAll() = op("refresh") { c ->
        var firstError: S7Failure? = null
        suspend fun <T> step(read: suspend () -> T, apply: (T) -> Unit) {
            try {
                apply(read())
            } catch (e: S7CommandException) {
                if (firstError == null) firstError = e.failure
            }
        }
        step({ c.getDeviceInfo() }) { v -> _state.update { it.copy(info = v) } }
        step({ c.getSnInfo() }) { v -> _state.update { it.copy(sn = v) } }
        step({ c.getDevFunc() }) { v -> _state.update { it.copy(devFunc = v) } }
        step({ c.getBondState() }) { v -> _state.update { it.copy(bondState = v) } }
        step({ c.getBattery() }) { v -> _state.update { it.copy(battery = v) } }
        step({ c.getDateTime() }) { v -> _state.update { it.copy(deviceTime = v, driftSec = c.driftSeconds(v)) } }
        step({ c.getPerson() }) { v -> _state.update { it.copy(person = v) } }
        val err = firstError
        if (err != null) {
            _state.update { it.copy(error = failureText(err)) }
            _toasts.tryEmit(ConsoleToast.Failed(failureText(err)))
        } else {
            _toasts.tryEmit(ConsoleToast.Refreshed)
        }
    }

    fun syncTime() = op("syncTime") { c ->
        val dt = c.syncTime()
        _state.update { it.copy(deviceTime = dt, driftSec = c.driftSeconds(dt)) }
        _toasts.tryEmit(ConsoleToast.TimeSynced)
    }

    /** 自定义对时：把用户填的 [dt] 写入设备（测试跨时区 / 过零点）。 */
    fun setCustomTime(dt: S7DateTime) = op("setTime") { c ->
        val applied = c.setDateTime(dt)
        _state.update { it.copy(deviceTime = applied, driftSec = c.driftSeconds(applied)) }
        _toasts.tryEmit(ConsoleToast.TimeSynced)
    }

    /** 编辑对话框预填：设备当前时间，或手机本地时间。 */
    fun phoneNowDateTime(): S7DateTime {
        val p = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        return S7DateTime(p.year, p.month, p.day, p.hour, p.minute, p.second, week = 1, timezone = 0)
    }

    fun toggleFind() = op("find") { c ->
        val target = !_state.value.finding
        c.findWatch(target)
        _state.update { it.copy(finding = target) }
        _toasts.tryEmit(ConsoleToast.FindToggled(target))
    }

    /** 快捷：把当前采集用户写入设备（无当前用户则报 NO_SUBJECT）。 */
    fun writeCurrentSubject() = op("writePerson") { c ->
        val current = currentSubject()
        if (current == null) {
            _state.update { it.copy(error = "NO_SUBJECT") }
            _toasts.tryEmit(ConsoleToast.Failed("NO_SUBJECT"))
            return@op
        }
        c.setPerson(current.toS7Person())
        _state.update { it.copy(person = c.getPerson()) }
        _toasts.tryEmit(ConsoleToast.PersonWritten)
    }

    /** 编辑后写入：UI 表单给出的值直接写设备（用户要求可修改）。 */
    fun writePerson(person: S7Person) = op("writePerson") { c ->
        c.setPerson(person)
        _state.update { it.copy(person = c.getPerson()) }
        _toasts.tryEmit(ConsoleToast.PersonWritten)
    }

    private suspend fun currentSubject(): Subject? {
        val id = subjects.currentId.first() ?: return null
        return subjects.subjects.first().firstOrNull { it.id == id }
    }

    /**
     * 拉取【设备固件运行日志】（DEV_CTRL 0x07 → now + bak 两文件），导出到公共
     * `Download/BlueTrace/logs/`（用户可用文件管理器直接找到）。
     */
    fun pullLog() = op("pullLog") { c ->
        _state.update { it.copy(logRunning = true, logChunks = 0, logBytes = 0) }
        try {
            val bytes = c.pullLog { p ->
                _state.update { it.copy(logChunks = p.chunks, logBytes = p.bytes) }
            }
            if (bytes.isEmpty()) {
                _toasts.tryEmit(ConsoleToast.Failed("NO_DATA"))
                return@op
            }
            val ts = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds()).compact()
            val mac = _state.value.device?.address ?: "unknown"
            // 存进设备日志文件夹（以 MAC 区分文件名，可在「查看日志」列表选看）
            val path = logStore.save(bytes, mac, ts)
            _state.update { it.copy(logAvailable = true) }
            _toasts.tryEmit(ConsoleToast.Exported(path))
        } finally {
            _state.update { it.copy(logRunning = false) }
        }
    }

    /** 导出【操作日志】（TX/RX 面板内容）到公共 Download/BlueTrace/logs/。 */
    fun exportOpLog() {
        if (opLines.isEmpty()) {
            _toasts.tryEmit(ConsoleToast.Failed("NO_DATA"))
            return
        }
        val snapshot = opLines.toList()
        viewModelScope.launch {
            val text = snapshot.joinToString("\n") { line ->
                epochMsToLocalParts(line.timeMs, zone.offsetSeconds()).timeCompact() + " " + line.text
            }
            val ts = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds()).compact()
            when (val r = exporter.exportLog(text, "s7_oplog_$ts.log")) {
                is ExportResult.Success -> _toasts.tryEmit(ConsoleToast.Exported(r.displayPath))
                is ExportResult.Error -> _toasts.tryEmit(ConsoleToast.ExportFailed(r.message))
                else -> _toasts.tryEmit(ConsoleToast.ExportFailed("SAVE_FAILED"))
            }
        }
    }

    /** 操作日志时间戳的本地时区偏移（屏渲染用；勿用 UTC，对时排障要与「设备时间」栏对得上）。 */
    fun zoneOffsetSeconds(): Int = zone.offsetSeconds()

    /** 危险命令：labelKey 用于确认对话框与等待条展示（string res id）。 */
    fun sendPower(key: Int, labelKey: Int) {
        val c = console ?: return
        if (_state.value.busy != null) return
        // 前置链路检查：确认对话框挂起期间链路可能已断（协议层还有一道，双保险）
        if (_state.value.link != LinkState.CONNECTED) {
            _state.update { it.copy(danger = DangerState.Done(false)) }
            return
        }
        opsScope.launch {
            _state.update { it.copy(busy = "power", danger = DangerState.Waiting(labelKey), error = null) }
            val ok = try {
                c.sendPowerCommand(key)
            } catch (e: Exception) {
                // 真实 GATT write 可能抛异常：danger 必须到达 Done，否则 Waiting 对话框永久卡死
                false
            } finally {
                if (console === c) _state.update { it.copy(busy = null) }
            }
            _state.update { it.copy(danger = DangerState.Done(ok)) }
        }
    }

    fun dismissDanger() = _state.update { it.copy(danger = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        console?.stop()
    }

    companion object {
        val POWER_KEYS = Triple(S7.CTRL_RESET, S7.CTRL_POWER_OFF, S7.CTRL_RESTORE)
    }
}

/** Subject → S7Person（性别编码 0/1/2 语义待实机核对，audit 清单）。 */
private fun Subject.toS7Person(): S7Person {
    val parts = birth.split("-")
    return S7Person(
        heightCm = heightCm ?: 170,
        weightKg = (weightKg ?: 65.0).toInt(),
        gender = when (sex) {
            Sex.MALE -> 1
            Sex.FEMALE -> 0
            Sex.OTHER -> 2
        },
        birthYear = parts.getOrNull(0)?.toIntOrNull() ?: 1990,
        birthMonth = parts.getOrNull(1)?.toIntOrNull() ?: 1,
        birthDay = parts.getOrNull(2)?.toIntOrNull() ?: 1,
    )
}
