package io.bluetrace.shared.b2a

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.device.FwPackage
import io.bluetrace.shared.device.FwUpdatePhase
import io.bluetrace.shared.device.FwUpdateProgress
import io.bluetrace.shared.device.FwUpdateResult
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [B2aFirmwareUpdateStrategy] 策略皮测试(W4): run 成功链 + 细->粗阶段映射 + FwPackage fail fast +
 * **abort 固件门控红线**(传输中不发 CTRL_RESET / 非传输态发 / 未连接跳过).
 * 编排链本身(下载/回连)的行为由 B2aOtaTest/B2aOtaProvisionerTest 覆盖, 本文件只测策略层的映射与门控传导.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class B2aFirmwareUpdateStrategyTest {

    private val device = ScannedDevice(
        id = "s7-fcc4", name = "SKG WATCH S7-FCC4", address = "71:61:48:19:FC:C4",
        rssi = -50, kind = DeviceKind.DUT,
    )

    /**
     * 直连 Mock 手表 + 下行命令捕获; [silent]=true 只记帧不回(watch 不接)——
     * run 会挂在等 REQ ack(传输态已置), 精确模拟"传输中手动停止"的取消窗口.
     */
    private class FakeBle(
        val watch: B2aMockWatch,
        private val clock: EpochClock,
        private val scope: CoroutineScope,
        var silent: Boolean = false,
    ) : BleClient {
        val link = MutableStateFlow(LinkState.CONNECTED)
        private val txDecoder = B2aFrameDecoder()

        /** 下行命令捕获 (cmd, key): 断言 CTRL_RESET 是否发出用. */
        val txCmds = mutableListOf<Pair<Int, Int>>()
        private val inbound = MutableSharedFlow<BleNotification>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow() // 无扫描流 -> 回连走直连兜底(老路径)
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) { link.value = LinkState.CONNECTED }
        override suspend fun disconnect(deviceId: String) { link.value = LinkState.DISCONNECTED }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link
        override fun negotiatedMtu(deviceId: String): Int = 247
        override fun notifications(deviceId: String): Flow<BleNotification> = inbound
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
            if (link.value != LinkState.CONNECTED) return
            for (m in txDecoder.feed(bytes)) txCmds += m.cmd to m.key
            if (silent) return
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

    private fun pkg(size: Int = 6000) = OtaPackage(
        files = listOf(OtaFile("fw.dat", ByteArray(size) { (it * 31).toByte() }, B2aFileTrans.FT_FW)),
    )

    private fun TestScope.strategy(
        ble: BleClient,
        onPhase: (OtaPhase) -> Unit = {},
        onLog: (String) -> Unit = {},
    ) = B2aFirmwareUpdateStrategy(
        ble, device, backgroundScope, virtualClock { testScheduler.currentTime }, TestZone(),
        abortScope = backgroundScope,
        reconnectScanMs = 1_000, // 无扫描流场景尽快落直连兜底
        onLog = onLog,
        onOtaPhase = onPhase,
    )

    // ---- run: 成功链 + 映射 ----

    @Test
    fun run_fullChain_success_coarsePhaseMapping_detailPassthrough() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val watch = B2aMockWatch(clock).apply { otaRebootAfterComplete = true }
        val ble = FakeBle(watch, clock, backgroundScope)
        val fw = mutableListOf<FwUpdateProgress>()
        val s = strategy(ble)

        val result = s.run(pkg()) { fw.add(it) }

        val ok = assertIs<FwUpdateResult.Success>(result)
        assertNotNull(ok.versionAfter, "回连后应读到 Mock 手表版本")
        assertEquals(LinkState.CONNECTED, ble.link.value, "应已重连回 CONNECTED")
        // 细 OtaPhase -> 粗 FwUpdatePhase 映射: 下载=Transferring, 等复位/扫描/重连=Applying, 读版本=Verifying, 完成=Done
        val phases = fw.map { it.phase }
        assertTrue(
            phases.containsAll(listOf(FwUpdatePhase.Transferring, FwUpdatePhase.Applying, FwUpdatePhase.Verifying, FwUpdatePhase.Done)),
            "粗阶段应走全: $phases",
        )
        // 传输进度 percent 末尾触顶(sent==total)
        assertTrue(fw.last { it.phase == FwUpdatePhase.Transferring }.percent >= 99, "传输末进度应触顶: $fw")
        // 细阶段文案经 detail 透传(UI 日志不丢)
        assertTrue(fw.any { it.detail == "等待复位" || it.detail == "重连中" }, "细阶段文案应经 detail 透传: ${fw.map { it.detail }}")
    }

    @Test
    fun run_wrongPackageType_failsFast_noBleTouch() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val ble = FakeBle(B2aMockWatch(clock), clock, backgroundScope)
        val s = strategy(ble)

        val r = s.run(object : FwPackage {})

        val f = assertIs<FwUpdateResult.Failed>(r)
        assertEquals(0, f.percentAtFailure)
        assertTrue(ble.txCmds.isEmpty(), "包类型不符应 fail fast, 不碰 BLE: ${ble.txCmds}")
    }

    // ---- abort: 固件门控红线(传输态自持的传导) ----

    @Test
    fun abort_midTransfer_skipsReset_firmwareGate() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        // silent: REQ 发出后无 ack, run 挂在下载链中(otaTransferActive 已置 true)——传输中手动停止的真实窗口
        val ble = FakeBle(B2aMockWatch(clock), clock, backgroundScope, silent = true)
        val phase = MutableStateFlow<OtaPhase?>(null)
        val s = strategy(ble, onPhase = { phase.value = it })

        val job = launch { s.run(pkg()) }
        withTimeout(30_000) { phase.first { it == OtaPhase.Downloading } }
        job.cancelAndJoin() // 手动停止 = 取消运行; 取消后 otaTransferActive 保持最后值(cancelAndJoin 屏障后读)

        s.abort()

        assertTrue(
            ble.txCmds.none { it.first == B2a.CMD_DEV_CTRL && it.second == B2a.CTRL_RESET },
            "传输中固件 OTA 门控吞重启指令——不应发送: ${ble.txCmds}",
        )
    }

    @Test
    fun abort_notTransferring_connected_sendsReset() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        // silent: RESET 电源类固件本就不回包(以断链为旁证); 不断链 -> 观测窗靠虚拟时钟跳过, 返 false 不影响断言
        val ble = FakeBle(B2aMockWatch(clock), clock, backgroundScope, silent = true)
        val s = strategy(ble)

        s.abort() // run 未跑: otaTransferActive=false 初始值, link=CONNECTED -> 应发

        assertTrue(
            ble.txCmds.any { it.first == B2a.CMD_DEV_CTRL && it.second == B2a.CTRL_RESET },
            "非传输态且连接中应发送重启指令: ${ble.txCmds}",
        )
    }

    @Test
    fun abort_notConnected_skips() = runTest {
        val clock = virtualClock { testScheduler.currentTime }
        val ble = FakeBle(B2aMockWatch(clock), clock, backgroundScope)
        ble.link.value = LinkState.DISCONNECTED
        val logs = mutableListOf<String>()
        val s = strategy(ble, onLog = { logs.add(it) })

        s.abort()

        assertTrue(ble.txCmds.isEmpty(), "未连接不应发任何命令: ${ble.txCmds}")
        assertTrue(logs.any { it.contains("跳过重启指令") }, "应日志说明跳过: $logs")
    }
}
