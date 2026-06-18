package com.example.bluetrace.shared.protocol

import com.example.bluetrace.shared.domain.DecodedStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockPacketCodecTest {

    @Test
    fun encodeDecode_roundtrip_acc() {
        val raw = MockPacketCodec.encode(DecodedStream.ACC, deviceTsUs = 123_456L, channels = listOf(-5, 17, 1003))
        val sample = MockPacketCodec.decode(raw, receivedAtMs = 999L)!!
        assertEquals(DecodedStream.ACC, sample.stream)
        assertEquals(123_456L, sample.deviceTsUs)
        assertEquals(999L, sample.receivedAtMs)
        assertEquals(listOf(-5, 17, 1003), sample.channels)
    }

    @Test
    fun encodeDecode_signed16_negative() {
        val raw = MockPacketCodec.encode(DecodedStream.PPG_G, 0, listOf(-32768, 32767))
        val sample = MockPacketCodec.decode(raw, 0)!!
        assertEquals(listOf(-32768, 32767), sample.channels)
    }

    @Test
    fun decode_rejectsGarbage() {
        assertNull(MockPacketCodec.decode(byteArrayOf(0x00, 0x01, 0x02), 0))
        assertNull(MockPacketCodec.decode(ByteArray(0), 0))
        // 长度不足声明的通道数
        val bad = byteArrayOf(0x7E, 0x03, 0, 0, 0, 0, 3, 0, 0)
        assertNull(MockPacketCodec.decode(bad, 0))
    }

    @Test
    fun hexLine_isUpperSpaced() {
        val hex = byteArrayOf(0x7E, 0x02, 0x1A.toByte(), 0x08).toHexLine()
        assertEquals("7E 02 1A 08", hex)
    }

    @Test
    fun mockDecoder_delegatesToCodec() {
        val raw = MockPacketCodec.encode(DecodedStream.HR, 10, listOf(72))
        val decoder = MockSampleDecoder()
        val samples = decoder.decode(
            com.example.bluetrace.shared.domain.DeviceKind.REFERENCE,
            com.example.bluetrace.shared.ble.BleNotification("ref", 5, raw),
        )
        assertEquals(1, samples.size)
        assertEquals(DecodedStream.HR, samples[0].stream)
        assertTrue(samples[0].channels == listOf(72))
    }
}
