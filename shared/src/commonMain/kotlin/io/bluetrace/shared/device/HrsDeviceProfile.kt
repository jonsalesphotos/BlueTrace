package io.bluetrace.shared.device

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.protocol.registry.HrsProfile
import io.bluetrace.shared.protocol.registry.ProtocolProfile

/**
 * 标准心率带(SIG HRS 0x180D)设备档案. 广播含 180D 即识别(默认 matches, 等价旧 toScanned 的
 * `adv.contains("180D")` 判定); GATT: service 180D / notify 2A37 / 无写特征. kind=REFERENCE(参考心率源).
 * dataPlane=现有 [HrsProfile](原样挂入, 不重写解码).
 */
class HrsDeviceProfile : DeviceProfile {
    override val profileId: String = PROFILE_HRS

    override val scanSpec: ScanSpec = ScanSpec(listOf("180D"))

    override val gattSpec: GattSpec = GattSpec(
        serviceUuid16 = "180D",
        notifyChar16s = listOf("2A37"),
        writeChar16 = null, // 参考心率带纯上行, 无下行写
    )

    override val kind: DeviceKind = DeviceKind.REFERENCE

    override val dataPlane: ProtocolProfile = HrsProfile()
    override val controlPlane: ControlPlaneFactory? = null
    override val firmwareUpdate: FirmwareUpdateFactory? = null
}
