package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [BleConnectionCoordinator] 的所有权与生命周期测试.
 *
 * 每条对应一项验收(2026-07-16 复核意见给定):
 * - [vmCancel_doesNotCancelConnectTransaction] -- **VM 取消等待不会取消 app 级连接任务**(本类存在的理由);
 * - [connectAndRegistryAdd_areOneTransaction] -- connect -> 确认 -> registry.add 同一事务, 不存在"连上但没登记"(孤儿第二类产地);
 * - [eachToken_settlesAtMostOnce] -- 每个 token 最多执行一次善后(陈旧事务零动作退出);
 * - [staleTransaction_mustNotTouchNewOne] -- 旧清理不能删除新连接;
 * - [disconnect_publishesIdleOnlyAfterUnderlyingDisconnectReturns] -- 底层断开返回前不谎报已断开;
 * - [connect_isIdempotentWhileInFlight] -- 在飞期间重复提交不重复发起.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionCoordinatorTest {

    private fun dev(id: String = "d1") =
        ScannedDevice(id, "X", "AA:BB:CC:DD:EE:FF", -50, DeviceKind.DUT, null, listOf("FFE0"))

    /** 可精确控场的假 BleClient: connect 挂到 gate 放行为止, 全程记账. */
    private class FakeBle : BleClient {
        private val links = HashMap<String, MutableStateFlow<LinkState>>()
        /** connect 的放行闸: 未 complete 前 connect 一直挂起(模拟真实的数秒建链). */
        var connectGate = CompletableDeferred<Unit>()
        var connectStarted = 0
        var connectFinished = 0
        var connectCancelled = 0
        val disconnectCalls = mutableListOf<String>()
        /** disconnect 的放行闸(测"底层断开返回前不发布 Idle"). */
        var disconnectGate: CompletableDeferred<Unit>? = null
        var connectTo: LinkState = LinkState.CONNECTED

        private fun link(id: String) = links.getOrPut(id) { MutableStateFlow(LinkState.DISCONNECTED) }
        override fun scan() = flowOf(emptyList<ScannedDevice>())
        override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {
            connectStarted++
            try {
                connectGate.await()
                link(device.id).value = connectTo
                connectFinished++
            } catch (e: kotlinx.coroutines.CancellationException) {
                connectCancelled++
                throw e
            }
        }
        override suspend fun disconnect(deviceId: String) {
            disconnectCalls.add(deviceId)
            disconnectGate?.await()
            link(deviceId).value = LinkState.DISCONNECTED
        }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)
        override fun notifications(deviceId: String) = emptyFlow<BleNotification>()
        override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {}
        override fun discoveredService16s(deviceId: String): List<String> = emptyList()
    }

    /**
     * app 级域用**共享虚拟时钟的独立 scope**, 不用 backgroundScope --
     * 本仓已记录的测试坑: `advanceUntilIdle()` **不跑 backgroundScope 任务**(只推进到"无前台任务"即返回).
     * 独立 scope 亦更贴真: 生产里 app scope 本就与调用方无父子关系(这正是本类的要点).
     */
    private fun TestScope.coordinator(ble: FakeBle): Triple<BleConnectionCoordinator, ConnectionRegistry, CoroutineScope> {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val reg = ConnectionRegistry(ble, appScope)
        return Triple(BleConnectionCoordinator(ble, reg, appScope), reg, appScope)
    }

    /**
     * **本类存在的理由**: 页面销毁 => viewModelScope 取消 => 此前 `connect()` 被腰斩/物理连接成孤儿.
     * 现在页面只是观察者, 它的域取消**不得**影响 app 级事务.
     */
    @Test
    fun vmCancel_doesNotCancelConnectTransaction() = runTest {
        val ble = FakeBle()
        val (coord, reg, appScope) = coordinator(ble)

        // 模拟页面: 用自己的域(独立 Job, 共享测试时钟)提交意图, 随后销毁.
        val vmScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        vmScope.launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        assertEquals(1, ble.connectStarted, "事务应已发起")

        vmScope.cancel() // 页面返回/销毁
        testScheduler.advanceUntilIdle()
        assertEquals(0, ble.connectCancelled, "**页面销毁不得取消 app 级连接事务**")

        // 事务照常跑完.
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, ble.connectFinished, "事务应跑完")
        assertEquals(BleConnectionCoordinator.Attempt.Connected, coord.state("d1").value)
        assertTrue(reg.isConnected("d1"), "连上即登记, 页面早已不在也不影响")
        appScope.cancel()
    }

    /** connect -> 确认 CONNECTED -> registry.add 是同一事务: 不存在"连上但没登记"(= 孤儿第二类). */
    @Test
    fun connectAndRegistryAdd_areOneTransaction() = runTest {
        val ble = FakeBle()
        val (coord, reg, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        assertFalse(reg.isConnected("d1"), "未连上前不该登记")

        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(reg.isConnected("d1"))
        assertEquals(BleConnectionCoordinator.Attempt.Connected, coord.state("d1").value)
        appScope.cancel()
    }

    /** 连接失败不登记, 状态收敛 Failed(而非停在 Connecting 让 UI 永远转圈). */
    @Test
    fun connectFails_publishesFailed_andDoesNotRegister() = runTest {
        val ble = FakeBle().apply { connectTo = LinkState.DISCONNECTED }
        val (coord, reg, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(coord.state("d1").value is BleConnectionCoordinator.Attempt.Failed)
        assertFalse(reg.isConnected("d1"))
        appScope.cancel()
    }

    /** 在飞期间重复提交意图 = 幂等, 不重复发起(UI 连点两下不该建两条事务). */
    @Test
    fun connect_isIdempotentWhileInFlight() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        var second = true
        launch { second = coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        assertFalse(second, "已有在飞事务时应拒绝, 返回 false")
        assertEquals(1, ble.connectStarted, "不得重复发起")
        assertTrue(coord.isBusy("d1"))
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(coord.isBusy("d1"), "事务结束后槽应释放")
        appScope.cancel()
    }

    /**
     * **底层断开返回前不发布 Idle** -- 谎报"已断开"而物理仍连, 正是孤儿难以察觉的根源
     * (真机实证: NordicBleClient.disconnect 在 conns 无该 id 时 ?.let 短路, 一个字节不发却仍置 DISCONNECTED).
     */
    @Test
    fun disconnect_publishesIdleOnlyAfterUnderlyingDisconnectReturns() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(BleConnectionCoordinator.Attempt.Connected, coord.state("d1").value)

        // 底层 disconnect 卡住不返回.
        val gate = CompletableDeferred<Unit>()
        ble.disconnectGate = gate
        val dj = launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()
        assertTrue(ble.disconnectCalls.contains("d1"), "底层断开应已发起")
        assertFalse(
            coord.state("d1").value == BleConnectionCoordinator.Attempt.Idle,
            "**底层断开尚未返回, 不得宣布 Idle**",
        )

        gate.complete(Unit)
        dj.join()
        assertEquals(BleConnectionCoordinator.Attempt.Idle, coord.state("d1").value, "断开真正完成后才 Idle")
        appScope.cancel()
    }

    /** 每个 token 最多善后一次: 显式断开接管后, 事务自己的 finally 必须零动作退出. */
    @Test
    fun eachToken_settlesAtMostOnce() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()

        // 在飞期间显式断开: 它 CAS 取走槽 -> 事务的 finally 应发现槽已易主 -> 零动作.
        val dj = launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()
        dj.join()

        assertEquals(1, ble.connectCancelled, "显式断开应取消在飞事务")
        assertEquals(
            BleConnectionCoordinator.Attempt.Idle, coord.state("d1").value,
            "终态由**断开**这一方发布; 事务的 finally 不得把它改回 Failed",
        )
        assertFalse(coord.isBusy("d1"))
        appScope.cancel()
    }

    /**
     * 旧清理不能删除新连接: 断开后立刻重连, 旧事务的收尾不得把新事务的槽/状态抹掉.
     */
    @Test
    fun staleTransaction_mustNotTouchNewOne() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        val dj = launch { coord.disconnect("d1") } // 取走旧槽, 取消旧事务
        testScheduler.advanceUntilIdle()
        dj.join()

        // 立刻重连(新 token).
        ble.connectGate = CompletableDeferred()
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        assertTrue(coord.isBusy("d1"), "新事务应在飞")

        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(
            BleConnectionCoordinator.Attempt.Connected, coord.state("d1").value,
            "**新事务的终态不得被旧事务的收尾覆盖**",
        )
        appScope.cancel()
    }

    /** 未连接时显式断开: 仍要把底层断开发下去(设备可能是上一轮会话遗留的活链路), 且不崩. */
    @Test
    fun disconnect_withNoInFlightTxn_stillCallsUnderlying() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        coord.disconnect("ghost")
        assertTrue(
            ble.disconnectCalls.contains("ghost"),
            "无在飞事务也要发底层断开 —— 孤儿正是'App 侧无记录但物理连着'",
        )
        assertEquals(BleConnectionCoordinator.Attempt.Idle, coord.state("ghost").value)
        appScope.cancel()
    }

    /**
     * **断开路径上的孤儿(回归闸)**: 用户点'断开'后立刻退屏 => 调用方协程被取消.
     * 断开事务必须照常完成(底层 disconnect + registry 摘除 + Idle) -- 它跑在 app 域,
     * 调用方的挂起点只是可取消的 join. 与 connect 腰斩同族, 真机实证过.
     */
    @Test
    fun callerCancelledDuringDisconnect_transactionStillCompletes() = runTest {
        val ble = FakeBle()
        val (coord, reg, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(reg.isConnected("d1"))

        // 底层断开卡住, 模拟"断开进行中用户退屏".
        val gate = CompletableDeferred<Unit>()
        ble.disconnectGate = gate
        val vmScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        vmScope.launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()
        assertTrue(ble.disconnectCalls.contains("d1"), "断开应已发起")

        vmScope.cancel() // 退屏
        testScheduler.advanceUntilIdle()
        gate.complete(Unit) // 底层断开这才完成
        testScheduler.advanceUntilIdle()

        assertEquals(
            BleConnectionCoordinator.Attempt.Idle, coord.state("d1").value,
            "**调用方被取消, 断开事务仍须完成并发布 Idle**",
        )
        assertFalse(reg.isConnected("d1"), "registry 摘除必达")
        appScope.cancel()
    }

    /**
     * **快速返回竞态(复核高危 1 的回归闸)**: client 的 connect 走"已连接"等短路路径瞬间返回时,
     * 事务不得把槽卡死. 用 Unconfined 调度器让事务体在 start() 处**急切跑完** -- 旧实现
     * (先 launch 后 putSlot)会把已结束的 Job 塞进槽, 槽永不释放/状态永卡 Connecting;
     * 现实现 LAZY 启动(先登记槽再 start)保证 finally 的 takeSlot 必然看得到槽.
     */
    @Test
    fun fastReturningConnect_doesNotStrandSlot() = runTest {
        val ble = FakeBle().apply { connectGate.complete(Unit) } // connect 立即返回
        val appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val reg = ConnectionRegistry(ble, appScope)
        val coord = BleConnectionCoordinator(ble, reg, appScope)

        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()

        assertFalse(coord.isBusy("d1"), "**快速返回的事务不得把槽卡死**(旧实现在此永久 busy)")
        assertEquals(BleConnectionCoordinator.Attempt.Connected, coord.state("d1").value)
        assertTrue(reg.isConnected("d1"))
        appScope.cancel()
    }

    /** 断开是事务(复核高危 2): 断开进行中, 新 connect 必须被拒 -- 不得与断开交叉. */
    @Test
    fun connectRejected_whileDisconnecting() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        ble.disconnectGate = gate
        launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()
        assertEquals(
            BleConnectionCoordinator.Attempt.Disconnecting, coord.state("d1").value,
            "断开期间状态应为 Disconnecting(供 UI 禁用按钮)",
        )
        assertTrue(coord.isBusy("d1"), "断开事务占槽")

        var accepted = true
        launch { accepted = coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        assertFalse(accepted, "**断开进行中的 connect 必须被拒**")
        assertEquals(1, ble.connectStarted, "不得发起第二次底层 connect")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(BleConnectionCoordinator.Attempt.Idle, coord.state("d1").value)
        assertFalse(coord.isBusy("d1"))
        appScope.cancel()
    }

    /** 断开幂等: 断开进行中再点断开 = 等同一事务, 底层 disconnect 只发一次. */
    @Test
    fun doubleDisconnect_joinsSameTransaction() = runTest {
        val ble = FakeBle()
        val (coord, _, appScope) = coordinator(ble)
        launch { coord.connect(dev()) }
        testScheduler.advanceUntilIdle()
        ble.connectGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        ble.disconnectGate = gate
        val d1 = launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()
        val d2 = launch { coord.disconnect("d1") }
        testScheduler.advanceUntilIdle()

        assertEquals(1, ble.disconnectCalls.count { it == "d1" }, "**底层 disconnect 只发一次**")

        gate.complete(Unit)
        d1.join(); d2.join()
        assertEquals(BleConnectionCoordinator.Attempt.Idle, coord.state("d1").value)
        appScope.cancel()
    }
}
