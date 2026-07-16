package io.bluetrace.shared.zx

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.ble.mock.MockBleClient
import io.bluetrace.shared.device.DeviceControl
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.device.DeviceSessionManager
import io.bluetrace.shared.device.FwPackage
import io.bluetrace.shared.device.FwUpdatePhase
import io.bluetrace.shared.device.FwUpdateProgress
import io.bluetrace.shared.device.FwUpdateResult
import io.bluetrace.shared.device.MockDeviceProfile
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_B2A
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.b2a.B2aDeviceProfile
import io.bluetrace.shared.b2a.B2aVendorOps
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W6 异构验收判据测试(设计 V2 §4 W6 行): 新增 ZX(第二协议, `shared/.../zx` 包)只加协议包 + 一行
 * catalog 注册(AppModule) + 一条 roster 数据(MockBleClient), 框架(device/protocol.registry/BLE
 * 客户端/UI)零改动. 覆盖:
 * 1. 识别: catalog=[S7, ZX, Mock] 下广播互不误伤;
 * 2. 会话 + 分面: 真 MockBleClient 走 DeviceSessionManager, logs/vendor 缺失 + 通用四面端到端读回;
 * 3. 升级策略异构: ZX"原地生效"重连行为(不断链不回连)与 S7"自复位回连"相反;
 * 4. 显隐依据: 分面 null 性直接驱动 UI 布尔(对照 [io.bluetrace.viewmodel.DeviceConsoleViewModel]
 *    的 hasLogs/hasVendorS7 逻辑, 纯逻辑断言, 不测 Android UI).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Protocol2AcceptanceTest {

    private fun dev(
        id: String = "d",
        adv: List<String> = emptyList(),
    ) = ScannedDevice(id, "X", "00:11:22:33:44:55", -50, DeviceKind.DUT, null, adv)

    // ---- 1. 识别: catalog=[S7, ZX, Mock] 广播互不误伤 ----

    @Test
    fun identify_ZX_and_S7_fromSameCatalog_noCrossContamination() {
        val catalog = DeviceProfileCatalog(listOf(B2aDeviceProfile(), ZxDeviceProfile(), MockDeviceProfile()))

        val zxHit = catalog.identify(dev(adv = listOf("AA00")))
        assertIs<ZxDeviceProfile>(zxHit) // AA00 广播应命中 ZxDeviceProfile
        assertEquals(PROFILE_ZX, zxHit.profileId)

        val s7Hit = catalog.identify(dev(adv = listOf("180A", "FFE0")))
        assertIs<B2aDeviceProfile>(s7Hit) // FFE0 广播应仍命中 B2aDeviceProfile, 不被 ZX 抢先/误伤
        assertEquals(PROFILE_B2A, s7Hit.profileId)
    }

    // ---- 2. 会话 + 分面: 真 MockBleClient(roster 含 zx-9001) 走 DeviceSessionManager ----

    @Test
    fun acquire_ZX_overMockBleClient_missingLogsAndVendor_genericFacetsLiveEndToEnd() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val mock = MockBleClient(clock, backgroundScope)
        val catalog = DeviceProfileCatalog(listOf(B2aDeviceProfile(), ZxDeviceProfile(), MockDeviceProfile()))
        val m = DeviceSessionManager(catalog, mock, backgroundScope, clock, TestZone())

        // roster 的 zx-9001(广播含 AA00) -> ZxDeviceProfile 命中 -> confirm(discoveredService16s 含 AA00) 通过
        val zx = ScannedDevice("zx-9001", "ZX WATCH ZX-9001", ZX_TEST_MAC, -55, DeviceKind.DUT, PROFILE_ZX, listOf("AA00"))
        val session = m.acquire(zx)

        assertNotNull(session)
        assertEquals(PROFILE_ZX, session.profile.profileId)
        val ctl = session.control
        assertNotNull(ctl)
        assertNull(ctl.logs, "ZX 故意缺日志面(设计文档 Open Questions #4 判据)")
        assertNull(ctl.vendor, "ZX 无厂商扩展面, 走通用分面块渲染路径")
        assertNotNull(ctl.info)
        assertNotNull(ctl.battery)
        assertNotNull(ctl.timeSync)
        assertNotNull(ctl.power)

        // 通用分面读路径端到端(首个非 S7 协议验证, W5 交接备注指出的判据点): acquire -> 分面 -> 假数据读回
        assertEquals("zx-2.0", ctl.info?.get()?.swVer)
        assertEquals(88, ctl.battery?.percent())

        m.release("zx-9001")
    }

    // ---- 3. 升级策略异构: ZX"原地生效"与 S7"自复位回连"相反 ----

    private class FakeBle : BleClient {
        var connectCount = 0
        val disconnectCalls = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String?>>()
        val link = MutableStateFlow(LinkState.CONNECTED)
        override fun scan(): Flow<List<ScannedDevice>> = flowOf(emptyList())
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) { connectCount++ }
        override suspend fun disconnect(deviceId: String) { disconnectCalls.add(deviceId) }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link
        override fun notifications(deviceId: String): Flow<BleNotification> = emptyFlow()
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
            writes.add(deviceId to char16)
        }
    }

    private fun zxDevice() = ScannedDevice("zx-9001", "ZX WATCH ZX-9001", ZX_TEST_MAC, -55, DeviceKind.DUT, PROFILE_ZX, listOf("AA00"))

    @Test
    fun firmwareUpdate_ZX_succeedsInPlace_noConnectNoDisconnect() = runTest {
        val ble = FakeBle()
        val strategy = ZxDeviceProfile().firmwareUpdate.create(
            ble, zxDevice(), backgroundScope, virtualClock { testScheduler.currentTime }, TestZone(),
            abortScope = backgroundScope, reconnectScanMs = 1_000, onLog = {},
        )
        val progress = mutableListOf<FwUpdateProgress>()

        val result = strategy.run(ZxPackage()) { progress.add(it) }

        val ok = assertIs<FwUpdateResult.Success>(result)
        assertEquals("zx-2.1", ok.versionAfter)
        assertTrue(progress.any { it.phase == FwUpdatePhase.Transferring }, "应有传输阶段进度回调: $progress")
        assertEquals(FwUpdatePhase.Done, progress.last().phase, "末尾应为 Done: $progress")
        // 核心判据: 全程无 connect/disconnect 调用, link 恒 CONNECTED——
        // 证明"自复位回连"是 S7 策略私有假设, 不是 FirmwareUpdateStrategy 框架契约.
        assertEquals(0, ble.connectCount, "ZX 升级不应触发任何 connect: ${ble.connectCount}")
        assertTrue(ble.disconnectCalls.isEmpty(), "ZX 升级不应断链: ${ble.disconnectCalls}")
        assertEquals(LinkState.CONNECTED, ble.link.value, "link 应恒 CONNECTED(未被任何 disconnect 拉低)")
    }

    @Test
    fun firmwareUpdate_ZX_wrongPackageType_failsFast_noBleTouch() = runTest {
        val ble = FakeBle()
        val strategy = ZxDeviceProfile().firmwareUpdate.create(
            ble, zxDevice(), backgroundScope, virtualClock { testScheduler.currentTime }, TestZone(),
            abortScope = backgroundScope, reconnectScanMs = 1_000, onLog = {},
        )

        val result = strategy.run(object : FwPackage {})

        val failed = assertIs<FwUpdateResult.Failed>(result)
        assertEquals(0, failed.percentAtFailure)
        assertEquals(0, ble.connectCount)
        assertTrue(ble.writes.isEmpty(), "包类型不符应 fail fast, 不碰 BLE: ${ble.writes}")
    }

    // ---- 4. 显隐依据: 对照 DeviceConsoleViewModel 的 hasLogs/hasVendorS7 纯逻辑断言 ----

    @Test
    fun facetNullness_drivesUiVisibilityBooleans_sameAsConsoleViewModelLogic() {
        // 显式声明为 DeviceControl 接口类型(而非具体 ZxDeviceControl): 与真实消费方
        // DeviceSession.control: DeviceControl? 同一静态类型, 各分面按接口的可空签名读取——
        // 否则编译器按具体类下的非空返回类型判定 "!= null" 恒真, 掩盖了本测试要证明的运行时 null 性.
        val ctl: DeviceControl = ZxDeviceControl()

        // 逐条复刻 DeviceConsoleViewModel.wire() 的分面显隐布尔计算(仅纯逻辑, 不涉 Android/Compose):
        val hasInfo = ctl.info != null
        val hasBattery = ctl.battery != null
        val hasTimeSync = ctl.timeSync != null
        val hasLogs = ctl.logs != null
        val hasPower = ctl.power != null
        val hasVendorS7 = (ctl.vendor as? B2aVendorOps) != null

        assertTrue(hasInfo, "版本块应显示")
        assertTrue(hasBattery, "电量块应显示")
        assertTrue(hasTimeSync, "对时块应显示")
        assertFalse(hasLogs, "W6 判据: 日志块应因 logs==null 自动隐藏")
        assertTrue(hasPower, "电源块应显示")
        assertFalse(hasVendorS7, "非 S7 vendor: S7 专属块应整块隐藏")
    }

    private companion object {
        const val ZX_TEST_MAC = "66:77:88:99:00:01"
    }
}
