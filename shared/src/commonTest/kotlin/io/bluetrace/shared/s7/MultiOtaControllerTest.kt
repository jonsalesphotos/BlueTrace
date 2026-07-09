package io.bluetrace.shared.s7

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_S7
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
import kotlin.test.assertTrue

/**
 * [MultiOtaController] 串行编排测试：N 个 [S7MockWatch] 造多设备假 [BleClient]，验证
 * 串行逐台跑完整流程（连接→读版本电量→电量门槛→刷写+等复位+重连+读版本→复读电量→断开）、
 * 失败即跳过继续下一台、电量门槛跳过、单台重试。虚拟时钟 + advanceUntilIdle 驱动。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MultiOtaControllerTest {

    private fun s7(id: String, name: String) =
        ScannedDevice(id, name, "71:61:48:19:FC:C4", -58, DeviceKind.DUT, PROFILE_S7)

    /** 多设备假 BleClient：每设备一个 [S7MockWatch]；write→watch.handle→该设备 inbound；末 STOP 后按注入断链。 */
    private class FakeMultiBle(
        private val watches: Map<String, S7MockWatch>,
        private val clock: EpochClock,
        private val scope: CoroutineScope,
    ) : BleClient {
        private val links = watches.keys.associateWith { MutableStateFlow(LinkState.DISCONNECTED) }
        private val inbound = watches.keys.associateWith {
            MutableSharedFlow<BleNotification>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice) { links.getValue(device.id).value = LinkState.CONNECTED }
        override suspend fun disconnect(deviceId: String) { links.getValue(deviceId).value = LinkState.DISCONNECTED }
        override fun linkState(deviceId: String): StateFlow<LinkState> = links.getValue(deviceId)
        override fun negotiatedMtu(deviceId: String): Int = 247
        override fun notifications(deviceId: String): Flow<BleNotification> = inbound.getValue(deviceId)
        override suspend fun write(deviceId: String, bytes: ByteArray) {
            val link = links.getValue(deviceId)
            if (link.value != LinkState.CONNECTED) return
            val reply = watches.getValue(deviceId).handle(bytes)
            scope.launch {
                for (f in reply.frames) {
                    delay(1) // 保证 collector 先订阅
                    inbound.getValue(deviceId).emit(BleNotification(deviceId, clock.nowMs(), f))
                }
                if (reply.disconnectAfter) {
                    delay(1)
                    link.value = LinkState.DISCONNECTED // 末 STOP 后设备自复位断链
                }
            }
        }
    }

    private fun pkg() = OtaPackage(
        files = listOf(OtaFile("fw.dat", ByteArray(3000) { (it * 31).toByte() }, S7FileTrans.FT_FW)),
    )

    private fun kotlinx.coroutines.test.TestScope.newController(ble: BleClient, lowBatteryPct: Int = 30): MultiOtaController =
        MultiOtaController(
            ble = ble,
            registry = ConnectionRegistry(ble, backgroundScope),
            clock = virtualClock { testScheduler.currentTime },
            zone = TestZone(),
            scope = backgroundScope,
            lowBatteryPct = lowBatteryPct,
        )

    @Test
    fun batch_processesAllDevices_serially_toDone() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watches = mapOf(
            "a" to S7MockWatch(clock).apply { otaRebootAfterComplete = true },
            "b" to S7MockWatch(clock).apply { otaRebootAfterComplete = true },
        )
        val ble = FakeMultiBle(watches, clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A"), s7("b", "S7-B")))

        ctl.startBatch(pkg())?.join()

        val q = ctl.queue.value
        assertEquals(2, q.size)
        assertTrue(q.all { it.status == DeviceOtaStatus.DONE }, "两台都应完成: ${q.map { it.status }}")
        // 版本/电量前后值（Mock：swVer=1.2.7.0，battery=82）
        assertEquals("1.2.7.0", q[0].versionAfter)
        assertEquals(82, q[0].batteryBefore)
        assertEquals(82, q[0].batteryAfter)
        // 两台的推包字节都应完整落到各自 mock（互不串扰）
        assertTrue(watches.getValue("a").otaComplete && watches.getValue("b").otaComplete, "两台 mock 都应整包收讫")
        assertEquals(false, ctl.running.value)
    }

    @Test
    fun batch_failedDevice_skips_and_continues() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watches = mapOf(
            "a" to S7MockWatch(clock).apply { otaRejectReq = S7FileTrans.REQ_BUSY }, // A: REQ 被拒 → 失败
            "b" to S7MockWatch(clock).apply { otaRebootAfterComplete = true }, // B: 正常
        )
        val ble = FakeMultiBle(watches, clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A"), s7("b", "S7-B")))

        ctl.startBatch(pkg())?.join()

        val q = ctl.queue.value.associateBy { it.device.id }
        assertEquals(DeviceOtaStatus.FAILED, q.getValue("a").status)
        assertEquals(DeviceOtaStatus.DONE, q.getValue("b").status, "A 失败不应拖垮 B")
        assertEquals(false, ctl.running.value)
    }

    @Test
    fun batch_lowBattery_skips_withoutFlashing() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watch = S7MockWatch(clock).apply { otaRebootAfterComplete = true } // 电量默认 82
        val ble = FakeMultiBle(mapOf("a" to watch), clock, backgroundScope)
        val ctl = newController(ble, lowBatteryPct = 90) // 门槛设 90 → 82% 被跳过
        ctl.addDevices(listOf(s7("a", "S7-A")))

        ctl.startBatch(pkg())?.join()

        val a = ctl.queue.value.single()
        assertEquals(DeviceOtaStatus.SKIPPED_LOW_BATTERY, a.status)
        assertEquals(82, a.batteryBefore)
        assertEquals(null, a.versionAfter, "低电跳过不应刷写/读刷后版本")
        assertTrue(!watch.otaComplete, "低电跳过不应推包")
    }

    @Test
    fun retry_failedDevice_reruns_toDone_afterFix() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watch = S7MockWatch(clock).apply { otaRejectReq = S7FileTrans.REQ_BUSY }
        val ble = FakeMultiBle(mapOf("a" to watch), clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A")))

        ctl.startBatch(pkg())?.join()
        assertEquals(DeviceOtaStatus.FAILED, ctl.queue.value.single().status)

        // 修好设备（不再拒 REQ + 刷后复位），手动重试
        watch.otaRejectReq = null
        watch.otaRebootAfterComplete = true
        ctl.retry("a", pkg())?.join()

        assertEquals(DeviceOtaStatus.DONE, ctl.queue.value.single().status)
    }
}
