package io.bluetrace.shared.b2a

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
 * S7 采集固件 OTA 推送会话(独占长事务; 每已连接设备一个实例).
 *
 * 设计见 `Docs/OTA/S7采集OTA_设计.md`. 仿 [B2aConsole.pullLog] 的独占事务范式(自建 ack 通道 + 空闲超时),
 * **不复用** 3s 单飞 `request()`——TRANS 数据片只写不回, 每切片才回 9B ack, REQ 授权异步.
 *
 * 时序: REQ(会话总量, 等设备 MMI 授权异步回 12B)→ 逐文件 START/TRANS×N/STOP → 末文件 STOP 即整包完成.
 * 流控: 每切片发完(≤17 无响应写)等 9B ack, 核对 U32 累加和再发下一切片(**切片内**的逐包背压 = Phase 2 真机项).
 * 纪律: 切片 ack 超时须 < 设备 ~15.36s UI 看门狗([sliceAckTimeoutMs] 默认 10s 留余量).
 *
 * ⚠️ **中途取消绝不可补发 STOP**(固件审查 2026-07-14, `Docs/OTA/OTA中止_固件行为审查.md`):
 * 固件 `B2A_FileTransStopAck` 会删除 61s 收包看门狗定时器且**不清 OTA 标志**——设备既不自复位,
 * 其他命令又被 OTA 门控丢弃, 永久卡传输态. 协程取消天然停在切片循环(不发 STOP)即正确行为:
 * 停发切片 → 固件看门狗 ~61s 超时 → 设备自行 SYS_Reset(复位前主动断链).
 */
class B2aOtaSession(
    private val ble: BleClient,
    val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    /** REQ → 12B 应答等待上限(含设备端人工授权, 异步; 非固定协议超时).  */
    private val authorizeTimeoutMs: Long = 60_000,
    /** 单切片 9B ack 超时(须 < 设备 ~15.36s 看门狗).  */
    private val sliceAckTimeoutMs: Long = 10_000,
    /** START/STOP 命令 ack 超时.  */
    private val cmdAckTimeoutMs: Long = 5_000,
    /** 单切片校验/NAK 失败重传上限.  */
    private val maxSliceRetries: Int = 3,
) {
    private val mutex = Mutex()
    private val _opLog = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<String> = _opLog

    /**
     * 推送整包. 挂起直到 [OtaResult]. 独占: 同一实例并发调用被 [mutex] 串行化.
     * 成功 = [OtaResult.DoneDownload](末文件 STOP 收讫; 生效由设备自复位完成, 调用方随后重连读版本确认).
     */
    suspend fun provision(pkg: OtaPackage, onProgress: (OtaProgress) -> Unit = {}): OtaResult = mutex.withLock {
        if (ble.linkState(deviceId).value != LinkState.CONNECTED) return@withLock fail(OtaFailure.NotConnected)

        val decoder = B2aFrameDecoder()
        val acks = Channel<B2aMessage>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val collector = scope.launch {
            ble.notifications(deviceId).collect { n ->
                for (m in decoder.feed(n.rawBytes)) {
                    if (m.cmd == B2a.CMD_FILE_TRANS) acks.trySend(m)
                }
            }
        }

        suspend fun awaitAck(key: Int, timeoutMs: Long): B2aMessage? = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val m = acks.receive()
                if (m.key == key) return@withTimeoutOrNull m
                log("drop stale ft ack key=0x${m.key.toString(16)}")
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        try {
            val mtu = ble.negotiatedMtu(deviceId)
            val startedMs = clock.nowMs()
            var sentTotal = 0L
            // 上传速度: ≥1s 滑动窗口(速度显示 + 逐切片日志用)
            var winStartMs = startedMs
            var winStartBytes = 0L
            var speedBps = 0L
            fun pctNow(): Int = if (pkg.totalBytes > 0) (sentTotal * 100 / pkg.totalBytes).toInt() else 0
            fun failWithProgress(reason: OtaFailure): OtaResult {
                log("FAILED $reason @ 已传 $sentTotal/${pkg.totalBytes} (${pctNow()}%)")
                return OtaResult.Failed(reason)
            }

            // ---- REQ: 报会话总量, 等(异步授权)12B ----
            log("TX REQ module=${pkg.moduleId} files=${pkg.fileCount} total=${pkg.totalBytes}")
            ble.write(deviceId, B2aFileTrans.encodeReq(pkg.moduleId, pkg.fileCount, pkg.totalBytes))
            val reqMsg = awaitAck(B2aFileTrans.KEY_REQ, authorizeTimeoutMs)
                ?: return@withLock failWithProgress(OtaFailure.Timeout("REQ"))
            // 真机 REQ 应答字节格式待抓包坐实(BUG-2): 可能为 8B 回显而非 12B(含 sliceMaxSize/offset).
            // - 可解析为 12B 且**自洽**(moduleId 回显==所发) → 采信 status 判拒 + 采信设备 sliceMax(仍夹本地上限);
            // - 短应答(parse=null) 或 12B 不自洽(如恰是请求回显, byte[1]=isOffset≠moduleId)
            //   → 无法可靠判 status, attended 下按"已授权"继续, sliceMax 用本地算值;
            //   真·拒绝(如 disk_full)会在随后 START ack 暴露 → 优雅降级为后段失败, 不静默挂起, 也不误 abort.
            // moduleId 自洽门(S1 硬化): 12B 分支不再凭单个未坐实字节硬 abort, 与短应答分支同等保守.
            val req = B2aFileTrans.parseReqReply(reqMsg.param)
            if (req != null && req.moduleId == pkg.moduleId && req.status != B2aFileTrans.REQ_OK) {
                return@withLock failWithProgress(OtaFailure.ReqRejected(req.status))
            }
            // 切片长: 设备回值(若有)夹到本地分帧容量 (MTU−15)×17 之内.
            // 夹取双重意义: (1)设备回值基准 MTU 大于本地(如本地低报回退 23)时防超固件 17 包/切片硬限;
            // (2)设备不回 sliceMax(短应答)时 localCap 作权威——与官方 App 自算口径一致(golden 日志).
            val localCap = B2aFileTrans.defaultSliceMaxSize(mtu)
            val deviceSlice = req?.sliceMaxSize ?: 0
            val sliceMax = (if (deviceSlice > 0) deviceSlice else localCap).coerceAtMost(localCap).coerceAtLeast(1)
            log("RX REQ ok sliceMax=$sliceMax (dev=${req?.sliceMaxSize ?: "n/a"} localCap=$localCap reqLen=${reqMsg.param.size} offset=${req?.offset ?: 0})")

            // ---- 逐文件 START/TRANS×N/STOP ----
            for ((idx, file) in pkg.files.withIndex()) {
                val sliceSize = sliceMax.coerceAtMost(if (file.bytes.isEmpty()) 1 else file.bytes.size)
                log("TX START [$idx] ${file.name} size=${file.bytes.size} type=${file.fileType}")
                ble.write(deviceId, B2aFileTrans.encodeStart(file.name, file.bytes.size.toLong(), sliceSize, file.fileType))
                val startAck = awaitAck(B2aFileTrans.KEY_START, cmdAckTimeoutMs)
                    ?: return@withLock failWithProgress(OtaFailure.Timeout("START:${file.name}"))
                if (!B2a.commAckOk(startAck.param)) {
                    return@withLock failWithProgress(OtaFailure.DeviceError("START:${file.name}", ackCode(startAck.param)))
                }

                var off = 0
                while (off < file.bytes.size) {
                    val len = sliceMax.coerceAtMost(file.bytes.size - off)
                    val slice = file.bytes.copyOfRange(off, off + len)
                    val expectSum = B2aFileTrans.additiveChecksum(slice)
                    val ok = sendSliceWithRetry(slice, expectSum, mtu, acks) { key, t -> awaitAck(key, t) }
                    if (!ok) return@withLock failWithProgress(OtaFailure.SliceFailed(file.name, off.toLong()))
                    off += len
                    sentTotal += len
                    val now = clock.nowMs()
                    if (now - winStartMs >= 1000) { // ≥1s 窗口刷新速度
                        speedBps = (sentTotal - winStartBytes) * 1000 / (now - winStartMs)
                        winStartMs = now
                        winStartBytes = sentTotal
                    }
                    onProgress(OtaProgress(idx, pkg.fileCount, file.name, sentTotal, pkg.totalBytes, speedBps))
                    // 逐切片一行(约定: TRANS 前缀行 UI 终端不刷屏, 只进落盘执行日志)
                    log("TRANS [$idx] ${file.name} $off/${file.bytes.size} · 总 $sentTotal/${pkg.totalBytes} ${pctNow()}% · ${speedBps / 1024} KB/s")
                }

                log("TX STOP [$idx] ${file.name}")
                ble.write(deviceId, B2aFileTrans.encodeStop())
                val stopAck = awaitAck(B2aFileTrans.KEY_STOP, cmdAckTimeoutMs)
                    ?: return@withLock failWithProgress(OtaFailure.Timeout("STOP:${file.name}"))
                if (!B2a.commAckOk(stopAck.param)) {
                    return@withLock failWithProgress(OtaFailure.DeviceError("STOP:${file.name}", ackCode(stopAck.param)))
                }
            }
            val elapsedMs = (clock.nowMs() - startedMs).coerceAtLeast(1)
            val avgBps = pkg.totalBytes * 1000 / elapsedMs
            log("DONE download ${pkg.fileCount} files ${pkg.totalBytes} bytes · 用时 ${elapsedMs / 1000}s · 平均 ${avgBps / 1024} KB/s → 设备自复位生效")
            OtaResult.DoneDownload
        } finally {
            collector.cancel()
            acks.close()
        }
    }

    /** 发一切片(≤17 帧背靠背无响应写)→ 等 9B ack → 核对; 失败重传至 [maxSliceRetries].  */
    private suspend fun sendSliceWithRetry(
        slice: ByteArray,
        expectSum: Long,
        mtu: Int,
        acks: Channel<B2aMessage>,
        await: suspend (Int, Long) -> B2aMessage?,
    ): Boolean {
        repeat(maxSliceRetries) { attempt ->
            // 每次发送前清残留 ack(含首发): TRANS ack 无 seq/offset, 全同 key, 相邻等长等和切片
            // 的陈旧 ack 会被误配为当前切片成功. 首发前清掉上一切片重传遗留的 ack 是主要防线.
            // 残余的"真·迟到 ack 落在本切片 await 窗口内"竞态需真机 ack 语义(recvLen 是否累计)才能根治
            // →Phase 2 硬化(见 implementation-notes). Mock 恒即时回, 此路径不发生.
            while (acks.tryReceive().isSuccess) { /* drain stale */ }
            for (frame in B2aFileTrans.encodeSlice(slice, mtu)) ble.write(deviceId, frame)
            val ackMsg = await(B2aFileTrans.KEY_TRANS, sliceAckTimeoutMs)
            if (ackMsg == null) {
                log("slice ack TIMEOUT (attempt ${attempt + 1})")
                return@repeat
            }
            val ack = B2aFileTrans.parseDataAck(ackMsg.param)
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
