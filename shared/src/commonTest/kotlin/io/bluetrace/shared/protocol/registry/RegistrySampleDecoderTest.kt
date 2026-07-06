package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.dutAssigned
import io.bluetrace.shared.protocol.MockPacketCodec
import io.bluetrace.shared.protocol.ProtocolEvent
import io.bluetrace.shared.refAssigned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrsParserTest {
    private fun hrsNotif(vararg bytes: Int, char: String? = "00002A37-0000-1000-8000-00805F9B34FB") =
        BleNotification("ref-h10", 1000L, bytes.map { it.toByte() }.toByteArray(), char)

    private fun bpmOf(events: List<ProtocolEvent>): Int {
        val samples = assertIs<ProtocolEvent.Samples>(events.single()).samples
        val s = samples.single()
        assertEquals(DecodedStream.HR, s.stream)
        return s.channels.single()
    }

    @Test
    fun `u8 心率`() {
        assertEquals(72, bpmOf(HrsParser().parse(hrsNotif(0x00, 72))))
    }

    @Test
    fun `u16 心率(flags bit0)`() {
        // 0x0140 = 320(测 u16 路径, 超 u8 上限的值)
        assertEquals(320, bpmOf(HrsParser().parse(hrsNotif(0x01, 0x40, 0x01))))
    }

    @Test
    fun `尾随字段不影响 bpm(Energy Expended 等)`() {
        assertEquals(65, bpmOf(HrsParser().parse(hrsNotif(0x08, 65, 0x12, 0x34))))
    }

    @Test
    fun `短包与空包产 Malformed`() {
        assertIs<ProtocolEvent.Malformed>(HrsParser().parse(hrsNotif(0x01, 72)).single())
        assertIs<ProtocolEvent.Malformed>(
            HrsParser().parse(BleNotification("d", 0L, ByteArray(0))).single(),
        )
    }
}

class ProtocolRegistryTest {
    private val registry = ProtocolRegistry(listOf(HrsProfile(), MockBleProfile()))

    @Test
    fun `byId 按稳定标识查档`() {
        assertEquals(PROFILE_HRS, registry.byId(PROFILE_HRS)?.id)
        assertEquals(MockBleProfile.ID, registry.byId(MockBleProfile.ID)?.id)
        assertNull(registry.byId("unknown"))
    }

    @Test
    fun `resolve 按列表顺序取首个 matches`() {
        val scanned = io.bluetrace.shared.domain.ScannedDevice(
            id = "ref", name = "Polar H10", address = "AA", rssi = -50,
            kind = DeviceKind.REFERENCE, profileId = PROFILE_HRS,
        )
        assertEquals(PROFILE_HRS, registry.resolve(scanned)?.id)
    }
}

class RegistrySampleDecoderTest {
    private fun decoder() = RegistrySampleDecoder(
        registry = ProtocolRegistry(listOf(HrsProfile(), MockBleProfile())),
        fallback = MockBleProfile(),
    )

    @Test
    fun `profileId 命中 HRS - 特征 id 大小写不敏感路由`() {
        val d = decoder()
        d.onSessionStart()
        d.onDeviceAttached(refAssigned())
        val events = d.decodeEvents(
            DeviceKind.REFERENCE,
            BleNotification("ref-h10", 500L, byteArrayOf(0x00, 80), "00002A37-0000-1000-8000-00805F9B34FB"),
        )
        val s = assertIs<ProtocolEvent.Samples>(events.single()).samples.single()
        assertEquals(DecodedStream.HR, s.stream)
        assertEquals(80, s.channels.single())
    }

    @Test
    fun `无 profileId 的 DUT 回退 Mock 线协议`() {
        val d = decoder()
        d.onDeviceAttached(dutAssigned())
        val wire = MockPacketCodec.encode(DecodedStream.PPG_G, 123_000L, listOf(4321))
        val events = d.decodeEvents(DeviceKind.DUT, BleNotification("dut-0427", 1L, wire))
        val s = assertIs<ProtocolEvent.Samples>(events.single()).samples.single()
        assertEquals(DecodedStream.PPG_G, s.stream)
        assertEquals(listOf(4321), s.channels)
    }

    @Test
    fun `解不出的字节产 Malformed 而非丢样本静默`() {
        val d = decoder()
        d.onDeviceAttached(dutAssigned())
        val events = d.decodeEvents(DeviceKind.DUT, BleNotification("dut-0427", 1L, byteArrayOf(1, 2, 3)))
        assertIs<ProtocolEvent.Malformed>(events.single())
        // 旧接口 decode() 视角: 无样本
        assertTrue(d.decode(DeviceKind.DUT, BleNotification("dut-0427", 2L, byteArrayOf(1, 2, 3))).isEmpty())
    }

    @Test
    fun `未 attach 的设备惰性建兜底宿主, onSessionStart 清空重建`() {
        val d = decoder()
        val wire = MockPacketCodec.encode(DecodedStream.HR, 1_000L, listOf(70))
        val first = d.decodeEvents(DeviceKind.REFERENCE, BleNotification("ghost", 1L, wire))
        assertIs<ProtocolEvent.Samples>(first.single())
        d.onSessionStart()
        d.onDeviceReset("ghost") // 已清空后重置不存在的宿主: 不抛
        val again = d.decodeEvents(DeviceKind.REFERENCE, BleNotification("ghost", 2L, wire))
        assertIs<ProtocolEvent.Samples>(again.single())
    }

    @Test
    fun `同设备复用同一解析宿主(跨包状态归属稳定)`() {
        val d = decoder()
        d.onDeviceAttached(dutAssigned())
        val n = BleNotification("dut-0427", 1L, MockPacketCodec.encode(DecodedStream.ACC, 1L, listOf(1, 2, 3)))
        // 行为等价性检查: 连续两包互不影响(Mock 无状态, 这里锁住"每包独立"的现状)
        assertIs<ProtocolEvent.Samples>(d.decodeEvents(DeviceKind.DUT, n).single())
        assertIs<ProtocolEvent.Samples>(d.decodeEvents(DeviceKind.DUT, n).single())
    }
}
