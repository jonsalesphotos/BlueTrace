package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * S7 采集固件 OTA 推送会话（独占长事务；每已连接设备一个实例）。
 *
 * 设计见 `Docs/OTA/S7采集OTA_设计.md`。仿 [S7Console.pullLog] 的独占事务范式（自建 ack 通道 + 空闲超时），
 * **不复用** 3s 单飞 `request()`——TRANS 数据片只写不回、每切片才回 9B ack、REQ 授权异步。
 *
 * 时序：REQ（会话总量，等设备 MMI 授权异步回 12B）→ 逐文件 START/TRANS×N/STOP → 末文件 STOP 即整包完成。
 * 流控：每切片发完（≤17 无响应写）等 9B ack、核对 U32 累加和再发下一切片（**切片内**的逐包背压 = Phase 2 真机项）。
 * 纪律：切片 ack 超时须 < 设备 ~15.36s UI 看门狗（[sliceAckTimeoutMs] 默认 10s 留余量）。
 */
class S7OtaSession(
    private val ble: BleClient,
    val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    /** REQ → 12B 应答等待上限（含设备端人工授权，异步；非固定协议超时）。 */
    private val authorizeTimeoutMs: Long = 60_000,
    /** 单切片 9B ack 超时（须 < 设备 ~15.36s 看门狗）。 */
    private val sliceAckTimeoutMs: Long = 10_000,
    /** START/STOP 命令 ack 超时。 */
    private val cmdAckTimeoutMs: Long = 5_000,
    /** 单切片校验/NAK 失败重传上限。 */
    private val maxSliceRetries: Int = 3,
) {
    private val mutex = Mutex()
    private val _opLog = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<String> = _opLog

    /**
     * 推送整包。挂起直到 [OtaResult]。独占：同一实例并发调用被 [mutex] 串行化。
     * 成功 = [OtaResult.DoneDownload]（末文件 STOP 收讫；生效由设备自复位完成，调用方随后重连读版本确认）。
     */
    suspend fun provision(pkg: OtaPackage, onProgress: (OtaProgress) -> Unit = {}): OtaResult = mutex.withLock {
        if (ble.linkState(deviceId).value != LinkState.CONNECTED) return@withLock fail(OtaFailure.NotConnected)

        val decoder = S7FrameDecoder()
        val acks = Channel<S7Message>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val collector = scope.launch {
            ble.notifications(deviceId).collect { n ->
                for (m in decoder.feed(n.rawBytes)) {
                    if (m.cmd == S7.CMD_FILE_TRANS) acks.trySend(m)
                }
            }
        }

        suspend fun awaitAck(key: Int, timeoutMs: Long): S7Message? = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val m = acks.receive()
                if (m.key == key) return@withTimeoutOrNull m
                log("drop stale ft ack key=0x${m.key.toString(16)}")
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        try {
            val mtu = ble.negotiatedMtu(deviceId)

            // ---- REQ：报会话总量，等（异步授权）12B ----
            log("TX REQ module=${pkg.moduleId} files=${pkg.fileCount} total=${pkg.totalBytes}")
            ble.write(deviceId, S7FileTrans.encodeReq(pkg.moduleId, pkg.fileCount, pkg.totalBytes))
            val reqMsg = awaitAck(S7FileTrans.KEY_REQ, authorizeTimeoutMs)
                ?: return@withLock fail(OtaFailure.Timeout("REQ"))
            val req = S7FileTrans.parseReqReply(reqMsg.param)
                ?: return@withLock fail(OtaFailure.Malformed("REQ"))
            if (req.status != S7FileTrans.REQ_OK) return@withLock fail(OtaFailure.ReqRejected(req.status))
            // 切片长以设备回值为准, 但夹到本地 MTU 的分帧容量 (MTU−12)×17 之内——
            // 否则设备回的 sliceMaxSize 基准 MTU 大于本地(如本地低报 MTU 回退 23)时,
            // 一切片按本地 MTU 分帧会超过固件 17 包/切片硬限, 触发丢包/拒收(审查 medium)。
            val localCap = S7FileTrans.defaultSliceMaxSize(mtu)
            val sliceMax = (if (req.sliceMaxSize > 0) req.sliceMaxSize else localCap).coerceAtMost(localCap).coerceAtLeast(1)
            log("RX REQ ok sliceMax=$sliceMax (dev=${req.sliceMaxSize} localCap=$localCap) offset=${req.offset}")

            // ---- 逐文件 START/TRANS×N/STOP ----
            var sentTotal = 0L
            for ((idx, file) in pkg.files.withIndex()) {
                val sliceSize = sliceMax.coerceAtMost(if (file.bytes.isEmpty()) 1 else file.bytes.size)
                log("TX START [$idx] ${file.name} size=${file.bytes.size} type=${file.fileType}")
                ble.write(deviceId, S7FileTrans.encodeStart(file.name, file.bytes.size.toLong(), sliceSize, file.fileType))
                val startAck = awaitAck(S7FileTrans.KEY_START, cmdAckTimeoutMs)
                    ?: return@withLock fail(OtaFailure.Timeout("START:${file.name}"))
                if (!S7.commAckOk(startAck.param)) {
                    return@withLock fail(OtaFailure.DeviceError("START:${file.name}", ackCode(startAck.param)))
                }

                var off = 0
                while (off < file.bytes.size) {
                    val len = sliceMax.coerceAtMost(file.bytes.size - off)
                    val slice = file.bytes.copyOfRange(off, off + len)
                    val expectSum = S7FileTrans.additiveChecksum(slice)
                    val ok = sendSliceWithRetry(slice, expectSum, mtu, acks) { key, t -> awaitAck(key, t) }
                    if (!ok) return@withLock fail(OtaFailure.SliceFailed(file.name, off.toLong()))
                    off += len
                    sentTotal += len
                    onProgress(OtaProgress(idx, pkg.fileCount, file.name, sentTotal, pkg.totalBytes))
                }

                log("TX STOP [$idx] ${file.name}")
                ble.write(deviceId, S7FileTrans.encodeStop())
                val stopAck = awaitAck(S7FileTrans.KEY_STOP, cmdAckTimeoutMs)
                    ?: return@withLock fail(OtaFailure.Timeout("STOP:${file.name}"))
                if (!S7.commAckOk(stopAck.param)) {
                    return@withLock fail(OtaFailure.DeviceError("STOP:${file.name}", ackCode(stopAck.param)))
                }
            }
            log("DONE download ${pkg.fileCount} files ${pkg.totalBytes} bytes → 设备自复位生效")
            OtaResult.DoneDownload
        } finally {
            collector.cancel()
            acks.close()
        }
    }

    /** 发一切片（≤17 帧背靠背无响应写）→ 等 9B ack → 核对；失败重传至 [maxSliceRetries]。 */
    private suspend fun sendSliceWithRetry(
        slice: ByteArray,
        expectSum: Long,
        mtu: Int,
        acks: Channel<S7Message>,
        await: suspend (Int, Long) -> S7Message?,
    ): Boolean {
        repeat(maxSliceRetries) { attempt ->
            // 每次发送前清残留 ack（含首发）：TRANS ack 无 seq/offset、全同 key，相邻等长等和切片
            // 的陈旧 ack 会被误配为当前切片成功。首发前清掉上一切片重传遗留的 ack 是主要防线。
            // 残余的"真·迟到 ack 落在本切片 await 窗口内"竞态需真机 ack 语义(recvLen 是否累计)才能根治
            // →Phase 2 硬化(见 implementation-notes)。Mock 恒即时回, 此路径不发生。
            while (acks.tryReceive().isSuccess) { /* drain stale */ }
            for (frame in S7FileTrans.encodeSlice(slice, mtu)) ble.write(deviceId, frame)
            val ackMsg = await(S7FileTrans.KEY_TRANS, sliceAckTimeoutMs)
            if (ackMsg == null) {
                log("slice ack TIMEOUT (attempt ${attempt + 1})")
                return@repeat
            }
            val ack = S7FileTrans.parseDataAck(ackMsg.param)
            if (ack != null && ack.ok && ack.recvLen == slice.size.toLong() && ack.checkSum == expectSum) return true
            log("slice ack mismatch st=${ack?.status} recv=${ack?.recvLen}/${slice.size} sum=${ack?.checkSum}/$expectSum (attempt ${attempt + 1})")
        }
        return false
    }

    private fun ackCode(param: ByteArray): Int = if (param.isNotEmpty()) param[0].toInt() and 0xFF else 0x01

    private fun fail(reason: OtaFailure): OtaResult {
        log("FAILED $reason")
        return OtaResult.Failed(reason)
    }

    private fun log(text: String) {
        _opLog.tryEmit(text)
    }
}
