package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.device.ControlPlaneFactory
import io.bluetrace.shared.device.DeviceProfile
import io.bluetrace.shared.device.FirmwareUpdateFactory
import io.bluetrace.shared.device.ScanSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.ProtocolProfile

/**
 * S7(B2A 协议)设备档案(识别面, 设计 V2 §3.1). 识别覆写委托 [B2aDetect.matchesAdvertisement]
 * (广播含 FFE0; 名称非判据; 128-bit/大小写容忍); GATT 通道对照 B2aDetect 常量——
 * service FFE0 / notify FFE2(表->App) / write FFE1(App->表, Write Without Response).
 *
 * dataPlane=null: S7 采集协议未冻结(M7), 现网无解码 profile——冻结前真实字节只落 raw HEX(source of truth).
 * controlPlane=[S7ControlPlaneFactory](W3 填充: 包装 S7Console 成六通用面 + S7VendorOps);
 * firmwareUpdate=[S7FirmwareUpdateFactory](W4 填充: 包 OtaProvisioner/S7OtaSession 编排链成通用策略).
 */
class S7DeviceProfile : DeviceProfile {
    override val profileId: String = PROFILE_S7

    override val scanSpec: ScanSpec = ScanSpec(listOf(B2aDetect.SERVICE_16))

    override val gattSpec: GattSpec = GattSpec(
        serviceUuid16 = B2aDetect.SERVICE_16, // FFE0
        notifyChar16s = listOf(B2aDetect.TX_16), // FFE2 表->App Notify
        writeChar16 = B2aDetect.RX_16, // FFE1 App->表 Write Without Response
        writeWithResponse = false,
    )

    override val kind: DeviceKind = DeviceKind.DUT

    /** 覆写: 复用 [B2aDetect.matchesAdvertisement](广播含 FFE0; 名称非判据; 128-bit/大小写容忍). */
    override fun matches(device: ScannedDevice): Boolean = B2aDetect.matchesAdvertisement(device)

    override val dataPlane: ProtocolProfile? = null
    override val controlPlane: ControlPlaneFactory = S7ControlPlaneFactory()
    override val firmwareUpdate: FirmwareUpdateFactory = S7FirmwareUpdateFactory()
}
