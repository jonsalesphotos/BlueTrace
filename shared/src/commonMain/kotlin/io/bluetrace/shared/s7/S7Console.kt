package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 操作日志行(控制台调试面板).  */
data class S7OpLine(val timeMs: Long, val text: String)

/** 一次命令的失败原因.  */
sealed interface S7Failure {
    data object Timeout : S7Failure
    data class DeviceError(val code: Int) : S7Failure {
        val name: String get() = S7.errorName[code] ?: "0x${code.toString(16)}"
    }
}

class S7CommandException(val failure: S7Failure) :
    Exception(if (failure is S7Failure.DeviceError) "device error ${failure.name}" else "timeout")

/** 日志拉取进度.  */
data class S7LogPullProgress(val chunks: Int, val bytes: Int, val done: Boolean)

/**
 * S7 设备维护控制会话(每已连接设备一个实例; [start] 订阅, [stop] 释放).
 *
 * 可靠性层(协议未定义, App 自建 —— spec §5):
 * - **单飞串行**: 同一时刻最多一个未决请求(无事务 ID, 同 Cmd/Key 并发无法区分应答);
 * - **超时**: 默认 3s; 超时抛 [S7CommandException], 重试交给用户手动触发;
 * - **不回包命令**(关机/重启/恢复出厂): 发送后以 LinkState 断链为成功判据(10s 窗口);
 * - 设备主动帧(心跳/日志块)由分发器先行拦截, 不干扰请求-应答配对.
 */
class S7Console(
    private val ble: BleClient,
    val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val requestTimeoutMs: Long = 3_000,
    private val logIdleTimeoutMs: Long = 4_000,
    private val disconnectWaitMs: Long = 10_000,
) {
    private val decoder = S7FrameDecoder()
    private val commandMutex = Mutex()
    private val responses = Channel<S7Message>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var logSink: Channel<ByteArray>? = null
    private var collectJob: Job? = null

    /** 上一次超时请求的 (cmd,key,截止时刻): 迟到应答在截止前到达则丢弃, 防错配下一个同键请求.  */
    private var poisoned: Triple<Int, Int, Long>? = null

    private val _opLog = MutableSharedFlow<S7OpLine>(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<S7OpLine> = _opLog

    private val _heartbeat = MutableStateFlow<S7Heartbeat?>(null)
    val heartbeat: StateFlow<S7Heartbeat?> = _heartbeat

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            launch {
                ble.notifications(deviceId).collect { n ->
                    for (msg in decoder.feed(n.rawBytes)) route(msg)
                }
            }
            // 重连守卫: 非 CONNECTED → CONNECTED 转变时清分片缓冲与残留应答
            //(S7FrameDecoder 契约「重连须 reset」; 防跨连接拼出脏多包消息).
            launch {
                var prev: LinkState? = null
                ble.linkState(deviceId).collect { l ->
                    if (l == LinkState.CONNECTED && prev != null && prev != LinkState.CONNECTED) {
                        decoder.reset()
                        drainStale()
                        log("reconnected → decoder reset")
                    }
                    prev = l
                }
            }
        }
        log("console attach $deviceId")
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        decoder.reset()
    }

    private fun route(msg: S7Message) {
        when {
            msg.cmd == S7.CMD_IND && msg.key == S7.IND_HEARTBEAT -> {
                S7Heartbeat.parse(msg.param)?.let { _heartbeat.value = it }
                log("RX heartbeat seq=${_heartbeat.value?.seq} batt=${_heartbeat.value?.batteryPercent}%")
            }
            msg.cmd == S7.CMD_DEV_CTRL && msg.key == S7.CTRL_ACK_FILE_LOG && logSink != null -> {
                logSink?.trySend(msg.param)
            }
            // 日志拉取期间设备对 0x07/0x07 回错误 CommAck(BUSY/PARAM…)→ 以异常关闭块通道,
            // 避免被 4s 空闲超时静默吞成「空日志成功」.
            msg.cmd == S7.CMD_DEV_CTRL && msg.key == S7.CTRL_FILE_LOG && logSink != null &&
                msg.param.isNotEmpty() && msg.param[0].toInt() != 0 -> {
                val code = msg.param[0].toInt() and 0xFF
                log("RX pull-log NAK ${S7.errorName[code] ?: code}")
                logSink?.close(S7CommandException(S7Failure.DeviceError(code)))
            }
            else -> {
                log("RX cmd=0x${msg.cmd.toString(16)} key=0x${msg.key.toString(16)} ${msg.param.toHexPreview()}")
                responses.trySend(msg)
            }
        }
    }

    /** 发请求并等待同 Cmd/Key 应答(单飞; 带迟到应答防错配).  */
    private suspend fun request(cmd: Int, key: Int, param: ByteArray = ByteArray(0)): S7Message =
        commandMutex.withLock {
            drainStale()
            val frame = S7FrameCodec.encodeRequest(cmd, key, param)
            log("TX cmd=0x${cmd.toString(16)} key=0x${key.toString(16)} ${frame.toHexPreview()}")
            ble.write(deviceId, frame)
            val reply = withTimeoutOrNull(requestTimeoutMs) {
                while (true) {
                    val msg = responses.receive()
                    if (msg.cmd == cmd && msg.key == key) {
                        // 迟到应答防错配: 上一个同键请求超时后, 截止时间内到达的首个应答按污染丢弃
                        val p = poisoned
                        if (p != null && p.first == cmd && p.second == key && clock.nowMs() < p.third) {
                            poisoned = null
                            log("drop poisoned rx cmd=0x${cmd.toString(16)} key=0x${key.toString(16)}")
                            continue
                        }
                        return@withTimeoutOrNull msg
                    }
                    log("drop stale rx cmd=0x${msg.cmd.toString(16)} key=0x${msg.key.toString(16)}")
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (reply == null) {
                poisoned = Triple(cmd, key, clock.nowMs() + requestTimeoutMs)
                log("TIMEOUT cmd=0x${cmd.toString(16)} key=0x${key.toString(16)}")
                throw S7CommandException(S7Failure.Timeout)
            }
            reply
        }

    private fun drainStale() {
        while (true) {
            val r = responses.tryReceive()
            if (r.isSuccess) log("drain stale") else break
        }
    }

    /** 期待 1B CommAck 的请求; 非 0 抛 DeviceError.  */
    private suspend fun requestAck(cmd: Int, key: Int, param: ByteArray = ByteArray(0)) {
        val reply = request(cmd, key, param)
        if (!S7.commAckOk(reply.param)) {
            val code = if (reply.param.isNotEmpty()) reply.param[0].toInt() and 0xFF else 0x01
            throw S7CommandException(S7Failure.DeviceError(code))
        }
    }

    // ---- 维护操作 ----

    suspend fun getDateTime(): S7DateTime =
        S7DateTime.parse(request(S7.CMD_GET, S7.KEY_DATE_TIME).param)
            ?: throw S7CommandException(S7Failure.DeviceError(0x03))

    /** 手机当前本地时间 → SET(timezone=0 保持设备时区不改, spec §4.1). 返回同步后的设备时间.  */
    suspend fun syncTime(): S7DateTime {
        val parts = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        return setDateTime(
            S7DateTime(
                parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second,
                week = weekday(parts.year, parts.month, parts.day),
                timezone = 0,
            ),
        )
    }

    /**
     * SET 任意日期时间(自定义对时, 用于测试跨时区 / 过零点).
     * week 字段由 y/m/d 自算(固件通常也自算); timezone 见 [S7DateTime](SET 时 0=保持设备时区).
     * @return SET 后读回的设备时间.
     */
    suspend fun setDateTime(dt: S7DateTime): S7DateTime {
        val fixed = dt.copy(week = weekday(dt.year, dt.month, dt.day))
        requestAck(S7.CMD_SET, S7.KEY_DATE_TIME, fixed.encode())
        return getDateTime()
    }

    /** Zeller 星期(1=周一 … 7=周日).  */
    private fun weekday(y: Int, m: Int, d: Int): Int {
        val (yy, mm) = if (m < 3) (y - 1) to (m + 12) else y to m
        val k = yy % 100
        val j = yy / 100
        val h = (d + (13 * (mm + 1)) / 5 + k + k / 4 + j / 4 + 5 * j) % 7 // 0=周六…
        return ((h + 5) % 7) + 1 // → 1=周一…7=周日
    }

    /** 设备时间与手机时间的偏差秒数(正=设备快). 粗算: 同时区假设, UI 展示用.  */
    fun driftSeconds(deviceTime: S7DateTime): Long {
        val phone = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        fun toSec(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long {
            // 天数差用简化公历(控制台展示偏差, 非精确历法; 跨月足够准)
            val days = (y - 1970L) * 365 + (y - 1969) / 4 + monthDays(y, mo) + d
            return days * 86400 + h * 3600L + mi * 60L + s
        }
        return toSec(deviceTime.year, deviceTime.month, deviceTime.day, deviceTime.hour, deviceTime.minute, deviceTime.second) -
            toSec(phone.year, phone.month, phone.day, phone.hour, phone.minute, phone.second)
    }

    private fun monthDays(year: Int, month: Int): Int {
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        return cum[month - 1] + if (leap && month > 2) 1 else 0
    }

    suspend fun getDeviceInfo(): S7DeviceInfo =
        S7DeviceInfo.parse(request(S7.CMD_GET, S7.KEY_DEV_INFO).param)
            ?: throw S7CommandException(S7Failure.DeviceError(0x03))

    suspend fun getSnInfo(): S7SnInfo =
        S7SnInfo.parse(request(S7.CMD_GET, S7.KEY_SN_INFO).param)
            ?: throw S7CommandException(S7Failure.DeviceError(0x03))

    suspend fun getBattery(): S7Battery =
        S7Battery.parse(request(S7.CMD_GET, S7.KEY_DEV_VOL).param)
            ?: throw S7CommandException(S7Failure.DeviceError(0x03))

    /** 功能掩码 u32(逐位含义文档缺失, 仅 hex 展示).  */
    suspend fun getDevFunc(): Long {
        val p = request(S7.CMD_GET, S7.KEY_DEV_FUNC).param
        if (p.size < 4) throw S7CommandException(S7Failure.DeviceError(0x03))
        return (p[0].toLong() and 0xFF) or ((p[1].toLong() and 0xFF) shl 8) or
            ((p[2].toLong() and 0xFF) shl 16) or ((p[3].toLong() and 0xFF) shl 24)
    }

    suspend fun getBondState(): Int {
        val p = request(S7.CMD_GET, S7.KEY_BOND_STATE).param
        if (p.isEmpty()) throw S7CommandException(S7Failure.DeviceError(0x03))
        return p[0].toInt() and 0xFF
    }

    suspend fun getPerson(): S7Person =
        S7Person.parse(request(S7.CMD_GET, S7.KEY_PERSON_DATA).param)
            ?: throw S7CommandException(S7Failure.DeviceError(0x03))

    suspend fun setPerson(person: S7Person) {
        requestAck(S7.CMD_SET, S7.KEY_PERSON_DATA, person.encodeSet())
    }

    /**
     * 逐项读全量快照(B3 下沉自 app 层编排): 单项失败不阻断其余, 失败项为 null,
     * 首个错误记入 [S7Snapshot.firstError] 供上层提示.
     */
    suspend fun readAll(): S7Snapshot {
        var firstError: S7Failure? = null
        suspend fun <T> step(read: suspend () -> T): T? = try {
            read()
        } catch (e: S7CommandException) {
            if (firstError == null) firstError = e.failure
            null
        }
        val info = step { getDeviceInfo() }
        val sn = step { getSnInfo() }
        val devFunc = step { getDevFunc() }
        val bondState = step { getBondState() }
        val battery = step { getBattery() }
        val deviceTime = step { getDateTime() }
        val person = step { getPerson() }
        return S7Snapshot(
            info = info,
            sn = sn,
            devFunc = devFunc,
            bondState = bondState,
            battery = battery,
            deviceTime = deviceTime,
            driftSec = deviceTime?.let { driftSeconds(it) },
            person = person,
            firstError = firstError,
        )
    }

    suspend fun findWatch(start: Boolean) {
        requestAck(S7.CMD_DEV_CTRL, if (start) S7.CTRL_FIND else S7.CTRL_FIND_END)
    }

    /**
     * 日志拉取(DEV_CTRL 0x07 → 0x09 块流). 协议无 EOF: **空闲 [logIdleTimeoutMs] 判完成**(spec §4.8),
     * 结果可能不完整(UI 需提示). 每块前缀 = 请求 payload 回显, 剥除后拼接.
     */
    suspend fun pullLog(onProgress: (S7LogPullProgress) -> Unit = {}): ByteArray = commandMutex.withLock {
        val reqPayload = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00) // ucModel=1 + szPassthru(示例帧口径)
        val sink = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        logSink = sink
        try {
            val frame = S7FrameCodec.encodeRequest(S7.CMD_DEV_CTRL, S7.CTRL_FILE_LOG, reqPayload)
            log("TX pull-log ${frame.toHexPreview()}")
            ble.write(deviceId, frame)
            val assembled = ArrayList<Byte>(8192)
            var chunks = 0
            while (true) {
                val chunk = withTimeoutOrNull(logIdleTimeoutMs) { sink.receive() } ?: break
                val body = if (chunk.size >= reqPayload.size) chunk.copyOfRange(reqPayload.size, chunk.size) else ByteArray(0)
                for (b in body) assembled.add(b)
                chunks++
                onProgress(S7LogPullProgress(chunks, assembled.size, done = false))
            }
            log("pull-log done chunks=$chunks bytes=${assembled.size} (idle-timeout heuristic)")
            onProgress(S7LogPullProgress(chunks, assembled.size, done = true))
            assembled.toByteArray()
        } finally {
            logSink = null
            sink.close()
        }
    }

    /**
     * 不回包命令(关机 0x01 / 重启 0x02 / 恢复出厂 0x03): 固件强制不发响应 →
     * 以 [disconnectWaitMs] 内观察到断链为成功(spec §4.7).
     */
    suspend fun sendPowerCommand(key: Int): Boolean = commandMutex.withLock {
        require(key == S7.CTRL_POWER_OFF || key == S7.CTRL_RESET || key == S7.CTRL_RESTORE) { "not a power command: $key" }
        // 前置链路检查: 已断链/重连中时 write 会被静默丢弃, 此时报「生效」是对不可逆命令的假成功
        //(TOCTOU: 确认对话框挂起期间链路可能已变化).
        if (ble.linkState(deviceId).value != LinkState.CONNECTED) {
            log("power-cmd key=0x${key.toString(16)} 拒发：链路非 CONNECTED")
            return@withLock false
        }
        val frame = S7FrameCodec.encodeRequest(S7.CMD_DEV_CTRL, key, needAck = false)
        log("TX power-cmd key=0x${key.toString(16)}（协议不回包，等待断链…）")
        ble.write(deviceId, frame)
        // 只把 write 之后发生的 CONNECTED→非 CONNECTED 转变计为成功(上面已确认当前值为 CONNECTED,
        // 故 first{!=CONNECTED} 必然等到真实转变, 不会命中订阅时初值).
        val disconnected = withTimeoutOrNull(disconnectWaitMs) {
            ble.linkState(deviceId).first { it != LinkState.CONNECTED }
        } != null
        log(if (disconnected) "device link down → 命令生效" else "${disconnectWaitMs / 1000}s 未断链 → 未观测到复位")
        disconnected
    }

    private fun log(text: String) {
        _opLog.tryEmit(S7OpLine(clock.nowMs(), text))
    }
}

/** [S7Console.readAll] 的全量读结果: 读失败的项为 null(上层保留旧值).  */
data class S7Snapshot(
    val info: S7DeviceInfo? = null,
    val sn: S7SnInfo? = null,
    val devFunc: Long? = null,
    val bondState: Int? = null,
    val battery: S7Battery? = null,
    val deviceTime: S7DateTime? = null,
    val driftSec: Long? = null,
    val person: S7Person? = null,
    val firstError: S7Failure? = null,
)
