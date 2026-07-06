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

        /** 假 uuid 占位(Mock 后端通知不带特征 id, 走单通道兜底路由)。 */
        val CHANNEL = ChannelId(
            serviceUuid = "0000feed-0000-1000-8000-00805f9b34fb",
            characteristicUuid = "0000feed-0001-1000-8000-00805f9b34fb",
        )
    }
}
