package io.bluetrace.shared.uwtp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 5B Core Header 编解码与 §5 帧接收规则(App 侧适用子集)。 */
class UwtpFrameCodecTest {

    @Test
    fun commandFrameFirstByteIs14() {
        val f = UwtpFrameCodec.encodeCommand(Uwtp.MT_CTRL, Uwtp.OP_CTRL_STATE_QUERY, seq = 5, payload = hexToBytes("08 01"))
        assertEquals("14 01 01 05 02 08 01", bytesToHex(f))
    }

    @Test
    fun dataFrameFirstByteIs10() {
        val f = UwtpFrameCodec.encodeData(Uwtp.MT_TRANSFER, Uwtp.OP_TRANSFER_ACK, seq = 0, payload = hexToBytes("08 01 10 80 80 04"))
        assertEquals("10 11 05 00 06 08 01 10 80 80 04", bytesToHex(f))
    }

    @Test
    fun emptyPayloadCommand() {
        // CTRL INFO 空请求: len=0
        val f = UwtpFrameCodec.encodeCommand(Uwtp.MT_CTRL, Uwtp.OP_CTRL_INFO, seq = 0)
        assertEquals("14 01 02 00 00", bytesToHex(f))
    }

    @Test
    fun roundTripResponse() {
        val payload = hexToBytes("10 01 18 01")
        val bytes = UwtpFrameCodec.encodeResponse(Uwtp.MT_CTRL, Uwtp.OP_CTRL_INFO, seq = 42, payload = payload)
        assertEquals(0x10, bytes[0].toInt() and 0xFF) // 响应帧 NEED_RSP=0
        val ok = assertIs<UwtpFrameDecode.Ok>(UwtpFrameCodec.decode(bytes))
        assertEquals(Uwtp.MT_CTRL, ok.frame.mainType)
        assertEquals(Uwtp.OP_CTRL_INFO, ok.frame.opcode)
        assertTrue(ok.frame.isResponse)
        assertEquals(42, ok.frame.seq)
        assertEquals(bytesToHex(payload), bytesToHex(ok.frame.payload))
    }

    @Test
    fun b2aBytesAreNotUwtp() {
        // 同管道 0xBB 开头 = 既有 B2A 协议: 静默分流, 不计 UWTP 错误(§13.1)
        assertEquals(UwtpFrameDecode.NotUwtp, UwtpFrameCodec.decode(hexToBytes("bb 02 04 00 1a 2b 00 00 01 02 00 00")))
        // 其他 header_ver(如 0x20)同样不进 UWTP(升 header_ver 须连分流一起评审, §3)
        assertEquals(UwtpFrameDecode.NotUwtp, UwtpFrameCodec.decode(hexToBytes("24 01 01 00 00")))
        assertEquals(UwtpFrameDecode.NotUwtp, UwtpFrameCodec.decode(ByteArray(0)))
    }

    @Test
    fun malformedFrames() {
        fun reason(hex: String): String =
            assertIs<UwtpFrameDecode.Malformed>(UwtpFrameCodec.decode(hexToBytes(hex))).reason

        assertEquals("short_frame", reason("14 01 01"))
        assertEquals("ext_hdr", reason("1c 01 01 00 00")) // bit3=EXT_HDR 置位一律丢弃
        assertEquals("seg_mode", reason("11 01 01 00 00")) // SEG 非零: 分段实现落地前丢弃计数
        assertEquals("len_mismatch", reason("14 01 01 00 05 08 01")) // len=5 实际 2B
        assertEquals("len_mismatch", reason("14 01 01 00 01 08 01")) // 帧尾多余字节(一帧恰占一次 notify)
        assertEquals("role_rsp_needrsp", reason("14 01 81 00 00")) // IS_RESPONSE=1 且 NEED_RSP=1 非法(§4)
    }

    @Test
    fun payloadTooLargeRejected() {
        var thrown = false
        try {
            UwtpFrameCodec.encodeCommand(Uwtp.MT_CTRL, Uwtp.OP_CTRL_INFO, 0, ByteArray(256))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
