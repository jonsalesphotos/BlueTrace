package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OtaProvisioner] 端到端编排测试：下载 → 设备自复位断链 → 重连 → 读当前版本 → Reconnected(version?)。
 * 不做版本校验(OTA 包无版本信息)，仅读取显示当前版本；读不到 = Reconnected(null)。
 * Mock 手表 `otaRebootAfterComplete=true` 在末 STOP 后随 ack 断链，模拟设备自复位。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class S7OtaProvisionerTest {

    private val device = ScannedDevice(
        id = "s7-fcc4", name = "SKG WATCH S7-FCC4", address = "71:61:48:19:FC:C4",
        rssi = -50, kind = DeviceKind.DUT,
    )

    /** 直连 Mock 手表 + 可控重连的 BleClient：write→watch.handle→异步回 notify；末 STOP 后按注入断链。 */
    private class FakeProvBle(
        val watch: S7MockWatch,
        private val clock: EpochClock,
        private val scope: CoroutineScope,
        /** connect() 是否置 CONNECTED（false = 模拟设备开不回来，测重连失败）。 */
        var connectSucceeds: Boolean = true,
        private val mtu: Int = 247,
    ) : BleClient {
        val link = MutableStateFlow(LinkState.CONNECTED)
        var connectCalls = 0
            private set
        private val inbound = MutableSharedFlow<BleNotification>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice) {
            connectCalls++
            if (connectSucceeds) link.value = LinkState.CONNECTED
        }
        override suspend fun disconnect(deviceId: String) { link.value = LinkState.DISCONNECTED }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link
        override fun negotiatedMtu(deviceId: String): Int = mtu
        override fun notifications(deviceId: String): Flow<BleNotification> = inbound
        override suspend fun write(deviceId: String, bytes: ByteArray) {
            if (link.value != LinkState.CONNECTED) return
            val reply = watch.handle(bytes)
            scope.launch {
                for (f in reply.frames) {
                    delay(1) // 保证 collector 先订阅
                    inbound.emit(BleNotification(deviceId, clock.nowMs(), f))
                }
                if (reply.disconnectAfter) {
                    delay(1)
                    link.value = LinkState.DISCONNECTED // 末 STOP 后设备自复位断链
                }
            }
        }
    }

    private fun pkg(vararg files: Pair<String, Int>) = OtaPackage(
        files = files.map { (name, size) -> OtaFile(name, ByteArray(size) { (it * 31 + name.length).toByte() }, S7FileTrans.FT_FW) },
    )

    private fun kotlinx.coroutines.test.TestScope.newSession(ble: BleClient): S7OtaSession =
        S7OtaSession(ble, "s7-fcc4", backgroundScope, virtualClock { testScheduler.currentTime })

    @Test
    fun provisionAndReconnect_reboots_reconnects_readsCurrentVersion() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }).apply { otaRebootAfterComplete = true }
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val prov = OtaProvisioner(newSession(ble), ble, device, readVersion = { "collect-1.0" })
        val phases = mutableListOf<OtaPhase>()

        val result = prov.provisionAndReconnect(pkg("fw.dat" to 6000), onPhase = { phases.add(it) })

        val v = assertIs<OtaResult.Reconnected>(result)
        assertEquals("collect-1.0", v.currentVersion)
        assertEquals(LinkState.CONNECTED, ble.link.value, "应已重连回 CONNECTED")
        assertTrue(ble.connectCalls >= 1, "应至少 connect 一次(重连)")
        assertTrue(
            phases.containsAll(listOf(OtaPhase.Downloading, OtaPhase.WaitingReboot, OtaPhase.Reconnecting, OtaPhase.ReadingVersion, OtaPhase.Done)),
            "阶段应走全: $phases",
        )
    }

    @Test
    fun provisionAndReconnect_failsWhenReconnectNeverSucceeds() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }).apply { otaRebootAfterComplete = true }
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope, connectSucceeds = false)
        val prov = OtaProvisioner(
            newSession(ble), ble, device, readVersion = { "x" },
            rebootWaitMs = 5_000, reconnectTimeoutMs = 2_000, reconnectAttempts = 3,
        )
        val result = prov.provisionAndReconnect(pkg("fw.dat" to 3000))
        assertIs<OtaFailure.ReconnectFailed>(assertIs<OtaResult.Failed>(result).reason)
        assertEquals(3, ble.connectCalls, "应尝试满 reconnectAttempts 次")
    }

    /** 版本读不到不算失败：重连成功即 Reconnected，currentVersion=null（UI 显示"未知"）。 */
    @Test
    fun provisionAndReconnect_versionUnreadable_yieldsReconnectedNull() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }).apply { otaRebootAfterComplete = true }
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val prov = OtaProvisioner(newSession(ble), ble, device, readVersion = { null }) // 恒读不到
        val result = prov.provisionAndReconnect(pkg("fw.dat" to 3000))
        assertEquals(null, assertIs<OtaResult.Reconnected>(result).currentVersion)
    }

    /**
     * A 修复守护：生产 readVersion(=S7Console.getDeviceInfo) 超时是 **抛异常** 非返 null →
     * 本层须容错(转 null 重试)而非抛穿 → 终态 Reconnected(null)、不异常穿透。
     */
    @Test
    fun provisionAndReconnect_versionReadThrows_yieldsReconnectedNull_notPropagated() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }).apply { otaRebootAfterComplete = true }
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        var reads = 0
        val prov = OtaProvisioner(
            newSession(ble), ble, device,
            readVersion = { reads++; throw S7CommandException(S7Failure.Timeout) }, // 仿 getDeviceInfo 超时抛
            versionReadRetries = 3,
        )
        // A 修复前此调用会异常穿透(测试直接失败)；修复后容错为 Reconnected(null)
        val result = prov.provisionAndReconnect(pkg("fw.dat" to 3000))
        assertEquals(null, assertIs<OtaResult.Reconnected>(result).currentVersion)
        assertEquals(3, reads, "异常被容错转 null → 应重试满 versionReadRetries 次")
    }

    /** 设备未断链(OTA 静默没触发复位)时仍能重连+读到当前版本；reconnect 首圈短路 connectCalls==0。 */
    @Test
    fun provisionAndReconnect_noReboot_readsStaleVersion() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }) // 不断链
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val prov = OtaProvisioner(newSession(ble), ble, device, readVersion = { "1.2.7.0" }, rebootWaitMs = 2_000)
        val result = prov.provisionAndReconnect(pkg("fw.dat" to 3000))
        assertEquals("1.2.7.0", assertIs<OtaResult.Reconnected>(result).currentVersion)
        assertEquals(0, ble.connectCalls, "链路未断 → reconnect 首圈短路, 从未真 connect")
    }

    @Test
    fun provisionAndReconnect_passesThroughDownloadFailure() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime }).apply { otaRejectReq = S7FileTrans.REQ_BUSY }
        val ble = FakeProvBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        var versionRead = false
        val prov = OtaProvisioner(newSession(ble), ble, device, readVersion = { versionRead = true; "x" })
        val result = prov.provisionAndReconnect(pkg("fw.dat" to 100))
        // 下载阶段(REQ 被拒)直接透传, 不进重连/读版本
        assertEquals(OtaFailure.ReqRejected(S7FileTrans.REQ_BUSY), assertIs<OtaResult.Failed>(result).reason)
        assertTrue(!versionRead, "下载失败不应进读版本阶段")
    }
}
