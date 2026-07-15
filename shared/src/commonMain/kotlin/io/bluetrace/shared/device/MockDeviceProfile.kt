package io.bluetrace.shared.device

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.MockBleProfile
import io.bluetrace.shared.protocol.registry.ProtocolProfile

/**
 * Mock 线协议设备档案(兜底, 对照 [MockBleProfile] 的识别口径). [matches] 恒真——只注册进 Mock
 * 后端目录, 作为唯一档案兜住 roster 全部设备. 无法从广播区分 DUT/REFERENCE, kind=DUT(plain Mock
 * DUT 现状); roster 预置 kind/profileId 的设备(Polar H10=REFERENCE/PROFILE_HRS, S7 Mock=DUT/PROFILE_S7)
 * 由 [DeviceProfileCatalog.annotate] 的"已带身份不覆盖"守卫保住, 不被本 catch-all 压平.
 *
 * dataPlane=现有 [MockBleProfile](原样挂入). gattSpec 为占位(Mock 无真实 GATT, 连接走
 * connect(spec=null) 定时器自驱; W3 接线前不消费).
 */
class MockDeviceProfile : DeviceProfile {
    override val profileId: String = MockBleProfile.ID

    override val scanSpec: ScanSpec = ScanSpec(emptyList()) // 广播无关: matches 恒真兜底

    override val gattSpec: GattSpec = GattSpec(
        serviceUuid16 = "FEED", // 占位(对照 MockBleProfile.CHANNEL 的 0000FEED 段); Mock 不做真实发现
        notifyChar16s = listOf("FEED"),
        writeChar16 = null,
    )

    override val kind: DeviceKind = DeviceKind.DUT

    /** 覆写: 对照 [MockBleProfile.matches] 恒真(catch-all 兜底). */
    override fun matches(device: ScannedDevice): Boolean = true

    override val dataPlane: ProtocolProfile = MockBleProfile()
    override val controlPlane: ControlPlaneFactory? = null
    override val firmwareUpdate: FirmwareUpdateFactory? = null
}
