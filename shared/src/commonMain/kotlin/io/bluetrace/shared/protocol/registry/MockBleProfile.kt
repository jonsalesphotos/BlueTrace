package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.protocol.MockPacketCodec
import io.bluetrace.shared.protocol.ProtocolEvent

/**
 * Mock 线协议档案(R2: 把 MockPacketCodec 装进 Profile 形状)。
 * 兜底语义: [matches] 恒真——只注册进 Mock 后端的注册表, 或作为
 * [RegistrySampleDecoder] 的 fallback(无 profileId 的自研 DUT 在协议冻结前走这里,
 * 真实字节解不出 → Malformed 诊断, 与旧 unparseable 告警等价)。
 */
class MockBleProfile : ProtocolProfile {
    override val id: String = ID

    override fun matches(device: io.bluetrace.shared.domain.ScannedDevice): Boolean = true

    override val notifyChannels: List<ChannelId> = listOf(CHANNEL)

    override fun createParser(channel: ChannelId): ChannelParser = MockParser()

    private class MockParser : ChannelParser {
        override fun parse(notification: BleNotification): List<ProtocolEvent> {
            val sample = MockPacketCodec.decode(notification.rawBytes, notification.receivedAtMs)
            return if (sample == null) listOf(ProtocolEvent.Malformed("mock-wire unparseable"))
            else listOf(ProtocolEvent.Samples(listOf(sample)))
        }
    }

    companion object {
        const val ID = "Mock.BlueTrace.v1"

        // 16-bit 短码占位(W2 短码对齐, 口径同 HrsProfile). Mock 后端数据面通知不带特征 id,
        // 仍走 DeviceParserHost 单通道兜底(single); 此处短码只为两侧注册口径统一, 不参与实际路由.
        val CHANNEL = ChannelId(
            serviceUuid = "FEED",
            characteristicUuid = "FEED",
        )
    }
}
