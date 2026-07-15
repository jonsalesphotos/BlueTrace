package io.bluetrace.shared.uwtp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileHandle
import okio.FileSystem
import okio.Path

/** 落盘接口(按 offset 定位写; okio 实现见 [OkioPullSink], 单测用内存实现)。 */
interface PullSink {
    fun writeAt(offset: Long, data: ByteArray)

    /** 截到 [size] 字节(accepted_offset 回退 / 停止时裁掉窗口内空洞后数据)。 */
    fun truncate(size: Long)

    fun flush()

    fun close()
}

/** okio FileHandle 定位写(100MB 级文件不过内存)。 */
class OkioPullSink(fileSystem: FileSystem, path: Path) : PullSink {
    private val handle: FileHandle = fileSystem.openReadWrite(path)

    override fun writeAt(offset: Long, data: ByteArray) = handle.write(offset, data, 0, data.size)

    override fun truncate(size: Long) = handle.resize(size)

    override fun flush() = handle.flush()

    override fun close() = handle.close()
}

enum class PullState { BEGINNING, RECEIVING, FINISHING, DONE, FAILED, ABORTED }

/** 拉取进度快照(UI StateFlow)。速率为新增有效字节口径(去重后)。 */
data class PullProgress(
    val state: PullState = PullState.BEGINNING,
    val fileId: Long = 0,
    val transferId: Long = 0,
    val totalSize: Long = 0,
    /** 从 0 起算的最大连续偏移(含续传前已有部分)。 */
    val contiguous: Long = 0,
    /** 本次会话新增有效字节。 */
    val sessionBytes: Long = 0,
    /** 实时速率(近 2s 窗口, KiB/s)。 */
    val instantKibps: Double = 0.0,
    /** 会话平均速率(KiB/s)。 */
    val avgKibps: Double = 0.0,
    val frames: Int = 0,
    val dupFrames: Int = 0,
    val staleFrames: Int = 0,
    val crcErrors: Int = 0,
    val malformedFrames: Int = 0,
    val staleEvents: Int = 0,
) {
    val percent: Int get() = if (totalSize <= 0) 0 else ((contiguous * 100) / totalSize).toInt()
}

/** 拉取终局。[Failed.resumable] = 断点信息仍有效, 可凭 contiguous+token 重新 BEGIN。 */
sealed interface PullOutcome {
    data class Done(val totalSize: Long, val sessionBytes: Long, val elapsedMs: Long, val avgKibps: Double) : PullOutcome
    data class Failed(val reason: String, val deviceError: Int?, val contiguous: Long, val resumable: Boolean) : PullOutcome
    data class Aborted(val contiguous: Long) : PullOutcome
}

/**
 * 离线文件上传(手表->App)单事务状态机(工作稿 §12.4, 连接域单文件推流):
 *
 * ```
 * BEGIN(file_id, start_offset, crc_mode, ack_every_n, object_token, object_size)
 *   -> 按 accepted_* 生效 -> 收 DATA 按 offset 组装(幂等去重/旧 transfer_id 丢弃计数)
 *   -> 每收满 accepted_ack_every_n 帧或到文件尾发累计 ACK(next_expected_offset=最大连续偏移)
 *   -> 收齐 total_size 发 FINISH -> Done
 * ```
 *
 * - 只接受当前 transfer_id 的 DATA/ERROR_EVENT, 旧世代丢弃计数(事务世代隔离, D-9/P-6);
 * - 重复 offset 幂等去重(固件 T_ack 超时会整窗重发); 空洞由窗口重发补齐(offset 范围集合归并);
 * - 重复帧同样计入窗口帧数: 纯重发窗口(App 的 ACK 丢失时)也能再次触发累计 ACK, 打破僵持;
 * - ERROR_EVENT{error_code, transfer_id, last_valid_offset} = 设备已自行终止, App 不再 ABORT;
 * - BLE 断连 = 事务即清(D-7), 返回可续传 Failed, App 凭 contiguous+object_token 重新 BEGIN;
 * - [onAckSent] 每次 ACK 后回调最大连续偏移, 供 App 持久化断点(进度记在 App 侧, D-7);
 * - 一切退出路径把 sink 截到 contiguous: 文件长度 == 断点, 恢复语义简单可靠。
 */
class UwtpFilePull(
    private val port: UwtpTransferPort,
    private val sink: PullSink,
    private val fileId: Long,
    private val startOffset: Long = 0,
    private val objectToken: Long = 0,
    private val objectSize: Long = 0,
    private val requestCrcMode: Int = 0,
    private val requestAckEveryN: Int = 32, // Binding 暂定值, P0 实测前非冻结
    private val idleTimeoutMs: Long = 10_000,
    /** BEGIN 受理后回调(App 持久化权威 object_token/total_size, D-10)。 */
    private val onBegin: (TransferBeginRsp) -> Unit = {},
    private val onAckSent: (contiguous: Long) -> Unit = {},
) {
    /** 入站事件通道(UNLIMITED: 不丢 DATA; 深度由单未确认窗口机制天然约束)。 */
    val inbound: Channel<TransferInbound> = Channel(Channel.UNLIMITED)

    private val _progress = MutableStateFlow(PullProgress(fileId = fileId, contiguous = startOffset))
    val progress: StateFlow<PullProgress> = _progress

    private var abortRequested = false

    /** 请求中止(线程安全; 会话在下一条入站事件边界执行 ABORT)。 */
    fun requestAbort() {
        abortRequested = true
        inbound.trySend(AbortMarker)
    }

    private data object AbortMarker : TransferInbound

    // ---- 组装状态 ----
    private var transferId = 0L
    private var totalSize = 0L
    private var crcMode = 0
    private var ackEveryN = requestAckEveryN
    private var contiguous = startOffset
    private val ranges = OffsetRanges()
    private var framesSinceAck = 0
    private var lastAcked = -1L

    private var frames = 0
    private var dupFrames = 0
    private var staleFrames = 0
    private var crcErrors = 0
    private var malformed = 0
    private var staleEvents = 0
    private var sessionBytes = 0L

    // 速率: 近 2s 滑窗桶(250ms 粒度)
    private val rate = RateMeter(windowMs = 2_000, bucketMs = 250)
    private var startedAtMs = 0L

    suspend fun run(): PullOutcome {
        publish(PullState.BEGINNING)
        val rsp = try {
            port.transferBegin(
                TransferBeginReq(
                    fileId = fileId,
                    startOffset = startOffset,
                    crcMode = requestCrcMode,
                    ackEveryN = requestAckEveryN,
                    objectToken = objectToken,
                    objectSize = objectSize,
                ),
            )
        } catch (e: UwtpCommandException) {
            val code = (e.failure as? UwtpFailure.DeviceError)?.code
            // OBJECT_CHANGED = 对象已变, 断点作废须从 0 重来(D-10); 其余失败断点仍有效
            return fail("BEGIN: ${e.message}", code, resumable = code != Uwtp.ERR_OBJECT_CHANGED)
        }
        if (rsp.acceptedOffset > startOffset) {
            // 设备不得跳过 App 没有的数据; 出现即协议错误, 中止防拼出带洞文件
            runCatching { port.transferAbort(rsp.transferId) }
            return fail("accepted_offset ${rsp.acceptedOffset} > start_offset $startOffset", null, resumable = true)
        }
        transferId = rsp.transferId
        totalSize = rsp.totalSize
        crcMode = rsp.acceptedCrcMode
        ackEveryN = if (rsp.acceptedAckEveryN > 0) rsp.acceptedAckEveryN else requestAckEveryN
        if (rsp.acceptedOffset < startOffset) {
            sink.truncate(rsp.acceptedOffset)
            contiguous = rsp.acceptedOffset
        }
        if (contiguous > totalSize) {
            // 断点越过对象尾: token=0 未校验时对象被换过的兜底(有 token 时应收 OBJECT_CHANGED)
            runCatching { port.transferAbort(transferId) }
            return fail("断点 $contiguous 超出 total_size $totalSize", null, resumable = false)
        }
        onBegin(rsp)
        startedAtMs = port.nowMs()
        publish(PullState.RECEIVING)

        while (contiguous < totalSize) {
            if (abortRequested) return doAbort()
            val msg = withTimeoutOrNull(idleTimeoutMs) { inbound.receive() }
                ?: run {
                    runCatching { port.transferAbort(transferId) }
                    return fail("DATA 空闲超时 ${idleTimeoutMs}ms", null, resumable = true)
                }
            when (msg) {
                AbortMarker -> return doAbort()
                TransferInbound.LinkDown -> return fail("链路断开", null, resumable = true)
                is TransferInbound.ErrorEvent -> handleEvent(msg)?.let { return it }
                is TransferInbound.Data -> handleData(msg)
            }
        }

        // 收口: 最终累计 ACK(幂等) + FINISH(§12.4: App 始终是正常结束的发起者)
        if (totalSize > 0 && lastAcked != contiguous) sendAckNow()
        publish(PullState.FINISHING)
        try {
            port.transferFinish(transferId)
        } catch (e: UwtpCommandException) {
            // 数据已收齐, FINISH 失败不丢断点(重连后可重新 BEGIN 到尾部再收口)
            return fail("FINISH: ${e.message}", (e.failure as? UwtpFailure.DeviceError)?.code, resumable = true)
        }
        sink.flush()
        val elapsed = (port.nowMs() - startedAtMs).coerceAtLeast(1)
        publish(PullState.DONE)
        return PullOutcome.Done(
            totalSize = totalSize,
            sessionBytes = sessionBytes,
            elapsedMs = elapsed,
            avgKibps = sessionBytes / 1024.0 / (elapsed / 1000.0),
        )
    }

    private suspend fun handleData(msg: TransferInbound.Data) {
        val f = TransferDataFrame.parse(msg.payload, crcMode)
        if (f == null || f.data.isEmpty()) {
            malformed++
            publish(PullState.RECEIVING)
            return
        }
        if (f.transferId != transferId) {
            staleFrames++ // 旧事务世代帧: 丢弃计数(§12.4)
            publish(PullState.RECEIVING)
            return
        }
        if (crcMode == 1 && !f.crcOk) {
            // 坏帧丢弃 -> 累计 ACK 停在最后连续位置 -> 固件窗口重发(§12.4)
            crcErrors++
            publish(PullState.RECEIVING)
            return
        }
        if (f.offset + f.data.size > totalSize) {
            // 越过对象尾的帧 = 协议错误, 丢弃防把文件写超
            malformed++
            publish(PullState.RECEIVING)
            return
        }
        frames++
        framesSinceAck++
        val end = f.offset + f.data.size
        if (end <= contiguous) {
            dupFrames++ // 整帧已有: 幂等丢弃(仍计入窗口帧数)
        } else {
            sink.writeAt(f.offset, f.data)
            val before = contiguous + ranges.pendingBytes()
            ranges.add(f.offset, end)
            contiguous = ranges.drainContiguous(contiguous)
            val newBytes = (contiguous + ranges.pendingBytes()) - before
            if (newBytes > 0) {
                sessionBytes += newBytes
                rate.add(port.nowMs(), newBytes)
            }
        }
        if (framesSinceAck >= ackEveryN || contiguous >= totalSize) sendAckNow()
        publish(PullState.RECEIVING)
    }

    /** 处理 ERROR_EVENT; 返回非 null 即终局。 */
    private fun handleEvent(msg: TransferInbound.ErrorEvent): PullOutcome? {
        val ev = try {
            TransferErrorEvent.decode(msg.payload)
        } catch (e: PbDecodeException) {
            malformed++
            return null
        }
        if (ev.transferId != transferId) {
            staleEvents++ // 旧事务事件静默丢弃并计数(§12.4)
            publish(PullState.RECEIVING)
            return null
        }
        // 设备已自行五步终止(§12.4), App 不再 ABORT; 断点回退到设备确认的 last_valid_offset
        contiguous = minOf(contiguous, ev.lastValidOffset)
        return fail("设备 ERROR_EVENT: ${Uwtp.errorName(ev.errorCode)} last_valid=${ev.lastValidOffset}", ev.errorCode, resumable = true)
    }

    private suspend fun sendAckNow() {
        framesSinceAck = 0
        lastAcked = contiguous
        port.sendTransferAck(transferId, contiguous)
        onAckSent(contiguous)
    }

    private suspend fun doAbort(): PullOutcome {
        runCatching { port.transferAbort(transferId) }
        runCatching {
            sink.truncate(contiguous)
            sink.flush()
        }
        publish(PullState.ABORTED)
        return PullOutcome.Aborted(contiguous)
    }

    private fun fail(reason: String, deviceError: Int?, resumable: Boolean): PullOutcome {
        // 裁掉连续位之后的窗口内空洞数据, 保证 .part 文件长度 == 断点
        runCatching {
            sink.truncate(contiguous)
            sink.flush()
        }
        publish(PullState.FAILED)
        return PullOutcome.Failed(reason, deviceError, contiguous, resumable)
    }

    private fun publish(state: PullState) {
        val started = startedAtMs > 0
        val now = if (started) port.nowMs() else 0
        val elapsed = if (started) (now - startedAtMs).coerceAtLeast(1) else 1
        _progress.value = PullProgress(
            state = state,
            fileId = fileId,
            transferId = transferId,
            totalSize = totalSize,
            contiguous = contiguous,
            sessionBytes = sessionBytes,
            instantKibps = if (started) rate.instantBytesPerSec(now) / 1024.0 else 0.0,
            avgKibps = if (started) sessionBytes / 1024.0 / (elapsed / 1000.0) else 0.0,
            frames = frames,
            dupFrames = dupFrames,
            staleFrames = staleFrames,
            crcErrors = crcErrors,
            malformedFrames = malformed,
            staleEvents = staleEvents,
        )
    }
}

/** [contiguous, ...) 之外的已收区间集合(升序不相交; 窗口重发补洞用)。 */
internal class OffsetRanges {
    private val starts = ArrayList<Long>()
    private val ends = ArrayList<Long>()

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        var i = 0
        // 跳过完全在左侧(不相邻)的区间
        while (i < starts.size && ends[i] < s) i++
        // 吸收所有重叠/相邻区间
        while (i < starts.size && starts[i] <= e) {
            s = minOf(s, starts[i])
            e = maxOf(e, ends[i])
            starts.removeAt(i)
            ends.removeAt(i)
        }
        starts.add(i, s)
        ends.add(i, e)
    }

    /** 从 [from] 起吃掉可衔接的区间, 返回新的连续位。 */
    fun drainContiguous(from: Long): Long {
        var c = from
        while (starts.isNotEmpty() && starts[0] <= c) {
            c = maxOf(c, ends[0])
            starts.removeAt(0)
            ends.removeAt(0)
        }
        return c
    }

    /** 连续位之后暂存(有洞)区间的总字节。 */
    fun pendingBytes(): Long {
        var sum = 0L
        for (i in starts.indices) sum += ends[i] - starts[i]
        return sum
    }
}

/** 近窗滑动速率(bucketMs 粒度环形桶)。 */
internal class RateMeter(private val windowMs: Long, private val bucketMs: Long) {
    private val n = (windowMs / bucketMs).toInt()
    private val bucketBytes = LongArray(n)
    private val bucketStamp = LongArray(n)

    fun add(nowMs: Long, bytes: Long) {
        val slot = ((nowMs / bucketMs) % n).toInt()
        val stamp = nowMs / bucketMs
        if (bucketStamp[slot] != stamp) {
            bucketStamp[slot] = stamp
            bucketBytes[slot] = 0
        }
        bucketBytes[slot] += bytes
    }

    fun instantBytesPerSec(nowMs: Long): Double {
        val cur = nowMs / bucketMs
        var sum = 0L
        for (i in 0 until n) {
            val stamp = bucketStamp[i]
            if (stamp > 0 && cur - stamp < n) sum += bucketBytes[i]
        }
        return sum * 1000.0 / windowMs
    }
}
