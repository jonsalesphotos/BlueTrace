package io.bluetrace.shared.protocol.registry

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.AssignedDevice
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.protocol.DecodedSample
import io.bluetrace.shared.protocol.ProtocolEvent
import io.bluetrace.shared.protocol.SampleDecoder

/**
 * 注册表驱动的解码器(R1 适配器): 把 [ProtocolRegistry]/[DeviceParserHost] 接到既有
 * [SampleDecoder] 缝上——编排层([io.bluetrace.shared.session.DefaultSessionController])
 * 只认 SampleDecoder, 换协议 = 换注册表内容, 编排零改动。
 *
 * 设备→Profile 解析: 会话开始 [onDeviceAttached] 按 profileId 查注册表,
 * 查不到(自研 DUT 协议冻结前 profileId=null)用 [fallback]。
 * 线程模型: 全部方法由会话串行上下文调用(start 主线程先行, 消费协程单线程), 无并发。
 */
class RegistrySampleDecoder(
    private val registry: ProtocolRegistry,
    private val fallback: ProtocolProfile,
) : SampleDecoder {

    private val hosts = mutableMapOf<String, DeviceParserHost>()

    override fun onSessionStart() {
        hosts.clear()
    }

    override fun onDeviceAttached(device: AssignedDevice) {
        val profile = device.profileId?.let { registry.byId(it) } ?: fallback
        hosts[device.deviceId] = DeviceParserHost(device.deviceId, profile)
    }

    override fun onDeviceReset(deviceId: String) {
        hosts[deviceId]?.onReconnected()
    }

    override fun decodeEvents(kind: DeviceKind, notification: BleNotification): List<ProtocolEvent> =
        hosts.getOrPut(notification.deviceId) { DeviceParserHost(notification.deviceId, fallback) }
            .parse(notification)

    override fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample> =
        decodeEvents(kind, notification).filterIsInstance<ProtocolEvent.Samples>().flatMap { it.samples }
}
