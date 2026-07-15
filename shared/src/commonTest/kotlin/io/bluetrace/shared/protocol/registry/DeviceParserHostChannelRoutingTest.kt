package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.MockPacketCodec
import io.bluetrace.shared.protocol.ProtocolEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W1 交接必修(item 5)验证: 通道键短码对齐后, 带 characteristicId 的通知**真正按通道命中对应 parser**,
 * 不再靠单通道 [DeviceParserHost] 的 single 兜底掩盖(旧 bug: BLE 层填 16-bit "2A37", profile 注册
 * 128-bit 全串, parsers[key] 永不命中).
 */
class DeviceParserHostChannelRoutingTest {

    /** 双通道测试档案: 每通道解析器借 Malformed.reason 打各自命中标记, single=null(证明不走兜底). */
    private class TwoChannelProfile : ProtocolProfile {
        override val id: String = "test.twochan"
        override fun matches(device: ScannedDevice): Boolean = false
        // 注册用 16-bit 短码(与 W2 后 HrsProfile/MockBleProfile 口径一致)
        override val notifyChannels: List<ChannelId> = listOf(
            ChannelId(serviceUuid = "180D", characteristicUuid = "2A37"),
            ChannelId(serviceUuid = "FFE0", characteristicUuid = "FFE2"),
        )
        override fun createParser(channel: ChannelId): ChannelParser =
            TaggingParser(channel.characteristicUuid) // tag = 注册短码

        private class TaggingParser(val tag: String) : ChannelParser {
            override fun parse(notification: BleNotification): List<ProtocolEvent> =
                listOf(ProtocolEvent.Malformed(tag))
        }
    }

    private fun tagOf(events: List<ProtocolEvent>): String =
        assertIs<ProtocolEvent.Malformed>(events.single()).reason

    @Test
    fun `16-bit short-code characteristicId hits matching channel parser (not single, not other)`() {
        val host = DeviceParserHost("dev", TwoChannelProfile())
        // BLE 层真实口径: characteristicId = 16-bit 短码, 命中各自通道 parser, 互不串.
        assertEquals("2A37", tagOf(host.parse(BleNotification("dev", 1L, byteArrayOf(1), "2A37"))))
        assertEquals("FFE2", tagOf(host.parse(BleNotification("dev", 2L, byteArrayOf(1), "FFE2"))))
    }

    @Test
    fun `128-bit characteristicId normalizes to same 16-bit key and hits`() {
        val host = DeviceParserHost("dev", TwoChannelProfile())
        // 即便某实现回填 128-bit 全串, extract16 两侧归一后仍命中同一通道.
        val hit = host.parse(BleNotification("dev", 1L, byteArrayOf(1), "00002A37-0000-1000-8000-00805F9B34FB"))
        assertEquals("2A37", tagOf(hit))
    }

    @Test
    fun `multi-channel unknown characteristicId has no single fallback - Malformed no-parser`() {
        val host = DeviceParserHost("dev", TwoChannelProfile())
        // 双通道 single=null: 未注册通道 -> "no parser for channel"(而非误落某 parser)
        val miss = host.parse(BleNotification("dev", 1L, byteArrayOf(1), "ABCD"))
        assertTrue(tagOf(miss).startsWith("no parser for channel"))
    }

    @Test
    fun `HRS 16-bit short-code characteristicId routes through channel parser`() {
        // W2 修法直接验证: HrsProfile 注册改 2A37, 真实 BLE 层回填 2A37 -> 命中 HrsParser(不靠 single 掩盖)
        val host = DeviceParserHost("ref", HrsProfile())
        val events = host.parse(BleNotification("ref", 1L, byteArrayOf(0x00, 75), "2A37"))
        val s = assertIs<ProtocolEvent.Samples>(events.single()).samples.single()
        assertEquals(DecodedStream.HR, s.stream)
        assertEquals(75, s.channels.single())
    }

    @Test
    fun `single-channel null characteristicId falls back to single (Mock data-plane contract)`() {
        // Mock 数据面契约(见 MockBleClientTest): 单通道 + null characteristicId -> single 兜底, 正常解出.
        val host = DeviceParserHost("dev", MockBleProfile())
        val wire = MockPacketCodec.encode(DecodedStream.HR, 1_000L, listOf(70))
        assertIs<ProtocolEvent.Samples>(host.parse(BleNotification("dev", 1L, wire, null)).single())
    }
}
