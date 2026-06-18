package com.example.bluetrace.shared.protocol

import com.example.bluetrace.shared.ble.BleNotification
import com.example.bluetrace.shared.domain.DeviceKind

/**
 * 原始 Notify 字节 → 已解码样本（协议层接口，上层只认这个，BLE 与解码均可替换）。
 *
 * v1：[MockSampleDecoder] 解 [MockPacketCodec] 的假格式。
 * 协议冻结后：换 `WireSampleDecoder`（标准 12B 帧头 + 一包多帧 + 分片重组 + msgType dispatch
 * + 高频批包定宽字节解析，§4），接口不变、上层不改。
 */
interface SampleDecoder {
    fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample>
}

/** v1 Mock 解码器（解 [MockPacketCodec] 单样本包）。 */
class MockSampleDecoder : SampleDecoder {
    override fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample> {
        val sample = MockPacketCodec.decode(notification.rawBytes, notification.receivedAtMs)
        return if (sample == null) emptyList() else listOf(sample)
    }
}
