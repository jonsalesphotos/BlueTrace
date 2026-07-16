package io.bluetrace.data.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.ServicesChanged
import no.nordicsemi.kotlin.ble.client.internal.OperationMutex
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Nordic Kotlin-BLE-Library 2.0.0-beta03 库行为固定测试**(任务 #25 证据闭环).
 *
 * 本测试不测本项目代码, 而是把"重连服务发现挂死"根因所依赖的**库内三条行为**钉死为可执行断言.
 * 目的有三:
 * 1. 把源码推理升级为证据--库真这么行为, 不是我们读错了;
 * 2. 作为 Nordic issue 的最小可复现附件(纯 JVM, 不需要真机/蓝牙);
 * 3. **跳闸丝**: 将来升级 Nordic 版本时本测试若失败, 说明上游改了行为, 届时可评估撤掉本项目侧规避.
 *
 * ## 根因链(源码坐标均为 beta03 sources jar)
 * `client-core` `Peripheral.discoverServices()` 是库内**唯一**用裸 `OperationMutex.lock()`
 * (而非自带 try/finally 的 `withLock`)的地方:
 * ```
 * private fun discoverServices(uuids: List<Uuid>) {
 *     if (!servicesDiscovered) {
 *         servicesDiscovered = true          // 同步置位
 *         scope.launch {                     // 但锁在**异步**协程里才拿
 *             try { OperationMutex.lock(ServicesChanged) } catch (e: IllegalStateException) { log }
 *             _services.update { RemoteServices.Discovering }
 *             impl.discoverServices(uuids)   // 无 finally: 之后一切靠回调事件来解锁
 *         }
 *     }
 * }
 * ```
 * 解锁只有三条路径, 全部依赖**事件到达**: `ServicesDiscovered` / `ServiceDiscoveryFailed` /
 * `invalidateServices()`(断链时经 handleDisconnection 调用, 且只在 `holdsLock` 为真时解).
 * 设备已断链时 `impl.discoverServices()` 的回调永不到达 => 三条解锁路径一条都不走
 * => **全进程唯一的那把锁被永久持有** => 此后任何设备的任何 GATT 操作永久排队.
 * 这解释了本项目实测的"仅杀进程可解".
 *
 * ## 三条被钉死的行为(对应下面三个用例)
 * 1. [operationMutex_isProcessWide_notPerPeripheral] -- 锁是**全进程一把**(object + static Mutex),
 *    不是 per-peripheral/per-CentralManager: 一台设备卡住锁, 全部设备陪葬;
 * 2. [rawLock_leaksForever_whenHolderCancelled] -- 裸 lock() 无 finally: 持锁协程被取消**不释放锁**
 *    (这正是本项目 teardown 取消连接协程时踩的形态);
 * 3. [ownerToken_isSharedSingleton_soAnyoneCanUnlockAnyone] -- owner token 用的是 `data object
 *    ServicesChanged`(全进程唯一实例), 故 owner 语义形同虚设: 同 owner 二次 lock 直接抛
 *    IllegalStateException(库 catch 后**继续裸奔执行发现**), 且任一 peripheral 的解锁会解掉别人的锁.
 *
 * 注: [OperationMutex] 虽在 `.internal` 包内, 但源码无 `internal` 修饰符(javap 确认为 public final class
 * + public static final INSTANCE), 故可直接驱动.
 */
class NordicOperationMutexPinTest {

    /** 探测锁是否被占: 拿得到返回 true, 被挡住返回 null. */
    private suspend fun probeLockFree(timeoutMs: Long = 300): Boolean? =
        withTimeoutOrNull(timeoutMs) { OperationMutex.withLock(PROBE) { true } }

    /**
     * 锁是**进程级单例**, 泄漏会污染同 JVM 的后续测试(本测试自身就是这一点的受害者与证人).
     * 每个用例后强制归零.
     */
    @After
    fun releaseGlobalLockIfHeld() {
        listOf(ServicesChanged, PERIPHERAL_A, PERIPHERAL_B, PROBE).forEach { owner ->
            if (OperationMutex.holdsLock(owner)) {
                runCatching { OperationMutex.unlock(owner) }
            }
        }
    }

    /** 行为 1: 锁的作用域 = 全进程(不是 per-peripheral, 也不是 per-CentralManager). */
    @Test
    fun operationMutex_isProcessWide_notPerPeripheral() = runBlocking {
        // 模拟"设备 A"持有锁(库内即 A 的服务发现进行中).
        OperationMutex.lock(PERIPHERAL_A)

        // 模拟"设备 B"--与 A 毫无关系的另一台设备/另一个 CentralManager--尝试做任意 GATT 操作.
        var bRan = false
        val b = launch(Dispatchers.Default) {
            OperationMutex.withLock(PERIPHERAL_B) { bRan = true }
        }

        assertNull(
            "设备 B 的操作应被设备 A 的锁挡住 => 证明这把锁是全进程一把, 不是 per-peripheral",
            withTimeoutOrNull(300) { b.join() },
        )
        assertFalse("B 不该执行", bRan)

        // A 放锁后 B 才通行.
        OperationMutex.unlock(PERIPHERAL_A)
        assertNotNull("A 解锁后 B 应立即通行", withTimeoutOrNull(2_000) { b.join() })
        assertTrue("B 应已执行", bRan)
    }

    /**
     * 行为 2(**根因**): 裸 `lock()` 无 try/finally -- 持锁协程被取消时锁**不释放**, 永久泄漏.
     *
     * 对照组同时证明: 走 `withLock` 的路径(库内写/读/订阅都走它)取消时**会**释放.
     * 故本项目原先"取消订阅的 CCC 写泄漏了全局锁"的假设是**错的**--泄漏点在服务发现的裸 lock.
     */
    @Test
    fun rawLock_leaksForever_whenHolderCancelled() = runBlocking {
        // --- 实验组: 复刻库 discoverServices 的裸 lock + 等回调形态 ---
        val ghost = launch(Dispatchers.Default) {
            OperationMutex.lock(ServicesChanged)
            // 库在此处调 impl.discoverServices() 后等 ServicesDiscovered 事件回来解锁;
            // 设备已断链 => 回调永不到达 => 挂在这里.
            awaitCancellation()
        }
        // 等它确实拿到锁.
        assertNotNull(
            "ghost 应已持锁",
            withTimeoutOrNull(2_000) {
                while (!OperationMutex.holdsLock(ServicesChanged)) { kotlinx.coroutines.yield() } // 自旋须有挂起点, 否则超时切不断(跳闸丝要失败不要卡死)
                true
            },
        )

        // 本项目 teardown 取消连接协程 -- 库的锁会跟着释放吗?
        ghost.cancelAndJoin()

        assertNull(
            "裸 lock 无 finally: 持锁协程被取消后锁仍被持有 => 此后全进程一切 GATT 操作永久排队(仅杀进程可解)",
            probeLockFree(),
        )
        assertTrue("锁仍挂在 ServicesChanged 名下", OperationMutex.holdsLock(ServicesChanged))

        // 手工解锁(库内没有任何路径会做这件事 -- 这正是问题所在).
        OperationMutex.unlock(ServicesChanged)

        // --- 对照组: withLock 路径取消时正常释放 ---
        val wellBehaved = launch(Dispatchers.Default) {
            OperationMutex.withLock(PERIPHERAL_A) { awaitCancellation() }
        }
        withTimeoutOrNull(2_000) {
            while (!OperationMutex.holdsLock(PERIPHERAL_A)) { kotlinx.coroutines.yield() } // 自旋须有挂起点, 否则超时切不断(跳闸丝要失败不要卡死)
            true
        }
        wellBehaved.cancelAndJoin()
        assertTrue(
            "对照: withLock 自带 finally, 取消后锁应已释放 => 写/订阅路径不是泄漏源",
            probeLockFree() == true,
        )
    }

    /**
     * 行为 3: owner token 是 `data object ServicesChanged` -- 全进程唯一实例, 被所有 peripheral 共用.
     * 后果 a: 第二台设备发现时 `lock(ServicesChanged)` 抛 ISE(库 catch 掉后**不持锁继续执行发现**);
     * 后果 b: 任一设备的解锁事件可解掉另一台设备持有的锁(owner 校验形同虚设).
     */
    @Test
    fun ownerToken_isSharedSingleton_soAnyoneCanUnlockAnyone() = runBlocking {
        // 设备 A 的服务发现持锁.
        OperationMutex.lock(ServicesChanged)

        // 后果 a: 设备 B 的服务发现用同一个 owner token => 直接抛 ISE, 而非等待排队.
        // 库在 discoverServices 里 catch(IllegalStateException) 只打 warn 日志, 随后照常
        // impl.discoverServices() -- 即**未持锁**执行, 串行保证在此处失效.
        assertThrows(
            "同 owner token 二次 lock 应抛 ISE(库将其吞掉后无锁裸奔)",
            IllegalStateException::class.java,
        ) {
            runBlocking { OperationMutex.lock(ServicesChanged) }
        }

        // 后果 b: 设备 B 收到自己的 ServicesDiscovered 事件时会 unlock(ServicesChanged) --
        // 解掉的其实是设备 A 持有的那把锁.
        OperationMutex.unlock(ServicesChanged)
        assertTrue(
            "B 的解锁事件解掉了 A 的锁 => owner 语义形同虚设(全进程共用同一 token 实例)",
            probeLockFree() == true,
        )
    }

    private companion object {
        /** 仅用于测试的 owner token(库用 data object ServicesChanged, 见行为 3). */
        private val PERIPHERAL_A = Any()
        private val PERIPHERAL_B = Any()
        private val PROBE = Any()
    }
}
