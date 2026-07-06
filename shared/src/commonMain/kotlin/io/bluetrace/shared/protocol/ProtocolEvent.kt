package io.bluetrace.shared.protocol

/**
 * 协议事件——解析层统一出口(02 设计 §3.4, R3 事件模型)。
 * [SampleDecoder.decodeEvents] 产出, 会话编排层按类型分发: 样本进落盘/设备卡, 其余进诊断日志
 * (控制面 R4/R5 接入后再各就各位)。
 *
 * 02 全集还有 Capability/State/AlgoResult/FileChunk 四类, 其 payload 形状依赖未冻结的
 * 采集协议(M7), 待 R5 冻结后补充——现在臆造字段只会返工。
 */
sealed interface ProtocolEvent {
    /** 一包解出的样本(可能多条, 高频批包)。空列表=该包合法但无样本(如心跳)。 */
    data class Samples(val samples: List<DecodedSample>) : ProtocolEvent

    /** 命令应答(R4 起控制面用; key=协议命令/子键, ok=设备侧执行结果)。 */
    data class CommandAck(val key: Int, val ok: Boolean) : ProtocolEvent

    /** 设备主动事件(佩戴脱落/按键/低电等, 编码依协议)。 */
    data class DeviceEvent(val code: Int, val detail: String? = null) : ProtocolEvent

    /** 解析失败(线内容不可信, 只告警不抛; raw HEX 已另行落盘可事后追查)。 */
    data class Malformed(val reason: String) : ProtocolEvent
}
