package io.bluetrace.shared.device

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.ble.mock.MockBleClient
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.S7_TEST_MAC
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.ProtocolProfile
import io.bluetrace.shared.s7.S7DeviceControl
import io.bluetrace.shared.s7.S7DeviceProfile
import io.bluetrace.shared.s7.S7VendorOps
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * DeviceSessionManager 生命周期测试: acquire(gattSpec 激活/复用/identify 空/连接失败/confirm 失败),
 * release(关控制面 + 断链 + 清缓存).fake BleClient/Profile 精确控场 + 一条真 S7Profile x MockBleClient 集成.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSessionManagerTest {

    // ---- fakes ----

    private class FakeControl : DeviceControl {
        var closed = false
        override val info: DeviceInfoOps? = null
        override val battery: BatteryOps? = null
        override val timeSync: TimeSyncOps? = null
        override val logs: LogOps? = null
        override val power: PowerOps? = null
        override val vendor: VendorOps? = null
        override fun close() {
            closed = true
        }
    }

    private class FakeFactory : ControlPlaneFactory {
        val created = mutableListOf<FakeControl>()
        override fun create(ble: BleClient, deviceId: String, scope: CoroutineScope, clock: EpochClock, zone: TimeZoneProvider): DeviceControl =
            FakeControl().also { created.add(it) }
    }

    /** 部分能力控制面(异构设备 W6 形态: 有 info/power, 缺 timeSync/logs/vendor)——W5 UI 按分面 null 显隐的数据源. */
    private class PartialControl : DeviceControl {
        override val info: DeviceInfoOps? = object : DeviceInfoOps {
            override suspend fun get() = io.bluetrace.shared.device.DeviceCmdInfo("v1", null, "MOCK")
        }
        override val battery: BatteryOps? = object : BatteryOps { override suspend fun percent() = 50 }
        override val timeSync: TimeSyncOps? = null // 缺对时
        override val logs: LogOps? = null // 缺日志(W6 判据: 缺一项能力, UI 该块不渲染)
        override val power: PowerOps? = object : PowerOps {
            override suspend fun reboot() = true
            override suspend fun powerOff() = true
        }
        override val vendor: VendorOps? = null // 非 S7: UI S7 专属块整块不渲染
        override fun close() {}
    }

    private class PartialFactory : ControlPlaneFactory {
        override fun create(ble: BleClient, deviceId: String, scope: CoroutineScope, clock: EpochClock, zone: TimeZoneProvider): DeviceControl =
            PartialControl()
    }

    private class FakeProfile(
        override val profileId: String,
        matchShort: String,
        override val controlPlane: ControlPlaneFactory?,
    ) : DeviceProfile {
        override val scanSpec = ScanSpec(listOf(matchShort))
        override val gattSpec = GattSpec(matchShort, listOf("FF01"), "FF02")
        override val kind = DeviceKind.DUT
        override val dataPlane: ProtocolProfile? = null
        override val firmwareUpdate: FirmwareUpdateFactory? = null
    }

    private class FakeBle : BleClient {
        private val links = HashMap<String, MutableStateFlow<LinkState>>()
        var connectCount = 0
        var lastSpec: GattSpec? = null
        val disconnectCalls = mutableListOf<String>()
        var services: List<String> = emptyList()
        /** connect 收敛到的状态(默认成功 CONNECTED). */
        var connectTo: LinkState = LinkState.CONNECTED

        private fun link(id: String) = links.getOrPut(id) { MutableStateFlow(LinkState.DISCONNECTED) }
        override fun scan() = flowOf(emptyList<ScannedDevice>())
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {
            connectCount++
            lastSpec = spec
            link(device.id).value = connectTo
        }
        override suspend fun disconnect(deviceId: String) {
            disconnectCalls.add(deviceId)
            link(deviceId).value = LinkState.DISCONNECTED
        }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)
        override fun notifications(deviceId: String) = emptyFlow<BleNotification>()
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {}
        override fun discoveredService16s(deviceId: String): List<String> = services
    }

    private fun dev(id: String = "d1", adv: List<String> = listOf("FFE0")) =
        ScannedDevice(id, "X", "00:11:22:33:44:55", -50, DeviceKind.DUT, null, adv)

    private fun TestScope.manager(catalog: DeviceProfileCatalog, ble: BleClient) =
        DeviceSessionManager(catalog, ble, backgroundScope, virtualClock { testScheduler.currentTime }, TestZone())

    // ---- acquire ----

    @Test
    fun acquire_success_connectsWithGattSpec_andCaches() = runTest {
        val factory = FakeFactory()
        val profile = FakeProfile(PROFILE_S7, "FFE0", factory)
        val ble = FakeBle().apply { services = listOf("FFE0") }
        val m = manager(DeviceProfileCatalog(listOf(profile)), ble)

        val session = m.acquire(dev())
        assertNotNull(session)
        assertEquals(PROFILE_S7, session.profile.profileId)
        assertNotNull(session.control)
        assertEquals(profile.gattSpec, ble.lastSpec) // gattSpec 激活: connect 收到 profile.gattSpec
        assertEquals(1, ble.connectCount)
        assertSame(session, m.get("d1"))
    }

    @Test
    fun acquire_reusesCachedSession_noSecondConnect() = runTest {
        val ble = FakeBle().apply { services = listOf("FFE0") }
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile(PROFILE_S7, "FFE0", FakeFactory()))), ble)
        val s1 = m.acquire(dev())
        val s2 = m.acquire(dev())
        assertSame(s1, s2)
        assertEquals(1, ble.connectCount)
    }

    @Test
    fun acquire_identifyMiss_returnsNull_noConnect() = runTest {
        val ble = FakeBle()
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile(PROFILE_S7, "FFE0", FakeFactory()))), ble)
        assertNull(m.acquire(dev(adv = listOf("9999")))) // 不匹配 FFE0
        assertEquals(0, ble.connectCount)
    }

    @Test
    fun acquire_connectFails_returnsNull() = runTest {
        val ble = FakeBle().apply { connectTo = LinkState.DISCONNECTED }
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile(PROFILE_S7, "FFE0", FakeFactory()))), ble)
        assertNull(m.acquire(dev()))
        assertNull(m.get("d1"))
    }

    @Test
    fun acquire_confirmFails_disconnectsAndReturnsNull() = runTest {
        val ble = FakeBle().apply { services = listOf("9999") } // 服务表不含 FFE0 -> confirm false
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile(PROFILE_S7, "FFE0", FakeFactory()))), ble)
        assertNull(m.acquire(dev()))
        assertTrue(ble.disconnectCalls.contains("d1"))
        assertNull(m.get("d1"))
    }

    @Test
    fun acquire_nullControlPlane_sessionHasNullControl() = runTest {
        val ble = FakeBle().apply { services = listOf("FFE0") }
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile("no-ctrl", "FFE0", null))), ble)
        val session = m.acquire(dev())
        assertNotNull(session)
        assertNull(session.control) // profile.controlPlane=null -> control 空(分面模型: 无能力)
    }

    @Test
    fun acquire_partialFacets_sessionExposesFacetPresence() = runTest {
        // W5 UI 按分面 null 与否显隐的宿主消费路径: 会话透出的 control 分面存在性即 UI 显隐依据.
        // 异构设备(W6 形态): info/battery/power 有 -> 对应块渲染; timeSync/logs/vendor 缺 -> 该块隐藏.
        val ble = FakeBle().apply { services = listOf("FFE0") }
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile("partial", "FFE0", PartialFactory()))), ble)
        val session = m.acquire(dev())
        assertNotNull(session)
        val ctl = session.control
        assertNotNull(ctl)
        assertNotNull(ctl.info) // 有版本面 -> UI 版本块显示
        assertNotNull(ctl.battery)
        assertNotNull(ctl.power)
        assertNull(ctl.timeSync) // 缺对时面 -> UI 对时块隐藏
        assertNull(ctl.logs) // 缺日志面 -> UI 日志块隐藏(W6 判据)
        assertNull(ctl.vendor) // 非 S7 vendor -> UI S7 专属块整块隐藏
    }

    // ---- release ----

    @Test
    fun release_closesControl_disconnects_clearsCache() = runTest {
        val factory = FakeFactory()
        val ble = FakeBle().apply { services = listOf("FFE0") }
        val m = manager(DeviceProfileCatalog(listOf(FakeProfile(PROFILE_S7, "FFE0", factory))), ble)
        m.acquire(dev())
        m.release("d1")
        assertTrue(factory.created.single().closed, "control.close() 应被调用")
        assertTrue(ble.disconnectCalls.contains("d1"))
        assertNull(m.get("d1"))
    }

    // ---- 集成: 真 S7Profile x MockBleClient(W3 裁定 a 的 Mock 模式等价) ----

    @Test
    fun acquire_realS7Profile_overMock_yieldsS7Control() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val mock = MockBleClient(clock, backgroundScope)
        val catalog = DeviceProfileCatalog(listOf(S7DeviceProfile(), MockDeviceProfile()))
        val m = DeviceSessionManager(catalog, mock, backgroundScope, clock, TestZone())
        // roster 的 s7-fcc4(广播含 FFE0) -> S7DeviceProfile 命中 -> confirm(discoveredService16s 含 FFE0) 通过
        val s7 = ScannedDevice("s7-fcc4", "SKG WATCH S7-FCC4", S7_TEST_MAC, -58, DeviceKind.DUT, PROFILE_S7, listOf("180A", "FFE0", "FFE1", "FFE2", "FFEB"))
        val session = m.acquire(s7)
        assertNotNull(session)
        assertEquals(PROFILE_S7, session.profile.profileId)
        assertTrue(session.control is S7DeviceControl, "S7 设备应拿到 S7DeviceControl")
        assertTrue(session.control?.vendor is S7VendorOps, "vendor 面经通用 DeviceControl 可向下转型 S7VendorOps(W5 UI 路径)")
        m.release("s7-fcc4")
    }
}
