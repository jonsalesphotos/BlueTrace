package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.DecodedSample
import io.bluetrace.shared.protocol.ProtocolEvent

/**
 * 标准心率服务档案(SIG HRS 0x180D / 心率测量 0x2A37)——参考心率带(Polar H10 等)。
 * R4 真实链路首连的先行协议: 不依赖自研协议冻结, 有心率带即可跑通真实采集。
 */
class HrsProfile : ProtocolProfile {
    override val id: String = PROFILE_HRS

    override fun matches(device: ScannedDevice): Boolean =
        device.profileId == PROFILE_HRS ||
            device.advertisedServices.any { it.lowercase().contains("180d") }

    override fun confirmByServices(serviceUuids: List<String>): Boolean =
        serviceUuids.any { it.lowercase().contains("180d") }

    override val notifyChannels: List<ChannelId> = listOf(HR_MEASUREMENT)

    override fun createParser(channel: ChannelId): ChannelParser = HrsParser()

    companion object {
        // 16-bit 短码注册(W2 短码对齐): BLE 层通知回填的 characteristicId 即 16-bit "2A37"
        // (AndroidBleClient/NordicBleClient 经 extract16 回填), DeviceParserHost 两侧归一后真正命中本通道.
        val HR_MEASUREMENT = ChannelId(
            serviceUuid = "180D",
            characteristicUuid = "2A37",
        )
    }
}

/**
 * 心率测量特征(0x2A37)解析: flags(1B) + bpm(u8 或 u16LE, 按 flags bit0)。
 * Energy Expended / RR-Interval 等后续字段与传感器接触位暂不消费。
 * HRS 无设备时钟, deviceTsUs 用接收时刻充当(参考设备只做对齐参照, 精度够用)。
 */
class HrsParser : ChannelParser {
    override fun parse(notification: BleNotification): List<ProtocolEvent> {
        val b = notification.rawBytes
        if (b.isEmpty()) return listOf(ProtocolEvent.Malformed("hrs: empty packet"))
        val u16 = (b[0].toInt() and 0x01) != 0
        val need = if (u16) 3 else 2
        if (b.size < need) return listOf(ProtocolEvent.Malformed("hrs: short packet ${b.size}B"))
        val bpm = if (u16) {
            (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
        } else {
            b[1].toInt() and 0xFF
        }
        val sample = DecodedSample(
            stream = DecodedStream.HR,
            deviceTsUs = notification.receivedAtMs * 1000,
            receivedAtMs = notification.receivedAtMs,
            channels = listOf(bpm),
        )
        return listOf(ProtocolEvent.Samples(listOf(sample)))
    }
}
