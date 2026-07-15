package io.bluetrace.shared.uwtp

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 传输状态机用例: DATA 组装 / offset 幂等去重 / 旧 transfer_id 丢弃计数 / 空洞重发补齐 /
 * 累计 ACK 节奏 / ERROR_EVENT / ABORT / 断连续传语义 / crc_mode=1 坏帧。
 */
class UwtpFilePullTest {

    private class FakePort(
        var beginRsp: (TransferBeginReq) -> TransferBeginRsp = { req ->
            TransferBeginRsp(
                transferId = 1,
                totalSize = 0,
                objectToken = 0x1122334455667788L,
                acceptedOffset = req.startOffset,
                acceptedCrcMode = req.crcMode,
                acceptedAckEveryN = req.ackEveryN,
                maxDataLen = 231,
            )
        },
    ) : UwtpTransferPort {
        var time = 1_000L
        val beginReqs = mutableListOf<TransferBeginReq>()
        val acks = mutableListOf<Long>()
        var finishCount = 0
        var abortCount = 0
        var beginError: UwtpFailure? = null

        override suspend fun transferBegin(req: TransferBeginReq): TransferBeginRsp {
            beginReqs.add(req)
            beginError?.let { throw UwtpCommandException(it) }
            return beginRsp(req)
        }

        override suspend fun transferFinish(transferId: Long) {
            finishCount++
        }

        override suspend fun transferAbort(transferId: Long) {
            abortCount++
        }

        override suspend fun sendTransferAck(transferId: Long, nextExpectedOffset: Long) {
            acks.add(nextExpectedOffset)
        }

        override fun nowMs(): Long {
            time += 5
            return time
        }
    }

    private class MemSink : PullSink {
        var bytes = ByteArray(0)
        var closed = false

        override fun writeAt(offset: Long, data: ByteArray) {
            val end = (offset + data.size).toInt()
            if (end > bytes.size) bytes = bytes.copyOf(end)
            data.copyInto(bytes, offset.toInt())
        }

        override fun truncate(size: Long) {
            bytes = bytes.copyOf(size.toInt())
        }

        override fun flush() {}

        override fun close() {
            closed = true
        }
    }

    /** 生成可校验的文件内容(第 i 字节 = i*7 & 0xFF)。 */
    private fun pattern(size: Int, base: Int = 0): ByteArray =
        ByteArray(size) { (((base + it) * 7) and 0xFF).toByte() }

    private fun dataFrame(id: Long, offset: Long, data: ByteArray): TransferInbound.Data =
        TransferInbound.Data(TransferDataFrame.build(id, offset, data))

    @Test
    fun happyPathInOrder() = runTest {
        val total = 100
        val chunk = 10
        val file = pattern(total)
        val port = FakePort({ req ->
            TransferBeginRsp(transferId = 1, totalSize = total.toLong(), objectToken = 1L, acceptedOffset = 0, acceptedAckEveryN = 4)
        })
        val sink = MemSink()
        val ackCb = mutableListOf<Long>()
        val pull = UwtpFilePull(port, sink, fileId = 7, requestAckEveryN = 4, onAckSent = { ackCb.add(it) })
        val result = async { pull.run() }
        for (off in 0 until total step chunk) {
            pull.inbound.send(dataFrame(1, off.toLong(), file.copyOfRange(off, off + chunk)))
        }
        val outcome = assertIs<PullOutcome.Done>(result.await())
        assertEquals(total.toLong(), outcome.totalSize)
        assertEquals(total.toLong(), outcome.sessionBytes)
        assertContentEquals(file, sink.bytes)
        assertEquals(1, port.finishCount)
        assertEquals(0, port.abortCount)
        // 10 帧 / 窗口 4: 第 4 帧 ACK(40), 第 8 帧 ACK(80), 第 10 帧到尾 ACK(100)
        assertEquals(listOf(40L, 80L, 100L), port.acks)
        assertEquals(port.acks, ackCb)
        val p = pull.progress.value
        assertEquals(PullState.DONE, p.state)
        assertEquals(10, p.frames)
        assertEquals(0, p.dupFrames)
        assertEquals(100, p.percent)
    }

    @Test
    fun duplicateFramesAreIdempotentAndStillTriggerAck() = runTest {
        val total = 80
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedAckEveryN = 4) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 4)
        val result = async { pull.run() }
        // 首窗 4 帧(0..40)
        for (off in 0 until 40 step 10) pull.inbound.send(dataFrame(1, off.toLong(), file.copyOfRange(off, off + 10)))
        // 假设 App 的 ACK 丢失, 固件整窗重发: 4 帧全重复 -> 仍要再触发一次累计 ACK 打破僵持
        for (off in 0 until 40 step 10) pull.inbound.send(dataFrame(1, off.toLong(), file.copyOfRange(off, off + 10)))
        // 第二窗(40..80)
        for (off in 40 until 80 step 10) pull.inbound.send(dataFrame(1, off.toLong(), file.copyOfRange(off, off + 10)))
        val outcome = assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
        assertEquals(total.toLong(), outcome.sessionBytes) // 重复帧不重复计有效字节
        assertEquals(listOf(40L, 40L, 80L), port.acks) // 纯重发窗口也产生一次相同值的累计 ACK
        assertEquals(4, pull.progress.value.dupFrames)
    }

    @Test
    fun staleTransferIdDroppedAndCounted() = runTest {
        val total = 20
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 2, totalSize = total.toLong(), acceptedAckEveryN = 8) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 8)
        val result = async { pull.run() }
        // 旧世代(id=1)迟到帧: 丢弃计数, 不写盘不计窗口
        pull.inbound.send(dataFrame(1, 0, pattern(10, base = 99)))
        pull.inbound.send(dataFrame(2, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(2, 10, file.copyOfRange(10, 20)))
        assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
        assertEquals(1, pull.progress.value.staleFrames)
        assertEquals(2, pull.progress.value.frames)
    }

    @Test
    fun holeFilledByWindowRetransmit() = runTest {
        val total = 50
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedAckEveryN = 5) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 5)
        val result = async { pull.run() }
        // 帧 2(offset 10..20)丢失: 先到 0,20,30,40
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(1, 20, file.copyOfRange(20, 30)))
        pull.inbound.send(dataFrame(1, 30, file.copyOfRange(30, 40)))
        pull.inbound.send(dataFrame(1, 40, file.copyOfRange(40, 50)))
        // 固件 T_ack 超时, 从最后确认 offset 整窗重发 -> offset0 是重复帧, offset10 补上洞后连续位直达文件尾
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(1, 10, file.copyOfRange(10, 20)))
        val outcome = assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
        assertEquals(total.toLong(), outcome.sessionBytes) // 洞后暂存数据不重复计费
        assertEquals(1, pull.progress.value.dupFrames)
        // 第 5 帧(重复 offset0)凑满窗口 -> ACK 停在洞前(10); 补洞后连续位到尾 -> ACK(50)
        assertEquals(listOf(10L, 50L), port.acks)
    }

    @Test
    fun errorEventStopsSessionAndKeepsResumePoint() = runTest {
        val total = 100
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 3, totalSize = total.toLong(), acceptedAckEveryN = 4) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 4)
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(3, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(3, 10, file.copyOfRange(10, 20)))
        // 旧事务事件: 忽略计数
        pull.inbound.send(TransferInbound.ErrorEvent(TransferErrorEvent(Uwtp.ERR_IO_ERROR, transferId = 2, lastValidOffset = 0).encode()))
        // 当前事务 IO_ERROR, 设备确认有效偏移 10 -> 断点回退到 10
        pull.inbound.send(TransferInbound.ErrorEvent(TransferErrorEvent(Uwtp.ERR_IO_ERROR, transferId = 3, lastValidOffset = 10).encode()))
        val outcome = assertIs<PullOutcome.Failed>(result.await())
        assertEquals(Uwtp.ERR_IO_ERROR, outcome.deviceError)
        assertTrue(outcome.resumable)
        assertEquals(10L, outcome.contiguous)
        assertEquals(10, sink.bytes.size) // 截到断点
        assertEquals(1, pull.progress.value.staleEvents)
        assertEquals(0, port.abortCount) // 设备已自行终止, App 不再 ABORT
    }

    @Test
    fun abortMidTransfer() = runTest {
        val total = 100
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedAckEveryN = 8) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 8)
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        // 等首帧真正被处理后再中止(runTest 单线程调度: 否则 abort 标志会先于数据帧被检查)
        pull.progress.first { it.contiguous == 10L }
        pull.requestAbort()
        val outcome = assertIs<PullOutcome.Aborted>(result.await())
        assertEquals(10L, outcome.contiguous)
        assertEquals(1, port.abortCount)
        assertEquals(0, port.finishCount)
        assertEquals(PullState.ABORTED, pull.progress.value.state)
    }

    @Test
    fun linkDownYieldsResumableFailure() = runTest {
        val total = 100
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedAckEveryN = 4) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 4)
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(TransferInbound.LinkDown)
        val outcome = assertIs<PullOutcome.Failed>(result.await())
        assertTrue(outcome.resumable)
        assertEquals(10L, outcome.contiguous)
        assertEquals(10, sink.bytes.size)
    }

    @Test
    fun resumeFromOffsetSendsAbsoluteAcks() = runTest {
        val total = 60L
        val file = pattern(60)
        val port = FakePort({ req ->
            TransferBeginRsp(transferId = 5, totalSize = total, acceptedOffset = req.startOffset, acceptedAckEveryN = 2)
        })
        val sink = MemSink()
        sink.bytes = file.copyOfRange(0, 40) // 上次已收 40B
        val pull = UwtpFilePull(
            port, sink, fileId = 9, startOffset = 40, objectToken = 0xABCDL, objectSize = total,
            requestAckEveryN = 2,
        )
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(5, 40, file.copyOfRange(40, 50)))
        pull.inbound.send(dataFrame(5, 50, file.copyOfRange(50, 60)))
        val outcome = assertIs<PullOutcome.Done>(result.await())
        assertEquals(20L, outcome.sessionBytes) // 只计本次会话新字节
        assertContentEquals(file, sink.bytes)
        assertEquals(listOf(60L), port.acks) // 绝对偏移
        val req = port.beginReqs.single()
        assertEquals(40L, req.startOffset)
        assertEquals(0xABCDL, req.objectToken)
        assertEquals(total, req.objectSize)
    }

    @Test
    fun beginObjectChangedIsNotResumable() = runTest {
        val port = FakePort()
        port.beginError = UwtpFailure.DeviceError(Uwtp.ERR_OBJECT_CHANGED)
        val pull = UwtpFilePull(port, MemSink(), fileId = 9, startOffset = 40, objectToken = 1L)
        val outcome = assertIs<PullOutcome.Failed>(pull.run())
        assertEquals(Uwtp.ERR_OBJECT_CHANGED, outcome.deviceError)
        assertEquals(false, outcome.resumable) // 断点作废, App 清断点从 0 重来
    }

    @Test
    fun beginBusyKeepsResumePoint() = runTest {
        val port = FakePort()
        port.beginError = UwtpFailure.DeviceError(Uwtp.ERR_BUSY)
        val pull = UwtpFilePull(port, MemSink(), fileId = 9, startOffset = 40, objectToken = 1L)
        val outcome = assertIs<PullOutcome.Failed>(pull.run())
        assertEquals(Uwtp.ERR_BUSY, outcome.deviceError)
        assertTrue(outcome.resumable)
    }

    @Test
    fun crcModeOneDropsBadFrame() = runTest {
        val total = 30
        val file = pattern(total)
        val port = FakePort({ req ->
            TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedCrcMode = 1, acceptedAckEveryN = 3)
        })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestCrcMode = 1, requestAckEveryN = 3)
        val result = async { pull.run() }
        fun crcFrame(off: Int, ok: Boolean): TransferInbound.Data {
            val data = file.copyOfRange(off, off + 10)
            val crc = TransferDataFrame.computeCrc(1, off.toLong(), data)
            return TransferInbound.Data(TransferDataFrame.buildRaw(1, off.toLong(), data, if (ok) crc else crc xor 0xFF))
        }
        pull.inbound.send(crcFrame(0, ok = true))
        pull.inbound.send(crcFrame(10, ok = false)) // 坏帧丢弃 -> 连续位停在 10
        pull.inbound.send(crcFrame(20, ok = true))
        // 重发补 10..20
        pull.inbound.send(crcFrame(10, ok = true))
        pull.inbound.send(crcFrame(20, ok = true))
        assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
        assertEquals(1, pull.progress.value.crcErrors)
    }

    @Test
    fun idleTimeoutAbortsAndFailsResumable() = runTest {
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = 100, acceptedAckEveryN = 4) })
        val pull = UwtpFilePull(port, MemSink(), fileId = 1, idleTimeoutMs = 2_000)
        val outcome = assertIs<PullOutcome.Failed>(pull.run()) // 无任何 DATA -> 虚拟时钟推进触发空闲超时
        assertTrue(outcome.resumable)
        assertEquals(1, port.abortCount)
    }

    @Test
    fun acceptedOffsetBehindTruncatesAndRestarts() = runTest {
        val total = 20
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedOffset = 0, acceptedAckEveryN = 2) })
        val sink = MemSink()
        sink.bytes = ByteArray(10) { 0x55 } // 旧断点数据将被设备回退覆盖
        val pull = UwtpFilePull(port, sink, fileId = 1, startOffset = 10, requestAckEveryN = 2)
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(1, 10, file.copyOfRange(10, 20)))
        assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
    }

    @Test
    fun frameBeyondTotalSizeDropped() = runTest {
        val total = 20
        val file = pattern(total)
        val port = FakePort({ TransferBeginRsp(transferId = 1, totalSize = total.toLong(), acceptedAckEveryN = 4) })
        val sink = MemSink()
        val pull = UwtpFilePull(port, sink, fileId = 1, requestAckEveryN = 4)
        val result = async { pull.run() }
        pull.inbound.send(dataFrame(1, 0, file.copyOfRange(0, 10)))
        pull.inbound.send(dataFrame(1, 15, pattern(10, base = 50))) // 越过对象尾 -> 丢弃
        pull.inbound.send(dataFrame(1, 10, file.copyOfRange(10, 20)))
        assertIs<PullOutcome.Done>(result.await())
        assertContentEquals(file, sink.bytes)
        assertEquals(1, pull.progress.value.malformedFrames)
    }

    @Test
    fun resumeBeyondTotalFailsNotResumable() = runTest {
        val port = FakePort({ req ->
            TransferBeginRsp(transferId = 1, totalSize = 30, acceptedOffset = req.startOffset)
        })
        val pull = UwtpFilePull(port, MemSink(), fileId = 1, startOffset = 100)
        val outcome = assertIs<PullOutcome.Failed>(pull.run())
        assertEquals(false, outcome.resumable)
        assertEquals(1, port.abortCount)
    }

    @Test
    fun offsetRangesMergeAndDrain() {
        val r = OffsetRanges()
        r.add(20, 30)
        r.add(40, 50)
        assertEquals(20L, r.pendingBytes())
        assertEquals(10L, r.drainContiguous(10)) // 不衔接
        r.add(10, 20) // 与 [20,30) 相邻合并
        assertEquals(30L, r.drainContiguous(10))
        assertEquals(10L, r.pendingBytes()) // 剩 [40,50)
        r.add(30, 40)
        assertEquals(50L, r.drainContiguous(30))
        assertEquals(0L, r.pendingBytes())
    }
}
