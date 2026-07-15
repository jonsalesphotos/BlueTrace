package io.bluetrace.shared.uwtp

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** 一次 UWTP 命令的失败原因。 */
sealed interface UwtpFailure {
    /** App 本地等待响应超时。 */
    data object Timeout : UwtpFailure

    /** 设备响应 error_code != 0。 */
    data class DeviceError(val code: Int) : UwtpFailure {
        val name: String get() = Uwtp.errorName(code)
    }

    /** 响应 PB 解码失败或缺少契约必需结构(如 STATE 成功响应缺 runtime)。 */
    data class Decode(val reason: String) : UwtpFailure

    /** 等待期间链路断开。 */
    data object LinkDown : UwtpFailure
}

class UwtpCommandException(val failure: UwtpFailure) : Exception(
    when (failure) {
        is UwtpFailure.DeviceError -> "device error ${failure.name}"
        is UwtpFailure.Decode -> "decode: ${failure.reason}"
        UwtpFailure.Timeout -> "timeout"
        UwtpFailure.LinkDown -> "link down"
    },
)

/** 操作日志行(调试页 TX/RX 面板)。 */
data class UwtpLogLine(val timeMs: Long, val text: String)

/** 送往活动传输会话的入站事件(由 [UwtpClient] 分发, [UwtpFilePull] 消费)。 */
sealed interface TransferInbound {
    /** TRANSFER_DATA 帧原始 payload(前缀 + data, 按会话 crc_mode 解析)。 */
    data class Data(val payload: ByteArray) : TransferInbound

    /** TRANSFER_ERROR_EVENT 帧原始 payload(PB)。 */
    data class ErrorEvent(val payload: ByteArray) : TransferInbound

    /** BLE 链路断开(连接域事务即清, §9.5)。 */
    data object LinkDown : TransferInbound
}

/** [UwtpFilePull] 依赖的命令端口(便于单测注入假实现)。 */
interface UwtpTransferPort {
    suspend fun transferBegin(req: TransferBeginReq): TransferBeginRsp
    suspend fun transferFinish(transferId: Long)
    suspend fun transferAbort(transferId: Long)
    suspend fun sendTransferAck(transferId: Long, nextExpectedOffset: Long)
    fun nowMs(): Long
}

/** RX 分流丢弃计数(诊断)。 */
data class UwtpRxStats(
    val uwtpFrames: Int = 0,
    val malformed: Int = 0,
    val staleResponses: Int = 0,
    val unknownData: Int = 0,
    val deviceCommands: Int = 0,
    val orphanTransfer: Int = 0,
)

/**
 * UWTP v0.2 App 侧客户端(每已连接设备一个实例; [start] 订阅, [stop] 释放)。
 *
 * - 复用 legacy 管道: 同一 FFE2 notify 流上 `0xBB`=B2A(静默忽略, 不干扰既有路径),
 *   `(b&0xF0)==0x10`=UWTP(§13.1/§13.2);
 * - App-owned 同步命令面(§4.1): 单飞串行 + 超时; 响应凭 (main_type, opcode, seq) 配对,
 *   迟到/错配响应丢弃计数(固件逐消息无状态, 不检查 seq 连续性);
 * - 命令 seq 单字节回绕; ACK(feedback)按 (main_type, opcode) 独立 seq, 新事务清零(§4);
 * - 收到 NEED_RSP=1 的非响应帧 = 设备主动请求, S7 Profile 不存在此路径 -> 丢弃计数(§5.4)。
 */
class UwtpClient(
    private val ble: BleClient,
    val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val requestTimeoutMs: Long = 3_000,
    /** 延迟完成命令(BEGIN/FINISH/ABORT, §4.1)等待上限。 */
    private val slowRequestTimeoutMs: Long = 8_000,
) : UwtpTransferPort {

    private val commandMutex = Mutex()
    private var cmdSeq = 0
    private var ackSeq = 0

    private class Pending(
        val mainType: Int,
        val opcode: Int,
        val seq: Int,
        val deferred: CompletableDeferred<ByteArray>,
    )

    // 写方(命令协程/传输会话线程)与读方(notify 收集协程)可能不同线程: volatile 保证可见性
    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var transferSink: SendChannel<TransferInbound>? = null
    private var collectJob: Job? = null

    var stats = UwtpRxStats()
        private set

    private val _opLog = MutableSharedFlow<UwtpLogLine>(replay = 128, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<UwtpLogLine> = _opLog

    override fun nowMs(): Long = clock.nowMs()

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            launch {
                ble.notifications(deviceId).collect { n -> onNotify(n.rawBytes) }
            }
            launch {
                var prev: LinkState? = null
                ble.linkState(deviceId).collect { l ->
                    if (prev == LinkState.CONNECTED && l != LinkState.CONNECTED) {
                        // 断连 = 连接域上下文回位(§9.5): 在飞命令失败, 活动传输收 LinkDown
                        pending?.deferred?.completeExceptionally(UwtpCommandException(UwtpFailure.LinkDown))
                        transferSink?.trySend(TransferInbound.LinkDown)
                        log("link down -> pending/transfer 已通知")
                    }
                    prev = l
                }
            }
        }
        log("uwtp attach $deviceId")
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        pending?.deferred?.completeExceptionally(UwtpCommandException(UwtpFailure.LinkDown))
        pending = null
    }

    /** 活动传输会话挂接入站通道(BEGIN 前挂接, 会话结束后解除)。 */
    fun attachTransfer(sink: SendChannel<TransferInbound>) {
        transferSink = sink
        ackSeq = 0 // 新事务清零本 (main,opcode) 异步 seq(§4 Core seq 细则)
    }

    fun detachTransfer(sink: SendChannel<TransferInbound>) {
        if (transferSink === sink) transferSink = null
    }

    // ---- RX 分流 ----

    private fun onNotify(bytes: ByteArray) {
        when (val r = UwtpFrameCodec.decode(bytes)) {
            UwtpFrameDecode.NotUwtp -> Unit // B2A 或其他协议, 不属本客户端
            is UwtpFrameDecode.Malformed -> {
                stats = stats.copy(malformed = stats.malformed + 1)
                log("RX malformed(${r.reason}) ${bytes.toHexPreview()}")
            }
            is UwtpFrameDecode.Ok -> {
                stats = stats.copy(uwtpFrames = stats.uwtpFrames + 1)
                route(r.frame)
            }
        }
    }

    private fun route(f: UwtpFrame) {
        when {
            f.isResponse -> {
                val p = pending
                if (p != null && p.mainType == f.mainType && p.opcode == f.opcode && p.seq == f.seq) {
                    p.deferred.complete(f.payload)
                } else {
                    stats = stats.copy(staleResponses = stats.staleResponses + 1)
                    log("RX stale rsp mt=0x${f.mainType.toString(16)} op=0x${f.opcode.toString(16)} seq=${f.seq}")
                }
            }
            f.needRsp -> {
                // Device 不主动发起需响应的业务请求(§4); 出现即协议异常, 丢弃计数不回复
                stats = stats.copy(deviceCommands = stats.deviceCommands + 1)
                log("RX unexpected device command mt=0x${f.mainType.toString(16)} op=0x${f.opcode.toString(16)}")
            }
            f.mainType == Uwtp.MT_TRANSFER && f.opcode == Uwtp.OP_TRANSFER_DATA -> {
                val sink = transferSink
                if (sink == null || sink.trySend(TransferInbound.Data(f.payload)).isFailure) {
                    stats = stats.copy(orphanTransfer = stats.orphanTransfer + 1)
                }
            }
            f.mainType == Uwtp.MT_TRANSFER && f.opcode == Uwtp.OP_TRANSFER_ERROR_EVENT -> {
                val sink = transferSink
                if (sink == null || sink.trySend(TransferInbound.ErrorEvent(f.payload)).isFailure) {
                    stats = stats.copy(orphanTransfer = stats.orphanTransfer + 1)
                    log("RX orphan TRANSFER_ERROR_EVENT ${f.payload.toHexPreview()}")
                }
            }
            else -> {
                stats = stats.copy(unknownData = stats.unknownData + 1)
                log("RX unknown data mt=0x${f.mainType.toString(16)} op=0x${f.opcode.toString(16)}")
            }
        }
    }

    // ---- 命令面(单飞 await, §4.1) ----

    /**
     * 发业务命令并等待响应 payload。命令必答(§4): 超时抛 [UwtpCommandException]。
     * 返回原始响应 PB 字节(error_code 检查由 typed 包装做, 便于调用方拿到完整响应)。
     */
    suspend fun request(
        mainType: Int,
        opcode: Int,
        payload: ByteArray = UwtpFrameCodec.EMPTY,
        timeoutMs: Long = requestTimeoutMs,
    ): ByteArray = commandMutex.withLock {
        val seq = cmdSeq
        cmdSeq = (cmdSeq + 1) and 0xFF
        val frame = UwtpFrameCodec.encodeCommand(mainType, opcode, seq, payload)
        val deferred = CompletableDeferred<ByteArray>()
        pending = Pending(mainType, opcode, seq, deferred)
        try {
            log("TX cmd mt=0x${mainType.toString(16)} op=0x${opcode.toString(16)} seq=$seq ${frame.toHexPreview()}")
            ble.write(deviceId, frame)
            val rsp = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (rsp == null) {
                log("TIMEOUT mt=0x${mainType.toString(16)} op=0x${opcode.toString(16)} seq=$seq")
                throw UwtpCommandException(UwtpFailure.Timeout)
            }
            log("RX rsp op=0x${opcode.toString(16)} seq=$seq ${rsp.toHexPreview()}")
            rsp
        } finally {
            pending = null
        }
    }

    private fun checkError(payload: ByteArray) {
        val code = try {
            MinimalErrorRsp.readErrorCode(payload)
        } catch (e: PbDecodeException) {
            throw UwtpCommandException(UwtpFailure.Decode(e.message ?: "pb"))
        }
        if (code != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(code))
    }

    private inline fun <T> decodeRsp(payload: ByteArray, decode: (ByteArray) -> T): T = try {
        decode(payload)
    } catch (e: PbDecodeException) {
        throw UwtpCommandException(UwtpFailure.Decode(e.message ?: "pb"))
    }

    // ---- typed API ----

    /** CTRL INFO(§7): 固件版本 / registry_rev / RAW Schema 支持集合。 */
    suspend fun ctrlInfo(): CtrlInfoRsp {
        val rsp = decodeRsp(request(Uwtp.MT_CTRL, Uwtp.OP_CTRL_INFO), CtrlInfoRsp::decode)
        if (rsp.errorCode != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(rsp.errorCode))
        return rsp
    }

    /** CTRL STATE_QUERY(§9.3): 成功响应必须携带 runtime 子消息, 缺失按契约违约拒绝。 */
    suspend fun stateQuery(): CtrlRuntimeState {
        val payload = request(Uwtp.MT_CTRL, Uwtp.OP_CTRL_STATE_QUERY, CtrlStateQueryReq().encode())
        val rsp = decodeRsp(payload, CtrlStateQueryRsp::decode)
        if (rsp.errorCode != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(rsp.errorCode))
        return rsp.runtime ?: throw UwtpCommandException(UwtpFailure.Decode("STATE rsp missing runtime"))
    }

    /** OBJECT_LIST 单页。 */
    suspend fun objectListPage(cursor: Long): ObjectListRsp {
        val payload = request(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_OBJECT_LIST, ObjectListReq(cursor).encode())
        val rsp = decodeRsp(payload, ObjectListRsp::decode)
        if (rsp.errorCode != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(rsp.errorCode))
        return rsp
    }

    /** OBJECT_LIST 全量(cursor 翻页到 next_cursor=0; [maxPages] 防环)。 */
    suspend fun listAllObjects(maxPages: Int = 64): List<ObjectEntry> {
        val out = ArrayList<ObjectEntry>()
        var cursor = 0L
        repeat(maxPages) {
            val page = objectListPage(cursor)
            out.addAll(page.objects)
            if (page.nextCursor == 0L) return out
            cursor = page.nextCursor
        }
        throw UwtpCommandException(UwtpFailure.Decode("OBJECT_LIST 翻页超过 $maxPages 页"))
    }

    override suspend fun transferBegin(req: TransferBeginReq): TransferBeginRsp {
        val payload = request(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_BEGIN, req.encode(), slowRequestTimeoutMs)
        val rsp = decodeRsp(payload, TransferBeginRsp::decode)
        if (rsp.errorCode != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(rsp.errorCode))
        return rsp
    }

    override suspend fun transferFinish(transferId: Long) {
        checkError(request(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_FINISH, TransferIdReq(transferId).encode(), slowRequestTimeoutMs))
    }

    override suspend fun transferAbort(transferId: Long) {
        checkError(request(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_ABORT, TransferIdReq(transferId).encode(), slowRequestTimeoutMs))
    }

    /** DELETE_OBJECT(全局 BUSY 语义由固件把关)。 */
    suspend fun deleteObjects(fileIds: List<Long>): TransferDeleteRsp {
        val payload = request(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_DELETE, TransferDeleteReq(fileIds).encode(), slowRequestTimeoutMs)
        val rsp = decodeRsp(payload, TransferDeleteRsp::decode)
        if (rsp.errorCode != Uwtp.ERR_OK) throw UwtpCommandException(UwtpFailure.DeviceError(rsp.errorCode))
        return rsp
    }

    /** TRANSFER_ACK(feedback, NEED_RSP=0): 累计确认, 不等待响应。 */
    override suspend fun sendTransferAck(transferId: Long, nextExpectedOffset: Long) {
        val seq = ackSeq
        ackSeq = (ackSeq + 1) and 0xFF
        val frame = UwtpFrameCodec.encodeData(
            Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_ACK, seq,
            TransferAck(transferId, nextExpectedOffset).encode(),
        )
        ble.write(deviceId, frame)
        log("TX ack id=$transferId next=$nextExpectedOffset seq=$seq")
    }

    fun log(text: String) {
        _opLog.tryEmit(UwtpLogLine(clock.nowMs(), text))
    }
}

/** 十六进制预览(与 s7 包同型但避免跨包依赖)。 */
internal fun ByteArray.toHexPreview(maxBytes: Int = 24): String {
    val n = size.coerceAtMost(maxBytes)
    val sb = StringBuilder(n * 3 + 12)
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        val v = this[i].toInt() and 0xFF
        if (v < 0x10) sb.append('0')
        sb.append(v.toString(16))
    }
    if (size > n) sb.append(" ..(").append(size).append("B)")
    return sb.toString()
}
