package io.bluetrace.shared.s7

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.ble.GattSpec
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MultiOtaController] 串行编排测试: N 个 [S7MockWatch] 造多设备假 [BleClient], 验证
 * 串行逐台跑完整流程(连接→读版本电量→电量门槛→刷写+等复位+重连+读版本→复读电量→断开),
 * 失败即跳过继续下一台, 电量门槛跳过, 单台重试. 虚拟时钟 + advanceUntilIdle 驱动.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MultiOtaControllerTest {

    private fun s7(id: String, name: String) =
        ScannedDevice(id, name, "71:61:48:19:FC:C4", -58, DeviceKind.DUT, PROFILE_S7)

    /** 多设备假 BleClient: 每设备一个 [S7MockWatch]; write→watch.handle→该设备 inbound; 末 STOP 后按注入断链.  */
    private class FakeMultiBle(
        private val watches: Map<String, S7MockWatch>,
        private val clock: EpochClock,
        private val scope: CoroutineScope,
    ) : BleClient {
        private val links = watches.keys.associateWith { MutableStateFlow(LinkState.DISCONNECTED) }
        private val inbound = watches.keys.associateWith {
            MutableSharedFlow<BleNotification>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

        /** 下行帧捕获(cmd,key 解码后记录): 断言"手动停止发了 CTRL_RESET"用.  */
        private val txDecoders = watches.keys.associateWith { S7FrameDecoder() }
        val txCmds = mutableListOf<Triple<String, Int, Int>>()

        /** 非空时 disconnect 挂起等放行: 拉长停止善后窗口, 测 stopping 门竞态用. */
        var disconnectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) { links.getValue(device.id).value = LinkState.CONNECTED }
        override suspend fun disconnect(deviceId: String) {
            disconnectGate?.await()
            links.getValue(deviceId).value = LinkState.DISCONNECTED
        }
        override fun linkState(deviceId: String): StateFlow<LinkState> = links.getValue(deviceId)
        override fun negotiatedMtu(deviceId: String): Int = 247
        override fun notifications(deviceId: String): Flow<BleNotification> = inbound.getValue(deviceId)
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
            val link = links.getValue(deviceId)
            if (link.value != LinkState.CONNECTED) return
            for (m in txDecoders.getValue(deviceId).feed(bytes)) txCmds += Triple(deviceId, m.cmd, m.key)
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
        // 版本/电量前后值(Mock: swVer=1.2.7.0, battery=82)
        assertEquals("1.2.7.0", q[0].versionAfter)
        assertEquals(82, q[0].batteryBefore)
        assertEquals(82, q[0].batteryAfter)
        // 两台的推包字节都应完整落到各自 mock(互不串扰)
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

    /**
     * 手动停止(传输中): 固件 OTA 门控会丢弃一切非 FILE_TRANS 命令(审查坐实 2026-07-14)——
     * **不发**重启指令, 标"手动停止"(可重试)并断开连接(设备由固件 61s 看门狗自复位).
     * ⚠️ 停止善后跑 backgroundScope——`testScheduler.advanceUntilIdle()` **不处理后台任务**
     * (只推进到"无前台任务"), 须像其他用例一样用挂起等待(first{})让 workRunner 消化后台队列.
     */
    @Test
    fun stopBatch_midTransfer_marksStopped_skipsReset_disconnects() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        // 大包(多切片)拖慢传输, 保证停止发生在下载中段(phase=Downloading)
        val bigPkg = OtaPackage(files = listOf(OtaFile("fw.dat", ByteArray(100_000) { (it * 7).toByte() }, S7FileTrans.FT_FW)))
        val watch = S7MockWatch(clock)
        val ble = FakeMultiBle(mapOf("a" to watch), clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A")))

        ctl.startBatch(bigPkg)
        testScheduler.advanceTimeBy(30)
        testScheduler.runCurrent() // 走到下载中段
        val mid = ctl.queue.value.single()
        assertEquals(DeviceOtaStatus.FLASHING, mid.status, "应停在刷写中段: ${mid.status}")
        assertEquals(OtaPhase.Downloading, mid.phase)

        ctl.stopBatch()
        withTimeout(10_000) { ctl.queue.first { q -> q.single().status == DeviceOtaStatus.FAILED } }
        withTimeout(10_000) { ble.linkState("a").first { it == LinkState.DISCONNECTED } }

        val item = ctl.queue.value.single()
        assertEquals("手动停止", item.failReason)
        assertTrue(item.retriable, "手动停止的台子应可重试")
        assertTrue(
            ble.txCmds.none { it.first == "a" && it.second == S7.CMD_DEV_CTRL && it.third == S7.CTRL_RESET },
            "传输中固件门控吞掉重启指令——不应发送: ${ble.txCmds.filter { it.second == S7.CMD_DEV_CTRL }}",
        )
        assertEquals(false, ctl.running.value)
    }

    /** 手动停止(非传输态, READING 刷前读取阶段): OTA 标志未置位, 重启指令应正常发送.  */
    @Test
    fun stopBatch_beforeTransfer_sendsReset() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watch = S7MockWatch(clock)
        val ble = FakeMultiBle(mapOf("a" to watch), clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A")))

        ctl.startBatch(pkg())
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent() // 连接后读版本/电量中(READING, 未进 FILE_TRANS)
        val mid = ctl.queue.value.single()
        assertTrue(mid.status in setOf(DeviceOtaStatus.CONNECTING, DeviceOtaStatus.READING), "应停在读取阶段: ${mid.status}")

        ctl.stopBatch()
        withTimeout(10_000) { ctl.queue.first { q -> q.single().status == DeviceOtaStatus.FAILED } }
        withTimeout(10_000) { ble.linkState("a").first { it == LinkState.DISCONNECTED } }

        assertTrue(
            ble.txCmds.any { it.first == "a" && it.second == S7.CMD_DEV_CTRL && it.third == S7.CTRL_RESET },
            "非传输态停止应发送重启指令: ${ble.txCmds.filter { it.second == S7.CMD_DEV_CTRL }}",
        )
        assertEquals(false, ctl.running.value)
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

        // 修好设备(不再拒 REQ + 刷后复位), 手动重试
        watch.otaRejectReq = null
        watch.otaRebootAfterComplete = true
        ctl.retry("a", pkg())?.join()

        assertEquals(DeviceOtaStatus.DONE, ctl.queue.value.single().status)
    }

    /**
     * 停止善后(阻塞中)期间 retry/startBatch 整体拒绝: 不改队列不起新一轮——否则善后窗口内
     * 项被改成 QUEUED 却没有任务在跑(需用户再点一次), 或旧善后误伤新一轮. 善后完成后恢复可重试.
     */
    @Test
    fun retry_duringStopCleanup_rejected_thenAllowedAfter() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watch = S7MockWatch(clock)
        val ble = FakeMultiBle(mapOf("a" to watch), clock, backgroundScope)
        val ctl = newController(ble)
        ctl.addDevices(listOf(s7("a", "S7-A")))

        val job = ctl.startBatch(pkg())
        checkNotNull(job)
        // 等设备进入执行中(任意非 QUEUED 态)再停止
        withTimeout(10_000) { ctl.queue.first { q -> q.any { it.status != DeviceOtaStatus.QUEUED } } }
        ble.disconnectGate = kotlinx.coroutines.CompletableDeferred() // 卡住善后的断开步, 拉长 stopping 窗口
        ctl.stopBatch()
        assertTrue(ctl.stopping.value, "stopBatch 应同步进入 stopping")
        job.join()

        // stopping 窗口内: retry 整体拒绝(返回 null 且不把项改成 QUEUED)
        assertEquals(null, ctl.retry("a", pkg()))
        assertTrue(
            ctl.queue.value.single().status != DeviceOtaStatus.QUEUED,
            "善后窗口内 retry 不得改队列: ${ctl.queue.value.single().status}",
        )
        assertEquals(null, ctl.startBatch(pkg()), "善后窗口内 startBatch 也应拒绝")

        // 放行善后, stopping 收敛, 重试恢复可用
        ble.disconnectGate!!.complete(Unit)
        withTimeout(10_000) { ctl.stopping.first { !it } }
        ble.disconnectGate = null
        watch.otaRebootAfterComplete = true
        val retryJob = ctl.retry("a", pkg())
        checkNotNull(retryJob) { "善后完成后 retry 应恢复可用" }
        retryJob.join()
        assertEquals(DeviceOtaStatus.DONE, ctl.queue.value.single().status)
    }

    /**
     * app 级租约跨实例互斥: 实例 A 的停止善后(阻塞中)期间, **另一实例** B(共享同一
     * OtaOperationGate, 模拟另一模式屏/退屏重进的新 VM)不得开始; A 善后完全结束后 B 可开始;
     * B 自然结束后租约释放(第三次可再开始).
     */
    @Test
    fun gate_sharedAcrossInstances_blocksWhileCleanup_releasesAfter() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val gate = io.bluetrace.shared.device.OtaOperationGate()
        val watchA = S7MockWatch(clock)
        val bleA = FakeMultiBle(mapOf("a" to watchA), clock, backgroundScope)
        val ctlA = MultiOtaController(
            ble = bleA, registry = ConnectionRegistry(bleA, backgroundScope),
            clock = clock, zone = TestZone(), scope = backgroundScope, gate = gate,
        )
        val watchB = S7MockWatch(clock).apply { otaRebootAfterComplete = true }
        val bleB = FakeMultiBle(mapOf("b" to watchB), clock, backgroundScope)
        val ctlB = MultiOtaController(
            ble = bleB, registry = ConnectionRegistry(bleB, backgroundScope),
            clock = clock, zone = TestZone(), scope = backgroundScope, gate = gate,
        )
        ctlA.addDevices(listOf(s7("a", "S7-A")))
        ctlB.addDevices(listOf(s7("b", "S7-B")))

        val jobA = checkNotNull(ctlA.startBatch(pkg()))
        withTimeout(10_000) { ctlA.queue.first { q -> q.any { it.status != DeviceOtaStatus.QUEUED } } }
        bleA.disconnectGate = kotlinx.coroutines.CompletableDeferred()
        ctlA.stopBatch()
        jobA.join()

        // A 善后阻塞中(租约仍被 A 持有): B 开新一轮必须被拒
        assertEquals(null, ctlB.startBatch(pkg()), "旧实例善后在飞时另一实例不得开始")

        // A 善后完成 -> 租约释放 -> B 可开始并跑完; B 自然结束后租约再次可用
        bleA.disconnectGate!!.complete(Unit)
        withTimeout(10_000) { ctlA.stopping.first { !it } }
        val jobB = checkNotNull(ctlB.startBatch(pkg())) { "善后结束后另一实例应可开始" }
        jobB.join()
        assertEquals(DeviceOtaStatus.DONE, ctlB.queue.value.single().status)
        assertEquals(false, gate.busy.value, "自然结束应释放租约")
    }
}
