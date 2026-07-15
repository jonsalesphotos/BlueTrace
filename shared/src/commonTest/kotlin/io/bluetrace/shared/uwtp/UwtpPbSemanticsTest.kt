package io.bluetrace.shared.uwtp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Parser 语义兼容测试(工作稿 §6.8):
 * 必须接受——字段乱序 / 未知字段 / 普通字段缺失读默认值 / error_code 省略与显式 `08 00` 等价;
 * 必须拒绝——wire 截断或非法。
 */
class UwtpPbSemanticsTest {

    @Test
    fun errorCodeOmittedEqualsExplicitZero() {
        // 省略(0 字节)与显式 08 00 两种编码等价, 均为成功(§6.3)
        assertEquals(0, MinimalErrorRsp.readErrorCode(ByteArray(0)))
        assertEquals(0, MinimalErrorRsp.readErrorCode(hexToBytes("08 00")))
        val rsp = TransferBeginRsp.decode(hexToBytes("08 00 10 01"))
        assertEquals(0, rsp.errorCode)
        assertEquals(1L, rsp.transferId)
    }

    @Test
    fun unknownFieldsSkipped() {
        // PoC 样本: field3 varint=0x4d + field4 LEN "hello"(§13.8 PoC ④)
        val extra = "18 4d 22 05 68 65 6c 6c 6f"
        assertEquals(2, MinimalErrorRsp.readErrorCode(hexToBytes("08 02 $extra")))
        // 已知消息中夹未知字段(field9 varint / field10 fixed64 / field11 fixed32)
        val rsp = TransferBeginRsp.decode(hexToBytes("10 01 48 7b 51 01 02 03 04 05 06 07 08 5d aa bb cc dd 18 20"))
        assertEquals(1L, rsp.transferId)
        assertEquals(32L, rsp.totalSize)
    }

    @Test
    fun outOfOrderFieldsAccepted() {
        // transfer_ack 字段倒序: field2 先于 field1
        val ack = TransferAck.decode(hexToBytes("10 80 80 04 08 01"))
        assertEquals(TransferAck(transferId = 1, nextExpectedOffset = 65_536), ack)
    }

    @Test
    fun missingFieldsReadDefaults() {
        val rsp = TransferBeginRsp.decode(hexToBytes("10 01"))
        assertEquals(0, rsp.errorCode)
        assertEquals(1L, rsp.transferId)
        assertEquals(0L, rsp.totalSize)
        assertEquals(0, rsp.acceptedAckEveryN)
        // STATE 成功响应缺 runtime -> Presence 检查在客户端层(消息语义校验, 与 error_code 无关)
        val state = CtrlStateQueryRsp.decode(ByteArray(0))
        assertEquals(0, state.errorCode)
        assertEquals(null, state.runtime)
    }

    @Test
    fun truncatedWireRejected() {
        assertFailsWith<PbDecodeException> { MinimalErrorRsp.readErrorCode(hexToBytes("08")) } // varint 断尾
        assertFailsWith<PbDecodeException> { TransferBeginRsp.decode(hexToBytes("21 88 77")) } // fixed64 断尾
        assertFailsWith<PbDecodeException> { CtrlStateQueryRsp.decode(hexToBytes("12 08 08 01")) } // LEN 越界
        assertFailsWith<PbDecodeException> { ObjectListRsp.decode(hexToBytes("12")) } // LEN 长度字节缺失
    }

    @Test
    fun wrongWireTypeOnKnownFieldRejected() {
        // error_code(field1)按 fixed64 编码 -> 生产者坏 wire, 拒绝
        assertFailsWith<PbDecodeException> {
            MinimalErrorRsp.readErrorCode(hexToBytes("09 01 00 00 00 00 00 00 00"))
        }
    }

    @Test
    fun varintOverlongRejected() {
        // 11 字节 varint(>64bit)拒绝
        assertFailsWith<PbDecodeException> {
            MinimalErrorRsp.readErrorCode(hexToBytes("08 80 80 80 80 80 80 80 80 80 80 01"))
        }
    }

    @Test
    fun uint32TruncatesTo32Bits() {
        // 5 字节 varint 值 0x1_0000_0001 -> uint32 语义截断为 1(与 protobuf 参考实现一致)
        val rsp = TransferBeginRsp.decode(hexToBytes("10 81 80 80 80 10"))
        assertEquals(1L, rsp.transferId)
    }

    @Test
    fun emptySubMessagePresenceDetected() {
        val rsp = CtrlStateQueryRsp.decode(hexToBytes("12 00"))
        val rt = assertNotNull(rsp.runtime)
        assertEquals(CtrlRuntimeState(0, 0, 0), rt)
    }

    @Test
    fun deleteReqUsesPackedRepeated() {
        // proto3 repeated uint32 默认 packed: field1 LEN + varint 体(与 protoc 参考生成器一致)
        assertEquals("0a 03 07 ac 02", bytesToHex(TransferDeleteReq(listOf(7, 300)).encode()))
        assertEquals(0, TransferDeleteReq(emptyList()).encode().size)
    }

    @Test
    fun crc32ReferenceVector() {
        // CRC-32(IEEE) 标准测试向量: "123456789" -> 0xCBF43926
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }
}
