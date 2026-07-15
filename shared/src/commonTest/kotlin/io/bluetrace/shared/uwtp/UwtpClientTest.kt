package io.bluetrace.shared.uwtp

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UwtpClient 分流与命令面: seq 配对 / 迟到响应丢弃 / B2A 共管道互不干扰 /
 * TRANSFER DATA-EVENT 路由 / 设备主动命令丢弃计数。
 */
class UwtpClientTest {

    private class FakeBle : BleClient {
        val written = mutableListOf<ByteArray>()
        val notify = MutableSharedFlow<BleNotification>(extraBufferCapacity = 64)
        val link = MutableStateFlow(LinkState.CONNECTED)

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice, spec: io.bluetrace.shared.ble.GattSpec?) {}
        override suspend fun disconnect(deviceId: String) {}
        override fun linkState(deviceId: String): StateFlow<LinkState> = link
        override fun notifications(deviceId: String): Flow<BleNotification> = notify
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
            written.add(bytes)
        }

        suspend fun push(bytes: ByteArray) {
            notify.emit(BleNotification("dev", 0, bytes, "FFE2"))
        }
    }

    private fun client(ble: FakeBle, scope: kotlinx.coroutines.CoroutineScope) =
        UwtpClient(ble, "dev", scope, { 0L }, requestTimeoutMs = 1_000, slowRequestTimeoutMs = 2_000)

    @Test
    fun requestMatchesResponseBySeqAndDropsStale() = runTest {
        val ble = FakeBle()
        val c = client(ble, this)
        c.start()
        val rsp = async { c.stateQuery() }
        // 等命令帧真正写出
        ble.notify.subscriptionCount.first { it > 0 }
        while (ble.written.isEmpty()) kotlinx.coroutines.yield()
        val cmd = ble.written.single()
        assertEquals(0x14, cmd[0].toInt() and 0xFF)
        val seq = cmd[3].toInt() and 0xFF
        // 先来一条错 seq 的迟到响应(应丢弃计数), 再来正确响应
        ble.push(UwtpFrameCodec.encodeResponse(Uwtp.MT_CTRL, Uwtp.OP_CTRL_STATE_QUERY, seq + 1, hexToBytes(UwtpGolden.ctrlStateQueryRspIdle)))
        ble.push(UwtpFrameCodec.encodeResponse(Uwtp.MT_CTRL, Uwtp.OP_CTRL_STATE_QUERY, seq, hexToBytes(UwtpGolden.ctrlStateQueryRspBusy)))
        val state = rsp.await()
        assertEquals(CtrlRuntimeState(offlineState = 1, liveState = 0, transferState = 1), state)
        assertEquals(1, c.stats.staleResponses)
        coroutineContext.cancelChildren()
    }

    @Test
    fun deviceErrorCodeSurfacesAsException() = runTest {
        val ble = FakeBle()
        val c = client(ble, this)
        c.start()
        val rsp = async { runCatching { c.ctrlInfo() } }
        ble.notify.subscriptionCount.first { it > 0 }
        while (ble.written.isEmpty()) kotlinx.coroutines.yield()
        val seq = ble.written.single()[3].toInt() and 0xFF
        ble.push(UwtpFrameCodec.encodeResponse(Uwtp.MT_CTRL, Uwtp.OP_CTRL_INFO, seq, MinimalErrorRsp.encode(Uwtp.ERR_BUSY)))
        val e = assertIs<UwtpCommandException>(rsp.await().exceptionOrNull())
        val failure = assertIs<UwtpFailure.DeviceError>(e.failure)
        assertEquals(Uwtp.ERR_BUSY, failure.code)
        assertEquals("BUSY", failure.name)
        coroutineContext.cancelChildren()
    }

    @Test
    fun b2aFramesIgnoredAndTransferFramesRouted() = runTest {
        val ble = FakeBle()
        val c = client(ble, this)
        c.start()
        ble.notify.subscriptionCount.first { it > 0 }
        val sink = Channel<TransferInbound>(Channel.UNLIMITED)
        c.attachTransfer(sink)
        // B2A 帧(0xBB): 静默忽略
        ble.push(hexToBytes("bb 02 04 00 1a 2b 00 00 01 02 00 00"))
        // UWTP DATA 帧: 路由到传输通道
        val payload = TransferDataFrame.build(1, 0, byteArrayOf(1, 2, 3))
        ble.push(UwtpFrameCodec.encodeData(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_DATA, 0, payload))
        // UWTP ERROR_EVENT: 同样路由
        ble.push(UwtpFrameCodec.encodeData(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_ERROR_EVENT, 0, TransferErrorEvent(1, 1, 0).encode()))
        // 设备主动"命令"(NEED_RSP=1 非响应): 丢弃计数
        ble.push(UwtpFrameCodec.encodeCommand(Uwtp.MT_CTRL, 0x55, 0, ByteArray(0)))
        // 坏帧(SEG 非零)
        ble.push(hexToBytes("11 01 01 00 00"))
        val d = assertIs<TransferInbound.Data>(sink.receive())
        assertEquals(bytesToHex(payload), bytesToHex(d.payload))
        assertIs<TransferInbound.ErrorEvent>(sink.receive())
        // 设备命令帧与坏帧在事件之后入队, 等收集协程消化完再断言
        while (c.stats.deviceCommands == 0 || c.stats.malformed == 0) kotlinx.coroutines.yield()
        assertEquals(3, c.stats.uwtpFrames) // DATA + EVENT + 设备命令(合法帧, 角色不当)
        assertEquals(1, c.stats.deviceCommands)
        assertEquals(1, c.stats.malformed)
        c.detachTransfer(sink)
        // 解除挂接后 DATA 成孤儿帧
        ble.push(UwtpFrameCodec.encodeData(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_DATA, 1, payload))
        while (c.stats.orphanTransfer == 0) kotlinx.coroutines.yield()
        assertEquals(1, c.stats.orphanTransfer)
        coroutineContext.cancelChildren()
    }

    @Test
    fun linkDownFailsPendingAndNotifiesTransfer() = runTest {
        val ble = FakeBle()
        val c = client(ble, this)
        c.start()
        ble.notify.subscriptionCount.first { it > 0 }
        ble.link.subscriptionCount.first { it > 0 } // 确保 linkState 收集器已见到 CONNECTED 初值
        val sink = Channel<TransferInbound>(Channel.UNLIMITED)
        c.attachTransfer(sink)
        val rsp = async { runCatching { c.stateQuery() } }
        while (ble.written.isEmpty()) kotlinx.coroutines.yield()
        ble.link.value = LinkState.DISCONNECTED
        val e = assertIs<UwtpCommandException>(rsp.await().exceptionOrNull())
        assertEquals(UwtpFailure.LinkDown, e.failure)
        assertEquals(TransferInbound.LinkDown, sink.receive())
        coroutineContext.cancelChildren()
    }

    @Test
    fun transferAckGoesOutAsDataFrameWithOwnSeq() = runTest {
        val ble = FakeBle()
        val c = client(ble, this)
        c.start()
        val sink = Channel<TransferInbound>(Channel.UNLIMITED)
        c.attachTransfer(sink) // attach 清零 ACK seq(新事务)
        c.sendTransferAck(1, 65_536)
        c.sendTransferAck(1, 131_072)
        assertEquals(2, ble.written.size)
        val f1 = assertIs<UwtpFrameDecode.Ok>(UwtpFrameCodec.decode(ble.written[0])).frame
        val f2 = assertIs<UwtpFrameDecode.Ok>(UwtpFrameCodec.decode(ble.written[1])).frame
        assertEquals(Uwtp.MT_TRANSFER, f1.mainType)
        assertEquals(Uwtp.OP_TRANSFER_ACK, f1.opcode)
        assertTrue(!f1.needRsp && !f1.isResponse)
        assertEquals(0, f1.seq)
        assertEquals(1, f2.seq) // 按 (main_type, opcode) 独立递增
        assertEquals(TransferAck(1, 65_536), TransferAck.decode(f1.payload))
        coroutineContext.cancelChildren()
    }
}
