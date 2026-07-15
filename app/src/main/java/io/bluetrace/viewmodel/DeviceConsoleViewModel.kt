package io.bluetrace.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.data.android.ExportResult
import io.bluetrace.data.android.MediaStoreExporter
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.data.android.DeviceLogStore
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.DeviceCmdInfo
import io.bluetrace.shared.device.DeviceCommandException
import io.bluetrace.shared.device.DeviceCommandFailure
import io.bluetrace.shared.device.DeviceControl
import io.bluetrace.shared.device.DeviceSessionManager
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.s7.S7Battery
import io.bluetrace.shared.s7.S7DateTime
import io.bluetrace.shared.s7.S7Heartbeat
import io.bluetrace.shared.s7.S7OpLine
import io.bluetrace.shared.s7.S7Person
import io.bluetrace.shared.s7.S7SnInfo
import io.bluetrace.shared.s7.S7VendorOps
import io.bluetrace.shared.s7.toS7Person
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import kotlinx.coroutines.CancellationException
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

/** 危险命令(关机/重启/恢复出厂, 协议不回包)执行态.  */
sealed interface DangerState {
    data class Waiting(val labelKey: Int) : DangerState
    data class Done(val ok: Boolean) : DangerState
}

/** 危险命令类型: 重启/关机走通用电源面; 恢复出厂是 S7 vendor 专属(电源面只有重启/关机).  */
enum class DangerAction { REBOOT, POWER_OFF, RESTORE }

/** 一次性提示事件(土 toast)——语义在 VM, 文案在 UI 拼(VM 不引 R.string).  */
sealed interface ConsoleToast {
    data object TimeSynced : ConsoleToast
    data object PersonWritten : ConsoleToast
    data class FindToggled(val on: Boolean) : ConsoleToast
    data object Refreshed : ConsoleToast
    /** 指令失败(错误码短串, 如 TIMEOUT / NOT_SUPPORT).  */
    data class Failed(val code: String) : ConsoleToast
    /** 文件已导出到公共 Download 路径.  */
    data class Exported(val displayPath: String) : ConsoleToast
    data class ExportFailed(val reason: String) : ConsoleToast
}

data class ConsoleUiState(
    val device: ScannedDevice? = null,
    /** 可控候选(已连接的非参考设备): >1 台时需用户选择(进入前多连场景).  */
    val candidates: List<ScannedDevice> = emptyList(),
    val link: LinkState = LinkState.DISCONNECTED,
    /** 非 null = 有命令在飞(按钮禁用), 值为动作标签(诊断用).  */
    val busy: String? = null,
    // ---- 分面存在性(W5 按此显隐功能块; 当前 S7 六面全有, 异构第二协议 W6 靠此隐藏缺失能力)----
    val hasInfo: Boolean = false,
    val hasBattery: Boolean = false,
    val hasTimeSync: Boolean = false,
    val hasLogs: Boolean = false,
    val hasPower: Boolean = false,
    /** vendor 面可转型 S7VendorOps(S7 专属块显隐 + 富信息数据源).  */
    val hasVendorS7: Boolean = false,
    // ---- 通用面数据(info/battery 分面)----
    /** 版本信息(swVer=FW, extras 含 modemVer/secBlVer/bpVer).  */
    val cmdInfo: DeviceCmdInfo? = null,
    val batteryPct: Int? = null,
    // ---- S7 vendor 富信息(hasVendorS7 时有)----
    val sn: S7SnInfo? = null,
    val devFunc: Long? = null,
    val bondState: Int? = null,
    /** 电压/容量等 S7 电量细节(通用 battery 面只给百分比).  */
    val batteryDetail: S7Battery? = null,
    val deviceTime: S7DateTime? = null,
    val driftSec: Long? = null,
    val person: S7Person? = null,
    val heartbeat: S7Heartbeat? = null,
    val finding: Boolean = false,
    val logRunning: Boolean = false,
    val logChunks: Int = 0,
    val logBytes: Int = 0,
    /** 本次会话已拉到日志("查看日志"按钮可用).  */
    val logAvailable: Boolean = false,
    val danger: DangerState? = null,
    /** 错误码/原因短串(TIMEOUT / NOT_SUPPORT …), UI 套 console_err_fmt.  */
    val error: String? = null,
)

/**
 * 设备维护(DUT)控制台 VM —— **DeviceSessionManager 的首个运行时消费方**(W5).
 *
 * 设备来源: [ConnectionRegistry] 中首个可维护设备(连接页先连; 识别归 DeviceProfileCatalog).
 * 控制走会话宿主 [DeviceSessionManager] 的 [io.bluetrace.shared.device.DeviceControl] 六分面:
 * - 通用块(版本/电量/对时/日志/电源)按分面 null 与否显隐 + 走分面语义操作;
 * - S7 专属块(SN/IMEI 等富信息 / 自定义对时 / 用户信息 / 找表 / 恢复出厂 / 心跳 / 操作日志)经
 *   `control.vendor as? S7VendorOps` 消费——vendor 非 S7 类型则整块不渲染(W6 异构设备);
 * - 失败统一按 [DeviceCommandException.failure] 分支展示, **不接触任何 S7 异常类型**.
 *
 * 连接保持(§5.3 退屏不断连): 会话缓存在 app 级宿主; 退屏**不 release**(onCleared 只让 viewModelScope
 * 收自身协程, S7 会话跑在宿主 app scope 存活, 回屏 acquire 复用); 显式断开(断开 chip)才 release.
 *
 * ⚠️ acquire 走 `connect(device, profile.gattSpec)` 声明式路径(W1 落地至今真机未跑; 已连设备再 acquire
 * 时 connect 幂等近似 no-op, 走探测建立的连接)——真机回归主线做.
 */
class DeviceConsoleViewModel(
    private val ble: BleClient,
    private val sessionManager: DeviceSessionManager,
    private val registry: ConnectionRegistry,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val subjects: SubjectRepository,
    private val exporter: MediaStoreExporter,
    private val logStore: DeviceLogStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state

    /** 一次性 toast 事件(UI collect 后弹土 toast).  */
    private val _toasts = MutableSharedFlow<ConsoleToast>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val toasts: SharedFlow<ConsoleToast> = _toasts

    /** 操作日志(monospace 面板; 上限 200 行防膨胀).  */
    val opLines = mutableStateListOf<S7OpLine>()

    // 当前附着会话的控制面(分面)与 S7 vendor 面(非 S7 则 null).
    private var control: DeviceControl? = null
    private var vendorS7: S7VendorOps? = null
    private var attachJob: Job? = null

    // 附着代际: teardown 递增. 在飞 op 的取消是异步的(cancel 只打标记), 其 catch/finally 在
    // 切换到新设备后仍会执行——所有跨挂起点的状态回写(busy/error/toast/danger)都必须校验
    // 发起时代际, 防旧设备协程清掉新设备的在飞状态(切 A→B 时 A 的 finally 清 B 的 busy).
    private var attachGen = 0

    /** 在飞操作作用域: 随 attach 重建——设备切换/断开时取消全部在飞 op, 防旧协程污染新状态.  */
    private var opsScope: CoroutineScope = newOpsScope()

    private fun newOpsScope() =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    init {
        viewModelScope.launch {
            registry.connected.collect { list ->
                // 候选 = 已连接的非参考设备(参考心率带不属控制台管辖)
                val candidates = list.filter { it.kind != DeviceKind.REFERENCE }
                _state.update { it.copy(candidates = candidates) }
                val current = _state.value.device
                when {
                    // 已选设备**始终黏附**: 断开(自断/掉链)也留在控制台, 可就地重连; 切换由用户手动(卡片/候选)
                    current != null -> Unit
                    // 首次进入且恰好一台 → 自动附着; 0 台或多台 → 脱离(多台等用户选择, 用户要求)
                    candidates.size == 1 -> attach(candidates.first())
                    else -> attach(null)
                }
            }
        }
    }

    /** 多设备时的手动选择(候选 chips / 选择列表).  */
    fun selectDevice(id: String) {
        if (id == _state.value.device?.id) return
        _state.value.candidates.firstOrNull { it.id == id }?.let { attach(it) }
    }

    /**
     * 重连当前受控设备: 控制台断开态**直接重连**(断开时已 release, 此处重新 acquire 建会话).
     * 不重置黏附显示(保留上次读值, 连上后 linkState 收集器触发一次 refreshAll 覆盖).
     */
    fun reconnect() {
        val device = _state.value.device ?: return
        val l = _state.value.link
        if (l == LinkState.CONNECTED || l == LinkState.CONNECTING) return
        teardownAttachment()
        _state.update { it.copy(link = LinkState.CONNECTING) }
        wire(device)
    }

    /**
     * 主动断开当前设备: **显式断开 → release 会话**(关控制面 + 断链 + 清宿主缓存), 从 registry 移除
     * (连接页/已连计数随之更新, 手表恢复广播, 连接页重新可见). 设备仍**黏附**在控制台显示为断开态,
     * 可点"重连"再连(重新 acquire).
     */
    fun disconnect() {
        val device = _state.value.device ?: return
        if (_state.value.link == LinkState.DISCONNECTED) return
        teardownAttachment()
        viewModelScope.launch {
            sessionManager.release(device.id) // 关控制面 + ble.disconnect + 清会话缓存
            registry.remove(device.id)
        }
        // 保留黏附显示(上次读值), 仅置断开态; ops 按钮据 link!=CONNECTED 自动禁用.
        _state.update { it.copy(link = LinkState.DISCONNECTED, busy = null, finding = false, danger = null) }
    }

    private fun attach(device: ScannedDevice?) {
        teardownAttachment()
        _state.value = ConsoleUiState(device = device, candidates = _state.value.candidates)
        opLines.clear()
        if (device == null) return
        wire(device)
    }

    /** 取消当前附着的协程与在飞 op, 清控制面引用(不动黏附的 _state 显示).  */
    private fun teardownAttachment() {
        attachGen++
        attachJob?.cancel()
        attachJob = null
        opsScope.cancel()
        opsScope = newOpsScope()
        control = null
        vendorS7 = null
    }

    /** 获取(或复用)会话 + 接线分面显隐 / 心跳 / 操作日志 / 链路收集.  */
    private fun wire(device: ScannedDevice) {
        attachJob = viewModelScope.launch {
            // 会话缓存在宿主(退屏 / 被动掉链均保留)则复用控制面, 只(重)连链路(spec 路径; 已连则幂等 no-op);
            // 无缓存(首次进入 / 显式断开 release 后)才 acquire 新建(identify -> connect(gattSpec) -> confirm -> 控制面).
            val cached = sessionManager.get(device.id)
            val session = if (cached != null) {
                ble.connect(device, cached.profile.gattSpec)
                cached
            } else {
                sessionManager.acquire(device)
            }
            if (session == null) {
                _state.update { it.copy(link = LinkState.DISCONNECTED, error = "NO_SESSION") }
                return@launch
            }
            val ctl = session.control
            val v = ctl?.vendor as? S7VendorOps
            control = ctl
            vendorS7 = v
            // 分面存在性 -> 显隐布尔(W6 缺某分面则对应块隐藏)
            _state.update {
                it.copy(
                    hasInfo = ctl?.info != null,
                    hasBattery = ctl?.battery != null,
                    hasTimeSync = ctl?.timeSync != null,
                    hasLogs = ctl?.logs != null,
                    hasPower = ctl?.power != null,
                    hasVendorS7 = v != null,
                )
            }
            // S7 vendor: 心跳 + 操作日志(W6 异构设备无此流, 整块不渲染)
            if (v != null) {
                launch { v.heartbeat.collect { hb -> if (hb != null) _state.update { it.copy(heartbeat = hb) } } }
                launch {
                    v.opLog.collect { line ->
                        opLines.add(line)
                        if (opLines.size > 200) opLines.removeRange(0, opLines.size - 200)
                    }
                }
            }
            // 链路状态 + 首次转入 CONNECTED 才 refreshAll(registry.add 先于 connect 完成, CONNECTING 时读会白吃超时)
            var refreshed = false
            ble.linkState(device.id).collect { l ->
                _state.update { it.copy(link = l) }
                when (l) {
                    LinkState.CONNECTED -> if (!refreshed) { refreshed = true; refreshAll() }
                    LinkState.DISCONNECTED -> refreshed = false
                    else -> Unit
                }
            }
        }
    }

    /**
     * 串行动作壳: busy 互斥 + 错误落 state + 失败 toast. 跑在 opsScope(随 attach 取消).
     * 状态回写全部校验发起时代际([attachGen]): 切换设备后旧协程的 catch/finally 不得触碰新附着的状态.
     */
    private fun op(label: String, block: suspend () -> Unit) {
        if (control == null) return
        if (_state.value.busy != null) return
        val gen = attachGen
        opsScope.launch {
            _state.update { it.copy(busy = label, error = null) }
            try {
                block()
            } catch (e: DeviceCommandException) {
                if (gen == attachGen) {
                    _state.update { it.copy(error = failureText(e.failure)) }
                    _toasts.tryEmit(ConsoleToast.Failed(failureText(e.failure)))
                }
            } finally {
                if (gen == attachGen) _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun failureText(f: DeviceCommandFailure): String = when (f) {
        DeviceCommandFailure.Timeout -> "TIMEOUT"
        is DeviceCommandFailure.DeviceError -> f.codeName
        DeviceCommandFailure.Unsupported -> "UNSUPPORT"
    }

    /**
     * 逐项读全量: 通用版本走 info 分面(S7 内部映射 swVer/extras), 其余富信息走 S7 vendor 面.
     * 单项失败不阻断其余(分面各自抛 [DeviceCommandException]), 首个错误上报.
     * 无 vendor(W6 异构设备): 只读通用 info/battery 分面.
     */
    fun refreshAll() = op("refresh") {
        val ctl = control ?: return@op
        val v = vendorS7
        if (v != null) {
            var firstErr: DeviceCommandFailure? = null
            suspend fun <T> step(read: suspend () -> T): T? = try {
                read()
            } catch (e: DeviceCommandException) {
                if (firstErr == null) firstErr = e.failure
                null
            }
            val info = ctl.info?.let { i -> step { i.get() } }
            val sn = step { v.snInfo() }
            val batt = step { v.battery() }
            val devFunc = step { v.devFunc() }
            val bond = step { v.bondState() }
            val time = step { v.dateTime() }
            val person = step { v.getPerson() }
            _state.update {
                it.copy(
                    cmdInfo = info ?: it.cmdInfo,
                    sn = sn ?: it.sn,
                    batteryPct = batt?.percent ?: it.batteryPct,
                    batteryDetail = batt ?: it.batteryDetail,
                    devFunc = devFunc ?: it.devFunc,
                    bondState = bond ?: it.bondState,
                    deviceTime = time ?: it.deviceTime,
                    driftSec = time?.let { t -> v.driftSeconds(t) } ?: it.driftSec,
                    person = person ?: it.person,
                )
            }
            val err = firstErr
            if (err != null) {
                _state.update { it.copy(error = failureText(err)) }
                _toasts.tryEmit(ConsoleToast.Failed(failureText(err)))
            } else {
                _toasts.tryEmit(ConsoleToast.Refreshed)
            }
        } else {
            // W6 异构设备: 只有通用分面
            val info = ctl.info?.get()
            val pct = ctl.battery?.percent()
            _state.update { it.copy(cmdInfo = info ?: it.cmdInfo, batteryPct = pct ?: it.batteryPct) }
            _toasts.tryEmit(ConsoleToast.Refreshed)
        }
    }

    /** 对时(通用 timeSync 面同步手机时间); S7 再回读设备时间刷新展示(通用面 sync 不回读).  */
    fun syncTime() = op("syncTime") {
        control?.timeSync?.sync()
        vendorS7?.let { v ->
            val dt = v.dateTime()
            _state.update { it.copy(deviceTime = dt, driftSec = v.driftSeconds(dt)) }
        }
        _toasts.tryEmit(ConsoleToast.TimeSynced)
    }

    /** 自定义对时(S7 vendor): 把用户填的 [dt] 写入设备(测跨时区 / 过零点).  */
    fun setCustomTime(dt: S7DateTime) = op("setTime") {
        val v = vendorS7 ?: return@op
        val applied = v.setDateTime(dt)
        _state.update { it.copy(deviceTime = applied, driftSec = v.driftSeconds(applied)) }
        _toasts.tryEmit(ConsoleToast.TimeSynced)
    }

    /** 编辑对话框预填: 手机本地时间.  */
    fun phoneNowDateTime(): S7DateTime {
        val p = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        return S7DateTime(p.year, p.month, p.day, p.hour, p.minute, p.second, week = 1, timezone = 0)
    }

    /** 找表(S7 vendor).  */
    fun toggleFind() = op("find") {
        val v = vendorS7 ?: return@op
        val target = !_state.value.finding
        v.findWatch(target)
        _state.update { it.copy(finding = target) }
        _toasts.tryEmit(ConsoleToast.FindToggled(target))
    }

    /** 快捷: 把当前采集用户写入设备(S7 vendor; 无当前用户则报 NO_SUBJECT).  */
    fun writeCurrentSubject() = op("writePerson") {
        val v = vendorS7 ?: return@op
        val current = currentSubject()
        if (current == null) {
            _state.update { it.copy(error = "NO_SUBJECT") }
            _toasts.tryEmit(ConsoleToast.Failed("NO_SUBJECT"))
            return@op
        }
        v.setPerson(current.toS7Person())
        _state.update { it.copy(person = v.getPerson()) }
        _toasts.tryEmit(ConsoleToast.PersonWritten)
    }

    /** 编辑后写入(S7 vendor): UI 表单给出的值直接写设备(用户要求可修改).  */
    fun writePerson(person: S7Person) = op("writePerson") {
        val v = vendorS7 ?: return@op
        v.setPerson(person)
        _state.update { it.copy(person = v.getPerson()) }
        _toasts.tryEmit(ConsoleToast.PersonWritten)
    }

    private suspend fun currentSubject(): Subject? {
        val id = subjects.currentId.first() ?: return null
        return subjects.subjects.first().firstOrNull { it.id == id }
    }

    /**
     * 拉取【设备固件运行日志】(logs 分面 → now + bak 两文件), 导出到公共
     * `Download/BlueTrace/log/firmware/`(用户可用文件管理器直接找到).
     */
    fun pullLog() = op("pullLog") {
        val logs = control?.logs ?: return@op
        // 块内 finally 也要代际保护: op 外层只护 busy/error, 本操作独有的 logRunning 在
        // MediaStore IO 中被取消(切设备)后, 延迟 finally 不得清掉新设备的拉取进度态.
        val gen = attachGen
        _state.update { it.copy(logRunning = true, logChunks = 0, logBytes = 0) }
        try {
            val bytes = logs.pull { chunks, bytes ->
                _state.update { it.copy(logChunks = chunks, logBytes = bytes) }
            }
            if (bytes.isEmpty()) {
                _toasts.tryEmit(ConsoleToast.Failed("NO_DATA"))
                return@op
            }
            val ts = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds()).compact()
            val mac = _state.value.device?.address ?: "unknown"
            // 经 MediaStore 存到公共 Download/BlueTrace/log/firmware/(以 MAC 区分文件名)
            val path = logStore.save(bytes, mac, ts)
            _state.update { it.copy(logAvailable = true) }
            _toasts.tryEmit(ConsoleToast.Exported(path))
        } finally {
            if (gen == attachGen) _state.update { it.copy(logRunning = false) }
        }
    }

    /** 导出【操作日志】(TX/RX 面板内容)到公共 Download/BlueTrace/log/app/.  */
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

    /** 操作日志时间戳的本地时区偏移(屏渲染用; 勿用 UTC, 对时排障要与"设备时间"栏对得上).  */
    fun zoneOffsetSeconds(): Int = zone.offsetSeconds()

    /**
     * 危险命令: 重启/关机走通用电源面([reboot]/[powerOff]), 恢复出厂走 S7 vendor([restore]).
     * labelKey 用于确认对话框与等待条展示(string res id).
     */
    fun sendDanger(action: DangerAction, labelKey: Int) {
        if (control == null) return
        if (_state.value.busy != null) return
        // 前置链路检查: 确认对话框挂起期间链路可能已断(协议层还有一道, 双保险)
        if (_state.value.link != LinkState.CONNECTED) {
            _state.update { it.copy(danger = DangerState.Done(false)) }
            return
        }
        val gen = attachGen
        opsScope.launch {
            _state.update { it.copy(busy = "power", danger = DangerState.Waiting(labelKey), error = null) }
            val ok = try {
                when (action) {
                    DangerAction.REBOOT -> control?.power?.reboot() ?: false
                    DangerAction.POWER_OFF -> control?.power?.powerOff() ?: false
                    DangerAction.RESTORE -> vendorS7?.restore() ?: false
                }
            } catch (c: CancellationException) {
                throw c // 取消(切换设备/退屏): 不写终态, 交给代际校验后的收尾
            } catch (e: Exception) {
                // 命令面可能抛(如 GATT 异常): danger 必须到达 Done, 否则 Waiting 对话框永久卡死
                false
            } finally {
                if (gen == attachGen) _state.update { it.copy(busy = null) }
            }
            if (gen == attachGen) _state.update { it.copy(danger = DangerState.Done(ok)) }
        }
    }

    fun dismissDanger() = _state.update { it.copy(danger = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        // 退屏**不 release**(§5.3 退屏不断连): 会话缓存在 app 级宿主, S7 会话跑在宿主 scope 存活,
        // 回屏 acquire 复用. 此处仅让 viewModelScope 收自身协程(框架自动).
    }
}
