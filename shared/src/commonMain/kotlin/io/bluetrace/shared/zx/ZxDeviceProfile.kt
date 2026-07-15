package io.bluetrace.shared.zx

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.device.ControlPlaneFactory
import io.bluetrace.shared.device.DeviceProfile
import io.bluetrace.shared.device.FirmwareUpdateFactory
import io.bluetrace.shared.device.ScanSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.protocol.registry.ProtocolProfile

/**
 * ZX(假协议)稳定标识: 与解码侧同一命名空间口径(对照 [io.bluetrace.shared.domain.PROFILE_S7]/
 * [io.bluetrace.shared.domain.PROFILE_HRS]), 但本协议不进解码侧(dataPlane=null), 常量只供识别/
 * roster 打标使用, 故不放 domain 包(W6 验收判据: 新增协议不改框架, domain 也在框架面内).
 */
const val PROFILE_ZX = "ZX.Fake.AA00"

/**
 * ZX(假协议)设备档案(识别面, 设计 V2 §3.1 / W6 异构验收): 与 S7 **全维度异构**——
 * 不同服务(`AA00` 非 `FFE0`), 不同特征(写 `AA01` / 通知 `AA02` 非 `FFE1`/`FFE2`),
 * 不同写类型([gattSpec] 声明 Write With Response, S7 是 Write Without Response).
 *
 * matches/confirm 均用 [DeviceProfile] 默认实现(广播/服务表按 [scanSpec]/[gattSpec] 直接比对即够,
 * 无需 S7 式名称前缀/128-bit 兼容覆写)——用最朴素的路径反证框架的识别面不偏向任何一种协议实现方式.
 *
 * dataPlane=null(本协议无采集数据流, 只验证控制/升级两面); controlPlane=[ZxControlPlaneFactory]
 * (故意缺 logs 面 + vendor 面, 验证 W5 UI 按分面 null 显隐); firmwareUpdate=[ZxFirmwareUpdateFactory]
 * (与 S7"自复位回连"相反的"原地生效"策略, 验证升级重连行为是协议私有假设而非框架假设).
 */
class ZxDeviceProfile : DeviceProfile {
    override val profileId: String = PROFILE_ZX

    override val scanSpec: ScanSpec = ScanSpec(listOf("AA00"))

    override val gattSpec: GattSpec = GattSpec(
        serviceUuid16 = "AA00",
        notifyChar16s = listOf("AA02"),
        writeChar16 = "AA01",
        writeWithResponse = true, // 与 S7(false)异构: 写类型也是协议私有声明, 非框架假设
    )

    override val kind: DeviceKind = DeviceKind.DUT

    override val dataPlane: ProtocolProfile? = null
    override val controlPlane: ControlPlaneFactory = ZxControlPlaneFactory()
    override val firmwareUpdate: FirmwareUpdateFactory = ZxFirmwareUpdateFactory()
}
