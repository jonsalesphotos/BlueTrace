package io.bluetrace.shared.s7

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S7MessagesTest {

    @Test
    fun dateTime_encodeParseRoundtrip() {
        val dt = S7DateTime(2026, 7, 2, 14, 5, 9, week = 3, timezone = 8)
        val parsed = S7DateTime.parse(dt.encode())!!
        assertEquals(dt, parsed)
        assertEquals("2026-07-02 14:05:09", parsed.display())
    }

    @Test
    fun dateTime_negativeTimezone_signed() {
        // GET 响应 timezone 为有符号 int8（spec §4.1）
        val b = S7DateTime(2026, 1, 1, 0, 0, 0, 1, timezone = -5).encode()
        assertEquals(-5, S7DateTime.parse(b)!!.timezone)
    }

    @Test
    fun dateTime_truncated_returnsNull() {
        assertNull(S7DateTime.parse(ByteArray(8)))
    }

    @Test
    fun dateTime_invalidFieldValues_returnNull() {
        // RTC 未初始化/恢复出厂后的垃圾值不得进入下游（评审修复 #0：防 monthDays 越界崩溃）
        assertNull(S7DateTime.parse(ByteArray(9))) // month=0
        assertNull(S7DateTime.parse(S7DateTime(2026, 13, 1, 0, 0, 0, 1, 0).encode())) // month=13
        assertNull(S7DateTime.parse(S7DateTime(2026, 1, 32, 0, 0, 0, 1, 0).encode())) // day=32
        assertNull(S7DateTime.parse(S7DateTime(2026, 1, 1, 24, 0, 0, 1, 0).encode())) // hour=24
    }

    @Test
    fun battery_parse10Bytes() {
        val b = ByteArray(10)
        S7FrameCodec.writeLe16(b, 0, 280)
        S7FrameCodec.writeLe16(b, 2, 4130)
        b[4] = 82
        b[5] = 1
        val bat = S7Battery.parse(b)!!
        assertEquals(280, bat.capacityMah)
        assertEquals(4130, bat.voltageMv)
        assertEquals(82, bat.percent)
        assertEquals(1, bat.chargeStatus)
        assertNull(S7Battery.parse(ByteArray(9)))
    }

    @Test
    fun snInfo_parse59Bytes_zeroPaddedAsciiAndMac() {
        val b = ByteArray(59)
        byteArrayOf(0x68, 0x39, 0x71, 0x25, 0x81.toByte()).copyInto(b, 0) // device_type 码
        "SN0001".encodeToByteArray().copyInto(b, 5) // 12B 槽位只填 6 字符 → 零截断
        // BleMac 线上为 LE 反序（真机实证）：填 10:0D:0A:8D:7B:C4 → 展示 C4:7B:8D:0A:0D:10
        byteArrayOf(0x10, 0x0D, 0x0A, 0x8D.toByte(), 0x7B, 0xC4.toByte()).copyInto(b, 17)
        "861234056789012".encodeToByteArray().copyInto(b, 23)
        "89860425987654321098".encodeToByteArray().copyInto(b, 39)
        val sn = S7SnInfo.parse(b)!!
        assertEquals("68 39 71 25 81", sn.devType)
        assertEquals("SN0001", sn.sn)
        assertEquals("C4:7B:8D:0A:0D:10", sn.macHex)
        assertEquals("861234056789012", sn.imei)
        assertEquals("89860425987654321098", sn.iccid)
        assertNull(S7SnInfo.parse(ByteArray(58)))
    }

    @Test
    fun snInfo_nonPrintableTruncation_andBinaryDevTypeHex() {
        // 真机实证：IMEI 尾部可含非打印填充 → 截断；DevType 恒 hex 展示
        val b = ByteArray(59)
        byteArrayOf(0x02, 0x68, 0x39, 0x71, 0x25).copyInto(b, 0)
        "861234056789012".encodeToByteArray().copyInto(b, 23)
        b[38] = 0x01 // IMEI 第 16 字节非打印 → 不影响前 15 位
        val sn = S7SnInfo.parse(b)!!
        assertEquals("02 68 39 71 25", sn.devType)
        assertEquals("861234056789012", sn.imei)
    }

    @Test
    fun deviceInfo_tlvRoundtrip() {
        val info = S7DeviceInfo("1.2.7.0", "1.0", "1.0", "23")
        assertEquals(info, S7DeviceInfo.parse(info.encode()))
    }

    @Test
    fun deviceInfo_truncatedTlv_returnsNull() {
        val bytes = S7DeviceInfo("1.2.7.0", "1.0", "1.0", "23").encode()
        assertNull(S7DeviceInfo.parse(bytes.copyOfRange(0, 3))) // sw 段长度域越界
    }

    @Test
    fun person_getSetLayouts() {
        val p = S7Person(heightCm = 175, weightKg = 68, gender = 1, birthYear = 1993, birthMonth = 6, birthDay = 12)
        val set = p.encodeSet()
        assertEquals(8, set.size)
        // GET 响应 = SET 前 7 字节布局（spec §4.6）
        assertEquals(p, S7Person.parse(set.copyOfRange(0, 7)))
        assertEquals(p, S7Person.parse(set)) // 8B 输入同样可解（只读前 7）
        assertNull(S7Person.parse(ByteArray(6)))
    }

    @Test
    fun heartbeat_parse() {
        val utc = 1_782_963_749L
        val b = ByteArray(8)
        b[0] = (utc and 0xFF).toByte()
        b[1] = ((utc shr 8) and 0xFF).toByte()
        b[2] = ((utc shr 16) and 0xFF).toByte()
        b[3] = ((utc shr 24) and 0xFF).toByte()
        S7FrameCodec.writeLe16(b, 4, 1234)
        b[6] = 82
        val hb = S7Heartbeat.parse(b)!!
        assertEquals(utc, hb.utcSeconds)
        assertEquals(1234, hb.seq)
        assertEquals(82, hb.batteryPercent)
    }

    @Test
    fun commAck_semantics() {
        assertEquals(true, S7.commAckOk(byteArrayOf(0)))
        assertEquals(false, S7.commAckOk(byteArrayOf(1)))
        assertEquals(false, S7.commAckOk(byteArrayOf(5)))
        assertEquals(false, S7.commAckOk(ByteArray(0)))
    }

    @Test
    fun hexPreview_truncates() {
        val s = ByteArray(30) { it.toByte() }.toHexPreview(maxBytes = 4)
        assertEquals("00 01 02 03 …(30B)", s)
        assertContentEquals(
            "BB 02".toCharArray(),
            byteArrayOf(0xBB.toByte(), 0x02).toHexPreview().toCharArray(),
        )
    }
}
