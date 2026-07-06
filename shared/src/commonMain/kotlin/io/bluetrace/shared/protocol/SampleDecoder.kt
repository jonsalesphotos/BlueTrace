package io.bluetrace.shared.protocol

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DeviceKind

/**
 * 原始 Notify 字节 → 已解码样本（协议层接口，上层只认这个，BLE 与解码均可替换）。
 *
 * v1：[MockSampleDecoder] 解 [MockPacketCodec] 的假格式。
 * 协议冻结后：换 `WireSampleDecoder`（标准 12B 帧头 + 一包多帧 + 分片重组 + msgType dispatch
 * + 高频批包定宽字节解析，§4），接口不变、上层不改。
 */
interface SampleDecoder {
    fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample>

    /**
     * 会话边界钩子：每次会话开始由编排层调用。decoder 是跨会话的全局单例——
     * 持有跨包状态（分片重组缓冲、pktSeq 等）的实现必须在此清空，否则上一会话的半包会带进新会话。
     * v1 Mock 无状态，默认 no-op。
     */
    fun onSessionStart() {}

    /**
     * 设备链路重置钩子：某设备断连进入重连时由编排层调用。真实协议断连后 pktSeq/分片必然中断，
     * 该设备的重组缓冲应整体丢弃，避免与重连后的新流错拼。默认 no-op。
     */
    fun onDeviceReset(deviceId: String) {}
}

/** v1 Mock 解码器（解 [MockPacketCodec] 单样本包）。 */
class MockSampleDecoder : SampleDecoder {
    override fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample> {
        val sample = MockPacketCodec.decode(notification.rawBytes, notification.receivedAtMs)
        return if (sample == null) emptyList() else listOf(sample)
    }
}
