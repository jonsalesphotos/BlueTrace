package io.bluetrace.shared.ble.mock

import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MockBleClientTest {

    private fun dut() = ScannedDevice("dut-0427", "BT-DUT-0427", "C4:7B:8D:0A:04:27", -52, DeviceKind.DUT)
    private fun s7() = ScannedDevice(
        "s7-fcc4", "SKG WATCH S7-FCC4", "AA:BB:CC:DD:FC:C4", -58, DeviceKind.DUT, PROFILE_S7,
        advertisedServices = listOf("FFE0"),
    )

    @Test
    fun scan_progressivelyDiscovers_dutAndReference() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        val seen = mutableListOf<List<ScannedDevice>>()
        backgroundScope.launch { mock.scan().collect { seen.add(it) } }
        advanceTimeBy(3000)
        runCurrent()
        val last = seen.last()
        assertTrue(last.any { it.kind == DeviceKind.DUT }, "should discover a DUT")
        assertTrue(last.any { it.kind == DeviceKind.REFERENCE && it.profileId == PROFILE_HRS }, "should discover HRS reference")
        assertEquals(6, last.size) // 4 DUT + 1 HRS 参考 + 1 S7 手表
    }

    @Test
    fun emptyScanMode_yieldsNoDevices() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.nextScanEmpty = true
        val seen = mutableListOf<List<ScannedDevice>>()
        backgroundScope.launch { mock.scan().collect { seen.add(it) } }
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(seen.all { it.isEmpty() })
    }

    @Test
    fun connect_thenNotifications_flow() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope, emitIntervalMs = 100, connectDelayMs = 600)
        val notifs = mutableListOf<BleNotification>()
        backgroundScope.launch { mock.notifications("dut-0427").collect { notifs.add(it) } }

        // 未连接前不产数据
        advanceTimeBy(500); runCurrent()
        assertTrue(notifs.isEmpty())

        backgroundScope.launch { mock.connect(dut()) }
        advanceTimeBy(700); runCurrent()
        assertEquals(LinkState.CONNECTED, mock.linkState("dut-0427").value)

        advanceTimeBy(500); runCurrent()
        assertTrue(notifs.isNotEmpty(), "connected device should stream notifications")
        assertTrue(notifs.all { it.deviceId == "dut-0427" })
        assertEquals(0x7E, notifs.first().rawBytes[0].toInt() and 0xFF)
        // Mock 线数据协议单通道: characteristicId 留空, 解码走 DeviceParserHost 单通道兜底路由
        assertTrue(notifs.all { it.characteristicId == null }, "data-plane notify carries no characteristicId")
    }

    @Test
    fun injectDisconnect_reconnectsAutomatically() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope, connectDelayMs = 600, reconnectDelayMs = 3000)
        backgroundScope.launch { mock.connect(dut()) }
        advanceTimeBy(700); runCurrent()
        assertEquals(LinkState.CONNECTED, mock.linkState("dut-0427").value)

        mock.injectDisconnect("dut-0427")
        runCurrent()
        assertEquals(LinkState.RECONNECTING, mock.linkState("dut-0427").value)

        advanceTimeBy(3100); runCurrent()
        assertEquals(LinkState.CONNECTED, mock.linkState("dut-0427").value)
    }

    @Test
    fun s7Notifications_carryB2aTxCharacteristicId() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope, connectDelayMs = 600)
        val notifs = mutableListOf<BleNotification>()
        backgroundScope.launch { mock.notifications("s7-fcc4").collect { notifs.add(it) } }

        backgroundScope.launch { mock.connect(s7()) }
        advanceTimeBy(700); runCurrent()
        assertEquals(LinkState.CONNECTED, mock.linkState("s7-fcc4").value)

        // 30s 周期心跳(连接态)走 B2A 表->App Notify 通道(TX=FFE2), 与真机 AndroidBleClient 口径一致
        advanceTimeBy(30_100); runCurrent()
        assertTrue(notifs.isNotEmpty(), "connected S7 watch should emit heartbeat notify")
        assertTrue(notifs.all { it.characteristicId == "FFE2" }, "S7 notify carries B2A TX characteristicId FFE2")
    }
}
