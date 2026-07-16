package io.bluetrace.viewmodel

import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.device.ControlPlaneFactory
import io.bluetrace.shared.device.DeviceProfile
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.device.FirmwareUpdateFactory
import io.bluetrace.shared.device.FirmwareUpdateStrategy
import io.bluetrace.shared.device.ScanSpec
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.ProtocolProfile
import io.bluetrace.shared.b2a.B2aDeviceProfile
import io.bluetrace.shared.b2a.B2aFirmwareUpdateFactory
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [supportsOtaTool] 判据测试: 锚**升级能力工厂类型**(B2aFirmwareUpdateFactory)——
 * 不锚产品名(名称会变), 也不只锚服务 UUID(同 FFE0 但 firmwareUpdate=null/异构策略的
 * 未来档案不具备本工具执行链的编排语义, 必须拒绝).
 */
class OtaToolSupportTest {

    private fun dev(adv: List<String>) =
        ScannedDevice("d1", "X", "00:11:22:33:44:55", -50, DeviceKind.DUT, null, adv)

    /** 同 FFE0 服务但升级面可注入的假档案(模拟未来同服务异构/无升级面的设备). */
    private class SameServiceProfile(
        override val firmwareUpdate: FirmwareUpdateFactory?,
    ) : DeviceProfile {
        override val profileId = "Future.SameService"
        override val scanSpec = ScanSpec(listOf("FFE0"))
        override val gattSpec = GattSpec("FFE0", listOf("FFE2"), "FFE1")
        override val kind = DeviceKind.DUT
        override val dataPlane: ProtocolProfile? = null
        override val controlPlane: ControlPlaneFactory? = null
    }

    private object AlienFactory : FirmwareUpdateFactory {
        override fun create(
            ble: io.bluetrace.shared.ble.BleClient,
            device: ScannedDevice,
            scope: CoroutineScope,
            clock: EpochClock,
            zone: TimeZoneProvider,
            abortScope: CoroutineScope,
            reconnectScanMs: Long,
            onLog: (String) -> Unit,
        ): FirmwareUpdateStrategy = throw UnsupportedOperationException("test marker only")
    }

    @Test
    fun s7Profile_supported() {
        val catalog = DeviceProfileCatalog(listOf(B2aDeviceProfile()))
        assertTrue("S7 档案(挂 S7 工厂)应放行", catalog.supportsOtaTool(dev(adv = listOf("FFE0"))))
    }

    @Test
    fun sameServiceUuid_butNoFirmwareFacet_rejected() {
        val catalog = DeviceProfileCatalog(listOf(SameServiceProfile(firmwareUpdate = null)))
        assertFalse("同 FFE0 但无升级面: 必须拒绝", catalog.supportsOtaTool(dev(adv = listOf("FFE0"))))
    }

    @Test
    fun sameServiceUuid_butAlienStrategy_rejected() {
        val catalog = DeviceProfileCatalog(listOf(SameServiceProfile(firmwareUpdate = AlienFactory)))
        assertFalse("同 FFE0 但异构升级策略: 必须拒绝", catalog.supportsOtaTool(dev(adv = listOf("FFE0"))))
    }

    @Test
    fun unrecognizedDevice_rejected() {
        val catalog = DeviceProfileCatalog(listOf(B2aDeviceProfile()))
        assertFalse("识别不到档案: 拒绝", catalog.supportsOtaTool(dev(adv = listOf("9999"))))
    }
}
