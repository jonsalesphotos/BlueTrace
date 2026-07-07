package io.bluetrace.shared.protocol

/**
 * 标准帧头模型（§4.3，固定 12 字节，小端）—— **v1 桩**：真实链路解析（一包多帧拆分 / 分片重组 /
 * hdrCrc8 校验 / msgType dispatch）协议冻结后再实现，第一版 Mock 跳过 Wire 解析。
 *
 * 留此类型 + [MsgType] 注册表，是为日后 `WireSampleDecoder` 接住 [SampleDecoder] 接口、上层不改。
 */
data class FrameHeader(
    val ver: Int,
    val msgType: Int,
    val flags: Int,
    val fragIndex: Int,
    val fragCount: Int,
    val hdrCrc8: Int,
    val pktSeq: Int,
    val msgId: Int,
    val payloadLen: Int,
) {
    companion object {
        const val SIZE = 12
        const val VER_V0 = 0x00

        // flags 位（§4.3）
        const val FLAG_MORE_FRAGMENTS = 0x01
        const val FLAG_FRAGMENTED = 0x02
        const val FLAG_NEEDS_ACK = 0x04
        const val FLAG_BATCH = 0x08
        const val FLAG_HAS_PAYLOAD_CRC = 0x10
    }
}

/** 消息类型注册表（header.msgType，§4.6）—— 与 `bluetrace_v0.proto`(已归档 Docs/归档/自研协议线_v0/, M7 改走 B2A 扩展路线)对齐, v1 仅占位。 */
object MsgType {
    const val HIGH_FREQ_BATCH = 0x01
    const val LOW_FREQ_FRAME = 0x02
    const val GOODIX_PPG_ACC = 0x03
    const val DEVICE_EVENT = 0x10
    const val DEVICE_CAPABILITY = 0x11
    const val DEVICE_STATE = 0x12
    const val ALGO_RESULT = 0x20
    const val COMMAND = 0x40
    const val ACK = 0x80
}
