package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.ProtocolEvent

/**
 * 通道标识: 一个 Notify/Write 特征(02 设计 §3.1)。uuid 用 128 位全长小写字符串比对,
 * 但 [ProtocolProfile] 实现里允许存任意大小写——比对方([DeviceParserHost])统一 lowercase。
 */
data class ChannelId(val serviceUuid: String, val characteristicUuid: String)

/**
 * 单通道解析器: 原始 Notify 字节 → 协议事件(02 设计 §3.2)。
 * 有状态实现(分片重组/pktSeq)在 [reset] 清空; 调用方保证单线程串行(会话消费协程)。
 */
interface ChannelParser {
    fun parse(notification: BleNotification): List<ProtocolEvent>

    /** 链路重置(断连进重连)时清跨包状态, 默认无状态 no-op。 */
    fun reset() {}
}

/**
 * 协议档案: 一种设备协议的自描述(02 设计 §3.3)——怎么认设备、订哪些通道、每通道怎么解析。
 * 新协议 = 新增一个 Profile 注册进 [ProtocolRegistry], 编排层零改动。
 *
 * 02 全集中的 `commandEncoder()`(下行命令编码)属控制面, R4 接真实链路时再补——
 * 现阶段采集面只消费 Notify。
 */
interface ProtocolProfile {
    /** 稳定标识, 与 [io.bluetrace.shared.domain.ScannedDevice.profileId] 同一命名空间。 */
    val id: String

    /** 扫描阶段按广播判定(名前缀/广播服务), 不连接。 */
    fun matches(device: ScannedDevice): Boolean

    /** 连接后按发现的服务二次确认(R4 真实 GATT 用), 默认通过。 */
    fun confirmByServices(serviceUuids: List<String>): Boolean = true

    /** 需订阅的 Notify 通道。 */
    val notifyChannels: List<ChannelId>

    /** 下行写通道(无控制面的协议为 null)。 */
    val writeChannel: ChannelId? get() = null

    /** 为一个通道建解析器(每设备每通道一实例, 状态互不串扰)。 */
    fun createParser(channel: ChannelId): ChannelParser
}

/**
 * 协议注册表: 全部已知 Profile 的查找入口(02 设计 §3.3)。列表顺序即 [resolve] 匹配优先级。
 * 注册内容由 DI 按 BLE 后端拼装(Mock 后端全员 Mock 线协议, 真实后端注册真协议)。
 */
class ProtocolRegistry(private val profiles: List<ProtocolProfile>) {
    fun resolve(device: ScannedDevice): ProtocolProfile? = profiles.firstOrNull { it.matches(device) }
    fun byId(id: String): ProtocolProfile? = profiles.firstOrNull { it.id == id }
    val all: List<ProtocolProfile> get() = profiles
}

/**
 * 单设备解析宿主(02 设计 §3.5): 按通道路由 Notify 到对应解析器, 承接链路重置。
 * 生命周期 = 一次会话内一台设备; 单线程串行访问(会话消费协程)。
 */
class DeviceParserHost(
    val deviceId: String,
    val profile: ProtocolProfile,
) {
    private val parsers: Map<String, ChannelParser> =
        profile.notifyChannels.associate { ch -> ch.characteristicUuid.lowercase() to profile.createParser(ch) }

    /** 单通道 profile 的兜底解析器: 通知没带特征 id(Mock 后端)或 id 未注册时用它。 */
    private val single: ChannelParser? = parsers.values.singleOrNull()

    fun parse(notification: BleNotification): List<ProtocolEvent> {
        val key = notification.characteristicId?.lowercase()
        val parser = (if (key != null) parsers[key] else null) ?: single
            ?: return listOf(
                ProtocolEvent.Malformed("no parser for channel ${notification.characteristicId} (profile=${profile.id})"),
            )
        return parser.parse(notification)
    }

    /** 断连→重连: 全通道清跨包状态(pktSeq/分片必然中断, 防与新流错拼)。 */
    fun onReconnected() {
        parsers.values.forEach { it.reset() }
    }
}
