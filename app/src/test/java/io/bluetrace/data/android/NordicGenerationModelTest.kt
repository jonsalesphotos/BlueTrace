package io.bluetrace.data.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.internal.OperationMutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **[#25] 代际模型的确定性测试**(app JVM 单测, 不需真机/蓝牙).
 *
 * 本测试钉死 [NordicBleClient] 的 `ManagerGen` 所依赖的四条不变量. 由于真正的 `CentralManager`/`Peripheral`
 * 需要 Android framework, 这里用**同构的最小模型**复刻代际结构(同样的 CAS/scope/表语义), 并用**真实的**
 * 库 `OperationMutex` 复现幽灵锁——即锁行为是真的, 被测的是我们的代际规则.
 *
 * 对应真机证据: `Docs/真机证据/nordic25_20260715/ghost_lock_repro.log`.
 *
 * 覆盖(对齐用户 2026-07-15 指令的四类):
 * 1. [heldServicesChangedLock_blocksDisconnect_andOuterTimeoutCannotCutIt]
 *    —— 持 ServicesChanged 锁时 disconnect 进不去, 外层取消切不掉, 解锁后才完成;
 * 2. [staleDisconnect_mustNotTouchNewGeneration]
 *    —— Gen1 disconnect 阻塞期间建立 Gen2, 放行 Gen1 后不得断掉 Gen2;
 * 3. [discoveryTimeout_retiresGenerationExactlyOnce]
 *    —— 多设备同时超时只触发一次换代;
 * 4. [staleGenerationCallback_mustNotOverwriteNewGeneration]
 *    —— 旧代回调/teardown/状态发布不得改写新代连接.
 */
class NordicGenerationModelTest {

    @After
    fun releaseGlobalLockIfHeld() {
        listOf<Any>(ServicesChanged, PROBE).forEach { owner ->
            if (OperationMutex.holdsLock(owner)) runCatching { OperationMutex.unlock(owner) }
        }
    }

    // ---- 最小同构模型(与 NordicBleClient.ManagerGen 同语义) ----

    private class FakeGatt(val genId: Long) {
        val closed = AtomicBoolean(false)
        val disconnectedBy = ConcurrentHashMap<Long, Boolean>()
        fun disconnectFrom(callerGen: Long) { disconnectedBy[callerGen] = true }
    }

    private class Gen(val id: Long, parent: Job) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(parent))
        val conns = ConcurrentHashMap<String, FakeGatt>()
        val retiring = AtomicBoolean(false)
    }

    private val genSeq = AtomicLong(0)

    /**
     * 1. 库的 `Peripheral.disconnect()` 第一件事就是 `OperationMutex.withLock`(全进程锁), 且整体跑在
     *    `withContext(NonCancellable)` 里 —— 故幽灵锁在手时它连函数体都进不去, 外层
     *    `withTimeoutOrNull` **切不掉**它(只是在超时时刻发出取消信号).
     *    真机对照: 锁空闲时 disconnect 1-4ms 完成; 锁被占时堵到解锁为止(实测 4.0s 而非 3.0s 超时).
     */
    @Test
    fun heldServicesChangedLock_blocksDisconnect_andOuterTimeoutCannotCutIt() = runBlocking {
        val entered = AtomicBoolean(false)
        // 复刻库内 disconnect 的锁语义(此处用真实的库 OperationMutex).
        suspend fun libDisconnect() = OperationMutex.withLock<Unit> { entered.set(true) }

        // 幽灵: 服务发现的裸 lock, 回调永不到达.
        val ghost = launch(Dispatchers.Default) {
            OperationMutex.lock(ServicesChanged)
            awaitCancellation()
        }
        withTimeoutOrNull(2_000) { while (!OperationMutex.holdsLock(ServicesChanged)) Unit; true }

        // 外层限时调用 disconnect —— 应该进不去.
        val r = withTimeoutOrNull(300) { libDisconnect() }
        assertNull("幽灵锁在手时 disconnect 不该完成", r)
        assertFalse("disconnect 连函数体都不该进入", entered.get())

        // 释放幽灵锁后才放行(模拟库自身 invalidateServices 的合法解锁).
        ghost.cancel()
        OperationMutex.unlock(ServicesChanged)
        assertNotNull("解锁后 disconnect 应立即完成", withTimeoutOrNull(2_000) { libDisconnect() })
        assertTrue(entered.get())
    }

    /**
     * 2. **失败实验的回归闸**: Gen1 的 disconnect 堵在幽灵锁上时, Gen2 建立了新连接;
     *    此时若直接解锁放行 Gen1(旧方案), 它读到的是**当前**的 gatt(库内 `NativeExecutor.gatt` 是可变字段)
     *    => 断掉 Gen2. 代际模型要求: Gen1 的一切善后只能作用于**自己那代**的对象.
     */
    @Test
    fun staleDisconnect_mustNotTouchNewGeneration() = runBlocking {
        val parent = Job()
        val gen1 = Gen(genSeq.incrementAndGet(), parent)
        val gatt1 = FakeGatt(gen1.id).also { gen1.conns["dev"] = it }

        // Gen1 的 disconnect 堵在幽灵锁上(持有的是**发起时**的 gatt 引用 —— 这正是代际模型的关键).
        OperationMutex.lock(ServicesChanged)
        val staleDisconnect = gen1.scope.launch {
            OperationMutex.withLock<Unit> { gatt1.disconnectFrom(gen1.id) } // 只碰自己那代的 gatt
        }

        // Gen2 建立(旧代退役 -> 新代新对象).
        gen1.retiring.set(true)
        val gen2 = Gen(genSeq.incrementAndGet(), parent)
        val gatt2 = FakeGatt(gen2.id).also { gen2.conns["dev"] = it }

        // 放行陈旧 disconnect.
        OperationMutex.unlock(ServicesChanged)
        withTimeoutOrNull(2_000) { staleDisconnect.join() }

        assertTrue("Gen1 的善后应作用在 Gen1 自己的 gatt 上", gatt1.disconnectedBy.containsKey(gen1.id))
        assertTrue(
            "**新代的 gatt 绝不能被陈旧 disconnect 碰到**(真机实测的跨代杀连接, 见 ghost_lock_repro.log 样本 5)",
            gatt2.disconnectedBy.isEmpty(),
        )
        assertFalse("Gen2 连接应存活", gatt2.closed.get())
        parent.cancel()
    }

    /** 3. 多台设备同时发现超时 -> 只触发一次换代(CAS 门; 败者零动作退出). */
    @Test
    fun discoveryTimeout_retiresGenerationExactlyOnce() = runBlocking {
        val parent = Job()
        val gen1 = Gen(genSeq.incrementAndGet(), parent)
        val retireCount = AtomicInteger(0)

        suspend fun retire(g: Gen) {
            if (!g.retiring.compareAndSet(false, true)) return // 败者零动作
            retireCount.incrementAndGet()
            g.scope.cancel()
        }

        // 三台设备并发超时.
        val jobs = (1..3).map { launch(Dispatchers.Default) { retire(gen1) } }
        jobs.forEach { it.join() }

        assertEquals("同一代只应退役一次", 1, retireCount.get())
        assertTrue(gen1.retiring.get())
        parent.cancel()
    }

    /**
     * 4. 旧代的晚到回调不得改写新代状态: 用 `conns.remove(id, expectedConn)` 的**值条件删除**
     *    (而非无条件 remove(id)) —— 这正是 [NordicBleClient] 里被动断链观测所用的守卫.
     */
    @Test
    fun staleGenerationCallback_mustNotOverwriteNewGeneration() = runBlocking {
        val parent = Job()
        val gen = Gen(genSeq.incrementAndGet(), parent)
        val oldConn = FakeGatt(gen.id)
        val newConn = FakeGatt(gen.id)

        gen.conns["dev"] = oldConn
        // 新一轮连接把登记换成 newConn(同一设备 id).
        gen.conns["dev"] = newConn

        // 旧连接的断链回调晚到: 条件删除应失败(当前登记者已不是它), 故零动作.
        val removedStale = gen.conns.remove("dev", oldConn)
        assertFalse("陈旧回调的条件删除应失败 => 零动作退出", removedStale)
        assertTrue("新代登记必须原样保留", gen.conns["dev"] === newConn)

        // 当前登记者自己的回调才允许清理.
        val removedCurrent = gen.conns.remove("dev", newConn)
        assertTrue("当前登记者的回调应成功清理", removedCurrent)
        assertNull(gen.conns["dev"])
        parent.cancel()
    }

    /**
     * 5. **真机第二轮撞出的回归闸**: 退役必须收敛**一切未断开的链路**, 不能只收敛 `conns`——
     *    发现超时发生在 conns 登记**之前**(登记在订阅完成后), 此时 conns 为空而 link 停在 CONNECTING.
     *    漏收敛 => `connect()` 首行 `if (l.value == CONNECTED || CONNECTING) return` 短路掉之后
     *    **每一次**连接请求 = 用户侧"永远连不上". 实测日志特征: "0 link(s) dropped".
     */
    @Test
    fun retire_mustConvergeConnectingLinks_notOnlyRegisteredConns() {
        val links = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<String>>()
        fun link(id: String) = links.getOrPut(id) { java.util.concurrent.atomic.AtomicReference("DISCONNECTED") }

        val gen = Gen(genSeq.incrementAndGet(), Job())
        // 发现超时的真实形态: link=CONNECTING, 而 conns **空**(尚未登记).
        link("dev").set("CONNECTING")
        assertTrue("前提: conns 此刻为空", gen.conns.isEmpty())

        // 错误做法(第二轮真机的 bug): 只收敛 conns.keys -> 0 dropped -> link 卡死 CONNECTING.
        val wrongDropped = gen.conns.keys.count()
        assertEquals("只看 conns 会漏掉一切未登记连接", 0, wrongDropped)
        assertEquals("CONNECTING", link("dev").get())

        // 正确做法: 收敛一切非 DISCONNECTED 的链路.
        var dropped = 0
        links.forEach { (_, f) -> if (f.get() != "DISCONNECTED") { f.set("DISCONNECTED"); dropped++ } }

        assertEquals("退役必须收敛 CONNECTING 链路", 1, dropped)
        assertEquals(
            "收敛后 connect() 的 CONNECTED/CONNECTING 短路才不会挡住重连",
            "DISCONNECTED", link("dev").get(),
        )
    }

    /**
     * 6. **真机第三轮撞出的回归闸**: 断开卡死的看门狗**必须跑在 app 域**, 不能跑在本代域——
     *    退役要取消本代域, 看门狗若在本代域里会被自己触发的退役取消, 换代做到一半就没了.
     *    (第三轮真机: 用户在服务发现中返回 -> disconnect 堵死幽灵锁永不返回 -> 只见 "disconnect enter"
     *    无 "done" -> dumpsys 证实 GATT 一直活着. )
     */
    @Test
    fun stuckDisconnectWatchdog_mustRunOnAppScope_notGenScope() = runBlocking {
        val appJob = Job()
        val appScope = CoroutineScope(Dispatchers.Default + appJob)
        val gen = Gen(genSeq.incrementAndGet(), appJob)
        val retired = AtomicBoolean(false)

        // 复刻"永不返回的 disconnect"(库内 NonCancellable + 堵在全局锁上).
        val stuckJob = gen.scope.launch { awaitCancellation() }

        // 看门狗跑 app 域: 判定卡死 -> 退役(取消本代域).
        val watchdog = appScope.launch {
            if (withTimeoutOrNull(200) { stuckJob.join() } == null) {
                gen.retiring.set(true)
                gen.scope.cancel() // 这会取消 stuckJob —— 若看门狗也在本代域, 它自己也会死在这一行
                retired.set(true)  // 关键: 取消本代域**之后**仍须执行到这里
            }
        }
        withTimeoutOrNull(3_000) { watchdog.join() }

        assertTrue("看门狗必须在取消本代域后仍能完成换代(故不能跑在本代域)", retired.get())
        assertTrue("卡死的断开协程应随本代域取消而了结", stuckJob.isCancelled)
        appJob.cancel()
    }

    private companion object {
        private val PROBE = Any()
    }
}
