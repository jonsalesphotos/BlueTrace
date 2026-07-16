package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **app 级连接事务宿主**: 一条 BLE 连接的寿命长于任何一个页面, 故建立它的**事务**也必须活在 app 域,
 * 而不是页面的 viewModelScope.
 *
 * ## 为什么需要它(2026-07-16 真机取证结论)
 * 此前 9 处发起连接的入口**全部**跑在 `viewModelScope` 上(唯一例外 [io.bluetrace.shared.device.DeviceSessionManager]
 * 跟调用方协程). 于是出现**生命周期错配**:
 * - 连接**对象**归 BleClient 单例(进程级);
 * - 建立连接的**过程**归页面(页面级).
 *
 * 页面一返回 -> viewModelScope 取消 -> `connect()` 被腰斩, 而它**已经在系统里造出了一条物理 GATT 连接**.
 * 清理责任于是落在一个**正在被取消的协程**里--恰恰是它最没能力干活的时刻(不能挂起/不能等回调/不能重试).
 * 后果实测(Docs/真机证据/nordic25_20260716/):
 * - 物理连接活着但 App 侧无人引用 = **孤儿**: 设备连着即停广播 -> 扫不到 -> 五个页面(连接/扫描/OTA/
 *   控制台/采集)**同时看不见**(它们都以 [ConnectionRegistry] 为唯一真相源, 而孤儿从未 add 进去);
 * - 且**断不开**: 所有断开入口按 id 寻址, 而 id 已从 BleClient 的连接表移除.
 *
 * 本类把"连接尝试"变成一个**有主的/app 级的事务**:
 * - [connect] 只是**提交意图**, 立即返回; 真正的事务跑在 app 域;
 * - **页面销毁不取消事务** -- 页面只是 [state] 的观察者, 走了就走了, 事务照常跑完;
 * - `ble.connect` -> 确认 CONNECTED -> `registry.add` 是**同一个事务**, 不再是"两条独立语句被中途腰斩";
 * - **只有显式 [disconnect] 才取消尝试**, 并由它接管完整清理.
 *
 * ## 所有权模型(与 OTA 侧 `OtaOperationGate` 的 Lease/RunToken 同源)
 * 每设备至多一个在飞事务, 由 [Txn] 的 `token` 标识. 清理入口(自然结束 / 显式断开)**CAS 争夺同一个槽,
 * 赢家才允许善后** => **每个 token 最多执行一次善后**, 陈旧事务零动作退出, 绝不碰新一代事务的状态.
 *
 * ## 不做什么(边界)
 * - **不**接管 BleClient 内部的连接表 -- 后端各自的"何时算持有句柄"是后端问题
 *   (自写后端 connectGatt 后立即入表; Nordic 直到订阅完成才入表, 那 30s 窗口的孤儿属 Nordic 侧缺陷,
 *   须由其自身的连接槽解决, 见 `Docs/设计/Nordic重连挂死_根因分析.md`);
 * - **不**把 [ConnectionRegistry] 变成"全部物理链路的真相源" -- registry 是**用户连接列表**,
 *   OTA 的临时回连等内部链路本就不该进; 故本类只服务"用户发起的连接"这一类意图.
 */
class BleConnectionCoordinator(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    /** **app 级**域: 事务的宿主, 与任何页面无关. */
    private val scope: CoroutineScope,
) {

    /** 一次连接意图的可观测状态(页面据此渲染, 不再自持 busy). */
    sealed interface Attempt {
        /** 无在飞事务(未连接, 或已断开). */
        data object Idle : Attempt
        /** 连接事务在飞(页面显示"连接中"; 页面销毁**不会**让它回到 Idle). */
        data object Connecting : Attempt
        /** 已连接且**已登记进 registry** -- 两者由同一事务提交, 不存在"连上但没登记". */
        data object Connected : Attempt
        /**
         * 断开事务在飞: 断开也是事务, 同样占槽/入状态机 -- 否则断开执行期间按钮重新可用,
         * 会重复 disconnect 或与新 connect 交叉(复核发现的第二个高危竞态).
         */
        data object Disconnecting : Attempt
        /** 本次尝试失败(设备无响应/被显式取消等); 下一次 [connect] 会覆盖它. */
        data class Failed(val reason: String) : Attempt
    }

    private enum class TxnKind { CONNECT, DISCONNECT }

    private class Txn(val token: Long, val kind: TxnKind, val job: Job)

    /** 事务槽: id -> 当前在飞事务. CAS 争夺(MutableStateFlow.compareAndSet), 与 OTA 侧同款. */
    private val slots = MutableStateFlow<Map<String, Txn>>(emptyMap())
    private val states = MutableStateFlow<Map<String, Attempt>>(emptyMap())
    private val tokenSeq = MutableStateFlow(0L)
    /** 只保护"建事务"这一步的原子性(检查已有 + 建新); 清理走 CAS 不占锁. */
    private val startLock = Mutex()

    /**
     * 全部设备的意图状态快照(响应式). 页面把它并进自己的 combine 派生 busy/行状态,
     * 不再自持 busy 布尔 -- 于是**换页面/重建 VM 后在飞事务依旧可见**(旧方案 _busy 随 VM 消亡,
     * 新实例进来看不到"连接中", 正是孤儿难察觉的一环).
     */
    val attempts: StateFlow<Map<String, Attempt>> = states.asStateFlow()

    /** 某设备的连接意图状态(单设备观察口; 与 [attempts] 同源). */
    fun state(deviceId: String): StateFlow<Attempt> = statesView(deviceId)

    /**
     * 单设备视图表: CAS getOrPut -- publish 在 app 域多线程跑, 普通 mutableMap 的 getOrPut
     * 有丢更新竞态(commonMain 无 ConcurrentHashMap/synchronized 可用, 故用 StateFlow CAS).
     */
    private val views = MutableStateFlow<Map<String, MutableStateFlow<Attempt>>>(emptyMap())
    private fun statesView(id: String): MutableStateFlow<Attempt> {
        while (true) {
            val cur = views.value
            cur[id]?.let { return it }
            val created = MutableStateFlow(states.value[id] ?: Attempt.Idle)
            if (views.compareAndSet(cur, cur + (id to created))) return created
        }
    }

    private fun publish(id: String, s: Attempt) {
        states.update { it + (id to s) }
        statesView(id).value = s
    }

    /** 是否有在飞事务(UI 禁用重复点击用; 与 [Attempt.Connecting] 同源). */
    fun isBusy(deviceId: String): Boolean = slots.value.containsKey(deviceId)

    /**
     * **提交连接意图**(立即返回, 不挂起调用方).
     *
     * 幂等: 同一设备已有在飞事务时直接返回 false, 不重复发起.
     * 事务跑在 app 域 -- **调用方(页面)销毁不会取消它**.
     * 提交本身包在 NonCancellable 里: "启动事务 + 登记槽"必须原子--若调用方恰在 launch 与
     * putSlot 之间被取消, 会留下"事务在跑但无槽"的怪胎(finally 的 CAS 永远失败, 状态卡 Connecting).
     *
     * @return true = 已受理并新建事务; false = 已有在飞事务, 未做任何事.
     */
    suspend fun connect(device: ScannedDevice, spec: GattSpec? = null): Boolean =
        withContext(NonCancellable) {
            startLock.withLock {
                if (slots.value.containsKey(device.id)) return@withLock false
                val token = tokenSeq.updateAndGet { it + 1 }
                publish(device.id, Attempt.Connecting)
                // **LAZY 启动**: 必须"先登记槽, 再放行事务". 若先 launch 后 putSlot, 快速返回的
                // connect(如 client 的"已连接"短路)可能在 putSlot 之前整个跑完--finally 的 takeSlot
                // 找不到槽零动作, 随后外层把**已结束**的 Job 塞进槽 => 槽永不释放, 状态永卡
                // Connecting/busy(复核发现的第一个高危竞态; NonCancellable 管不住调度顺序).
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    var ok = false
                    try {
                        ble.connect(device, spec)
                        // connect 与 registry.add 属**同一事务**: 此前二者是两条独立语句, 恢复点被取消
                        // 即漏登记(= 孤儿的第二类产地). 这里页面已无法取消我们, 故不存在那个窗口.
                        ok = ble.linkState(device.id).value == LinkState.CONNECTED
                        if (ok) registry.add(device)
                    } finally {
                        // 善后与状态发布**只有 CAS 赢家**能做: 显式 disconnect 可能已接管本事务.
                        withContext(NonCancellable) {
                            if (takeSlot(device.id, token)) {
                                publish(device.id, if (ok) Attempt.Connected else Attempt.Failed("connect failed"))
                            }
                        }
                    }
                }
                putSlot(device.id, Txn(token, TxnKind.CONNECT, job))
                job.start()
                true
            }
        }

    /**
     * **显式断开**: 唯一允许取消在飞连接事务的入口. 断开自己也是事务--占槽 + 发布
     * [Attempt.Disconnecting], 期间新的 [connect] 被拒/重复 [disconnect] 幂等等待同一事务
     * (否则断开执行期间按钮重新可用, 会重复断开或与新连接交叉).
     *
     * 语义: 接管在飞连接(取消并等其收尾) -> 底层断开 -> 摘除 registry -> 发布 Idle.
     * **在底层断开真正返回之前不发布 Idle** -- 谎报"已断开"正是孤儿难以察觉的原因之一.
     *
     * 断开事务同样跑在 **app 域**: 用户点'断开'后立刻退屏(viewModelScope 取消)时, 断开必须
     * 照常完成 -- 否则就是"断开路径上的孤儿"(与 connect 腰斩同族, 真机实证过).
     * 调用方的挂起点只是 join(可取消); 取消它不影响事务本身.
     */
    suspend fun disconnect(deviceId: String) {
        val job = withContext(NonCancellable) {
            startLock.withLock {
                val prior = slots.value[deviceId]
                // 幂等: 已有断开事务在飞 -> 不再起第二个, 等同一事务(底层 disconnect 只发一次).
                if (prior?.kind == TxnKind.DISCONNECT) return@withLock prior.job
                // 接管在飞连接事务(若有): 从槽里取走, 由断开事务负责取消与收尾等待.
                val taken = prior?.also { takeSlot(deviceId, it.token) }
                val token = tokenSeq.updateAndGet { it + 1 }
                publish(deviceId, Attempt.Disconnecting)
                val j = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        // 先取消并**等待**在飞事务收尾, 再动底层: 否则 connect 与 disconnect 在 BleClient 内打架.
                        taken?.job?.cancelAndJoin()
                        ble.disconnect(deviceId)
                    } finally {
                        // 底层断开抛异常也不得漏善后: registry 摘除与终态发布必达.
                        withContext(NonCancellable) {
                            registry.remove(deviceId)
                            if (takeSlot(deviceId, token)) publish(deviceId, Attempt.Idle)
                        }
                    }
                }
                putSlot(deviceId, Txn(token, TxnKind.DISCONNECT, j))
                j.start()
                j
            }
        }
        job.join()
    }

    // ---- 槽的 CAS 原语 ----

    private fun putSlot(id: String, txn: Txn) {
        slots.update { it + (id to txn) }
    }

    /** CAS 取走**指定 token**的槽: 赢家 true(可善后), 陈旧/已被接管者 false(零动作退出). */
    private fun takeSlot(id: String, token: Long): Boolean {
        while (true) {
            val cur = slots.value
            val t = cur[id] ?: return false
            if (t.token != token) return false // 已被新事务或 disconnect 接管
            if (slots.compareAndSet(cur, cur - id)) return true
        }
    }

    /** CAS 取走该 id 的**任意**槽(显式断开用: 它有权接管当前在飞的那一个). */
    private fun takeSlotAny(id: String): Txn? {
        while (true) {
            val cur = slots.value
            val t = cur[id] ?: return null
            if (slots.compareAndSet(cur, cur - id)) return t
        }
    }
}

private fun <T> MutableStateFlow<T>.update(f: (T) -> T) {
    while (true) {
        val cur = value
        if (compareAndSet(cur, f(cur))) return
    }
}

private fun MutableStateFlow<Long>.updateAndGet(f: (Long) -> Long): Long {
    while (true) {
        val cur = value
        val next = f(cur)
        if (compareAndSet(cur, next)) return next
    }
}
