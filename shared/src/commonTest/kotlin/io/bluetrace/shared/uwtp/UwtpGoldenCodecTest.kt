package io.bluetrace.shared.uwtp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 18 个 DRAFT golden 样本逐字节对拍(shared/proto/uwtp_v02/golden):
 * - App 发送方向(请求/ACK): 本端编码 == golden;
 * - App 接收方向(响应/事件): golden 解码字段断言 + 本端重编码 == golden
 *   (编码器按字段号升序 + 默认值省略, 与参考生成器 protoc 同规范, §6.8 编码快照回归)。
 */
class UwtpGoldenCodecTest {

    private fun assertHex(expectedHex: String, actual: ByteArray) =
        assertEquals(expectedHex, bytesToHex(actual))

    // ---- CTRL ----

    @Test
    fun ctrlStateQueryReq() {
        assertHex(UwtpGolden.ctrlStateQueryReq, CtrlStateQueryReq(queryFlags = 0x01).encode())
    }

    @Test
    fun ctrlStateQueryRspIdle() {
        val rsp = CtrlStateQueryRsp.decode(hexToBytes(UwtpGolden.ctrlStateQueryRspIdle))
        assertEquals(0, rsp.errorCode)
        val rt = assertNotNull(rsp.runtime, "成功响应必须携带 runtime(空子消息也在场)")
        assertEquals(CtrlRuntimeState(0, 0, 0), rt)
        assertHex(UwtpGolden.ctrlStateQueryRspIdle, rsp.encode())
    }

    @Test
    fun ctrlStateQueryRspBusy() {
        val rsp = CtrlStateQueryRsp.decode(hexToBytes(UwtpGolden.ctrlStateQueryRspBusy))
        assertEquals(0, rsp.errorCode)
        assertEquals(CtrlRuntimeState(offlineState = 1, liveState = 0, transferState = 1), rsp.runtime)
        assertHex(UwtpGolden.ctrlStateQueryRspBusy, rsp.encode())
    }

    @Test
    fun ctrlInfoReqIsEmpty() {
        assertContentEquals(ByteArray(0), hexToBytes(UwtpGolden.ctrlInfoReq))
        // INFO 空请求: 帧 len=0, 无 payload 编码物
    }

    @Test
    fun ctrlInfoRsp() {
        val rsp = CtrlInfoRsp.decode(hexToBytes(UwtpGolden.ctrlInfoRsp))
        assertEquals(0, rsp.errorCode)
        assertEquals(1L, rsp.fwVersion)
        assertEquals(1, rsp.registryRev)
        assertEquals(1, rsp.rawSchemas.size)
        assertEquals(
            RawSchemaRef(schemaId = 0x1001, schemaRev = 1, sourceProfile = 0x0101, channelMask = 1L shl 8),
            rsp.rawSchemas[0],
        )
        assertHex(UwtpGolden.ctrlInfoRsp, rsp.encode())
    }

    @Test
    fun minimalErrorNotSupported() {
        val bytes = hexToBytes(UwtpGolden.minimalErrorNotSupported)
        assertEquals(Uwtp.ERR_NOT_SUPPORTED, MinimalErrorRsp.readErrorCode(bytes))
        assertHex(UwtpGolden.minimalErrorNotSupported, MinimalErrorRsp.encode(Uwtp.ERR_NOT_SUPPORTED))
    }

    // ---- LIVE ----

    @Test
    fun liveStartReq() {
        assertHex(UwtpGolden.liveStartReq, LiveStartReq(rawChannelMask = 1L shl 8, resultMask = 0).encode())
    }

    @Test
    fun liveStartRsp() {
        val rsp = LiveStartRsp.decode(hexToBytes(UwtpGolden.liveStartRsp))
        assertEquals(0, rsp.errorCode)
        assertEquals(1L shl 8, rsp.effectiveChannelMask)
        assertEquals(0L, rsp.effectiveResultMask)
        assertEquals(0x1001, rsp.schemaId)
        assertEquals(1, rsp.schemaRev)
        assertHex(UwtpGolden.liveStartRsp, rsp.encode())
    }

    @Test
    fun liveStopReqAndRsp() {
        assertContentEquals(ByteArray(0), hexToBytes(UwtpGolden.liveStopReq))
        // STOP 成功响应 = 0 字节 PB, error_code 省略即 0=OK(§6.3)
        assertEquals(Uwtp.ERR_OK, MinimalErrorRsp.readErrorCode(hexToBytes(UwtpGolden.liveStopRspOk)))
    }

    // ---- TRANSFER ----

    @Test
    fun transferBeginReqFirst() {
        val req = TransferBeginReq(fileId = 7, startOffset = 0, crcMode = 0, ackEveryN = 16, objectToken = 0, objectSize = 0)
        assertHex(UwtpGolden.transferBeginReqFirst, req.encode())
    }

    @Test
    fun transferBeginReqResume() {
        val req = TransferBeginReq(
            fileId = 7,
            startOffset = 65_536,
            crcMode = 0,
            ackEveryN = 16,
            objectToken = 0x1122334455667788L,
            objectSize = 100L * 1024 * 1024,
        )
        assertHex(UwtpGolden.transferBeginReqResume, req.encode())
    }

    @Test
    fun transferBeginRsp() {
        val rsp = TransferBeginRsp.decode(hexToBytes(UwtpGolden.transferBeginRsp))
        assertEquals(0, rsp.errorCode)
        assertEquals(1L, rsp.transferId)
        assertEquals(100L * 1024 * 1024, rsp.totalSize)
        assertEquals(0x1122334455667788L, rsp.objectToken)
        assertEquals(0L, rsp.acceptedOffset)
        assertEquals(0, rsp.acceptedCrcMode)
        assertEquals(32, rsp.acceptedAckEveryN)
        assertEquals(231, rsp.maxDataLen)
        assertHex(UwtpGolden.transferBeginRsp, rsp.encode())
    }

    @Test
    fun transferAck() {
        val ack = TransferAck(transferId = 1, nextExpectedOffset = 65_536)
        assertHex(UwtpGolden.transferAck, ack.encode())
        assertEquals(ack, TransferAck.decode(hexToBytes(UwtpGolden.transferAck)))
    }

    @Test
    fun transferErrorEvent() {
        val ev = TransferErrorEvent.decode(hexToBytes(UwtpGolden.transferErrorEvent))
        assertEquals(Uwtp.ERR_IO_ERROR, ev.errorCode)
        assertEquals(1L, ev.transferId)
        assertEquals(12_345L, ev.lastValidOffset)
        assertHex(UwtpGolden.transferErrorEvent, ev.encode())
    }

    @Test
    fun objectListRsp() {
        val rsp = ObjectListRsp.decode(hexToBytes(UwtpGolden.objectListRsp))
        assertEquals(0, rsp.errorCode)
        assertEquals(0L, rsp.nextCursor)
        assertEquals(1, rsp.objects.size)
        assertEquals(
            ObjectEntry(
                fileId = 7,
                size = 100L * 1024 * 1024,
                startUtc = 1_784_000_000L,
                flags = 1,
                objectToken = 0x1122334455667788L,
            ),
            rsp.objects[0],
        )
        assertHex(UwtpGolden.objectListRsp, rsp.encode())
    }

    // ---- TRANSFER_DATA 二进制前缀(非 PB) ----

    @Test
    fun dataPrefixNoCrc() {
        val f = assertNotNull(TransferDataFrame.parse(hexToBytes(UwtpGolden.dataPrefixNocrc), crcMode = 0))
        assertEquals(1L, f.transferId)
        assertEquals(65_536L, f.offset)
        assertEquals(0, f.data.size)
        assertNull(f.frameCrc32)
        assertHex(UwtpGolden.dataPrefixNocrc, TransferDataFrame.build(1, 65_536, ByteArray(0)))
    }

    @Test
    fun dataPrefixCrc() {
        val f = assertNotNull(TransferDataFrame.parse(hexToBytes(UwtpGolden.dataPrefixCrc), crcMode = 1))
        assertEquals(1L, f.transferId)
        assertEquals(65_536L, f.offset)
        assertEquals(0, f.data.size)
        // golden 样本的 CRC 为占位值 0xDEADBEEF(覆盖式样待专项⑤), 只对拍字段与重编码
        assertEquals(0xDEADBEEFL, f.frameCrc32)
        assertHex(UwtpGolden.dataPrefixCrc, TransferDataFrame.buildRaw(1, 65_536, ByteArray(0), 0xDEADBEEFL))
    }
}
