package io.bluetrace.shared.device

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.ProtocolProfile

/** 广播识别声明(设计 V2 §3.1): 先按广播 service 表匹配; 复杂匹配交 [DeviceProfile.matches] 覆写. */
data class ScanSpec(val advertisedService16s: List<String>)

// 控制面工厂 ControlPlaneFactory 已在 W3 转正, 定义见 DeviceControl.kt(同包).
// 固件升级面工厂 FirmwareUpdateFactory 已在 W4 转正, 定义见 FirmwareUpdate.kt(同包).

/**
 * 设备档案(识别一次的载体, 设计 V2 §3.1): 一种协议设备怎么认(广播 [matches]/连接后 [confirm]),
 * 走哪条 GATT 通道([gattSpec]), 扮演什么角色([kind]), 以及三个能力分面工厂(可空=无该能力).
 *
 * W2 落识别面: matches/confirm/scanSpec/gattSpec/kind + dataPlane 挂现成解码;
 * W3 已填 controlPlane(控制面工厂); firmwareUpdate 仍空 marker 占位, W4 填充.
 */
interface DeviceProfile {
    /** 稳定标识, 与解码侧同一命名空间(PROFILE_B2A/PROFILE_HRS 等). */
    val profileId: String

    /** 广播识别声明. */
    val scanSpec: ScanSpec

    /** GATT 通道声明(复用 BLE 层 [GattSpec]): 连接后按此发现/订阅/写. */
    val gattSpec: GattSpec

    /** 该协议设备的角色(扫描打标用): S7=DUT, HRS=REFERENCE. */
    val kind: DeviceKind

    /** 广播匹配(默认=广播 service 命中 [scanSpec]; 大小写容忍; 复杂匹配覆写). */
    fun matches(device: ScannedDevice): Boolean =
        device.advertisedServices.any { adv ->
            scanSpec.advertisedService16s.any { it.equals(adv, ignoreCase = true) }
        }

    /**
     * 连接后二次确认(默认=发现的服务表含 [GattSpec.serviceUuid16]; 同解码侧 ProtocolProfile 先例).
     * false=识别撤销. **本波只落接口与默认实现 + 单测; 实际运行时调用接线归 W3 DeviceSessionManager.**
     */
    fun confirm(discoveredService16s: List<String>): Boolean =
        discoveredService16s.any { it.equals(gattSpec.serviceUuid16, ignoreCase = true) }

    /** 采集解码面: 直接复用解码侧现有 [ProtocolProfile](挂现成实现); 无(协议未冻结)则 null. */
    val dataPlane: ProtocolProfile?

    /** 控制面工厂(W3 已转正, 定义见 DeviceControl.kt; 无控制能力则 null——如 HRS 参考带). */
    val controlPlane: ControlPlaneFactory?

    /** 固件升级面工厂(W4 已转正, 定义见 FirmwareUpdate.kt; 无升级能力则 null——如 HRS 参考带). */
    val firmwareUpdate: FirmwareUpdateFactory?
}
