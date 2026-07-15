package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 已连接登记: 扁平限额(DUT≤3 + 参考≤1, D-V4-3) + B2 事件驱动清退(linkState 断连自动移除).  */
class ConnectionRegistryTest {

    /** 只喂 linkState 的假客户端(登记表只依赖这一个成员).  */
    private class FakeLinkBleClient : BleClient {
        val links = HashMap<String, MutableStateFlow<LinkState>>()
        fun link(id: String): MutableStateFlow<LinkState> =
            links.getOrPut(id) { MutableStateFlow(LinkState.CONNECTED) }

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {}
        override suspend fun disconnect(deviceId: String) {}
        override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)
        override fun notifications(deviceId: String): Flow<BleNotification> = emptyFlow()
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {}
    }

    private fun dut(i: Int) = ScannedDevice("dut$i", "BT-DUT-$i", "C4:7B:8D:0A:00:0$i", -50, DeviceKind.DUT)
    private fun ref() = ScannedDevice("ref", "Polar H10", "A0:9E:1A:55:0D:10", -60, DeviceKind.REFERENCE, PROFILE_HRS)

    @Test
    fun dutLimitedToThree_referenceToOne() = runTest {
        val r = ConnectionRegistry(FakeLinkBleClient(), backgroundScope)
        repeat(3) { r.add(dut(it)) }
        assertEquals(3, r.dutCount())
        assertFalse(r.canConnect(DeviceKind.DUT), "4th DUT must be blocked")
        assertTrue(r.canConnect(DeviceKind.REFERENCE), "reference still allowed")

        r.add(ref())
        assertEquals(1, r.referenceCount())
        assertFalse(r.canConnect(DeviceKind.REFERENCE), "2nd reference must be blocked")
        assertEquals(4, r.count())
    }

    @Test
    fun removingFreesSlot() = runTest {
        val r = ConnectionRegistry(FakeLinkBleClient(), backgroundScope)
        repeat(3) { r.add(dut(it)) }
        assertFalse(r.canConnect(DeviceKind.DUT))
        r.remove("dut0")
        assertEquals(2, r.dutCount())
        assertTrue(r.canConnect(DeviceKind.DUT))
        assertFalse(r.isConnected("dut0"))
        assertTrue(r.isConnected("dut1"))
    }

    @Test
    fun addIsIdempotent() = runTest {
        val r = ConnectionRegistry(FakeLinkBleClient(), backgroundScope)
        r.add(dut(1)); r.add(dut(1))
        assertEquals(1, r.count())
    }

    @Test
    fun passiveDisconnectAutoEvicts() = runTest {
        val ble = FakeLinkBleClient()
        val r = ConnectionRegistry(ble, backgroundScope)
        r.add(dut(1)); r.add(dut(2))
        runCurrent()
        // 被动断连(超距/没电): 无人调 remove, 登记表订阅 linkState 自动清退
        ble.link("dut1").value = LinkState.DISCONNECTED
        runCurrent()
        assertFalse(r.isConnected("dut1"))
        assertTrue(r.isConnected("dut2"))
        assertEquals(1, r.count())
    }

    @Test
    fun reconnectingKeepsMembership() = runTest {
        val ble = FakeLinkBleClient()
        val r = ConnectionRegistry(ble, backgroundScope)
        r.add(dut(1))
        runCurrent()
        // 协议栈自动重连中: 仍在册(设备卡琥珀点), 不算断开
        ble.link("dut1").value = LinkState.RECONNECTING
        runCurrent()
        assertTrue(r.isConnected("dut1"))
        // 重连成功回 CONNECTED 依旧在册
        ble.link("dut1").value = LinkState.CONNECTED
        runCurrent()
        assertTrue(r.isConnected("dut1"))
        // 最终放弃 → DISCONNECTED 才清退
        ble.link("dut1").value = LinkState.DISCONNECTED
        runCurrent()
        assertFalse(r.isConnected("dut1"))
    }

    @Test
    fun reAddAfterEvictReusesWatcher() = runTest {
        val ble = FakeLinkBleClient()
        val r = ConnectionRegistry(ble, backgroundScope)
        r.add(dut(1))
        runCurrent()
        ble.link("dut1").value = LinkState.DISCONNECTED
        runCurrent()
        assertFalse(r.isConnected("dut1"))
        // 重连后再登记: 监听常驻复用, 再断依旧能清退
        ble.link("dut1").value = LinkState.CONNECTED
        r.add(dut(1))
        runCurrent()
        assertTrue(r.isConnected("dut1"))
        ble.link("dut1").value = LinkState.DISCONNECTED
        runCurrent()
        assertFalse(r.isConnected("dut1"))
    }
}
