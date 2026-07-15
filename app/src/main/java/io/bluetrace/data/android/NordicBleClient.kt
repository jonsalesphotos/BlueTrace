package io.bluetrace.data.android

import android.content.Context
import android.util.Log
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.ble.extract16
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
// ---- Nordic Kotlin-BLE-Library 2.0（隔离面：全部 Nordic import 收在本文件——BleClient 的唯一 Nordic 依赖点）----
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteService
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.ScanResult
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment

/**
 * 真实 Android BLE Central 的 **Nordic 实现**（BleClient 第二并存实现，W1.5 PoC；不替换 [AndroidBleClient]）。
 *
 * 行为契约逐条对齐 [AndroidBleClient] 的 KDoc（真机踩过的坑同款收敛）：
 * - 扫描：CentralManager.scan 全量（无过滤）-> 累积去重列表 Flow；上游取消即停扫。
 * - 连接：**连接前 services() 预注册**（库设计的重连姿势, 被动断链 close() 清标志故每连必注册）->
 *   CentralManager.connect(Direct) + requestHighestValueLength(MTU) -> 发现收敛（Discovered 成功 /
 *   Failed 快速失败 / 超时记末态, OTA 复位回连实证）-> spec 声明式定位 / 现状探测
 *   （B2A RX+TX 同在 -> B2A; 否则 HRS 2A37; 都无 -> 失败断开）-> 订阅 Notify（等首个 CCC 使能才置
 *   CONNECTED）。**连接失败不抛业务异常, linkState 收敛 DISCONNECTED; 协程取消释放底层连接**
 *   （peripheral.disconnect, 3s 硬上限防库内 await 挂死）。不自动重连（同 v1）。
 * - 写：直接映射 [RemoteCharacteristic.write]（WriteType.WITHOUT_RESPONSE）——见下「写流控证据」。
 * - Notify：per-characteristic subscribe() 合流成设备级 Flow, characteristicId 填 16-bit 短码（extract16 口径）。
 * - 逐帧 TX/RX hex 日志（TAG=[TAG]）：编译期开关 [FRAME_LOG] 门控, **默认关**（常开拖慢 OTA 吞吐,
 *   见常量 KDoc）; 排固件问题（golden log 工作法）时改 true 重编。
 *
 * ## 写流控证据（S7 OTA 17 帧切片生死项；结论：库已保证串行等完成, 故不叠加自写 Mutex）
 * 源：`client-core` `internal/BaseRemoteCharacteristic.write`（tag 2.0.0-beta03）：
 *   `write(data, writeType) = OperationMutex.withLock { events.onSubscription { executeWrite(data, writeType) }`
 *   `.filterIsInstance<CharacteristicWrite>().firstOrNull { it.matches() }.let { check(it.status == Success) } }`
 * - `OperationMutex.withLock`：所有特征操作串行（等价 AndroidBleClient 的 writeLock）。
 * - 挂起等 `CharacteristicWrite` 事件（源自连接级 GATT 事件流 = onCharacteristicWrite 回调）并校验 status==Success
 *   （等价 AndroidBleClient 的 writeAck=onCharacteristicWrite）。
 * - **无 WriteType 分支**：WITHOUT_RESPONSE 亦走同一「串行 + 等完成回调」路径。
 * 源：`client-android` `internal/NativeRemoteCharacteristic.executeWrite` -> `gatt.writeCharacteristic(ch, data, writeType.toInt())`
 *   （API33+）/ 旧版设 value+writeType 后 `gatt.writeCharacteristic(ch)`——与 AndroidBleClient 同一 native 调用。
 * 因串行 + 等回调, 库天然规避了 AndroidBleClient 需退避重试的 WRITE_BUSY 背靠背溢出。W1.6 真机 A/B 仍需对照吞吐基线复核。
 *
 * 所有 Nordic import 收在本类（隔离面 = 这一个文件）。
 */
@OptIn(ExperimentalUuidApi::class)
class NordicBleClient(
    private val context: Context,
    private val clock: EpochClock,
    /** CentralManager 生命周期域 + 各连接的父域（app 应用域）。 */
    private val scope: CoroutineScope,
) : BleClient {

    // 首次使用时创建（避免在 DI 构造期做 Android 工作）: NativeAndroidEnvironment 桥接 Context + 注册蓝牙状态广播。
    // isNeverForLocationFlagSet=false: 本工程 manifest 的 BLUETOOTH_SCAN 未声明 neverForLocation（定位是真实数据源, D-3）。
    private val centralManager by lazy {
        CentralManager.Factory.native(
            NativeAndroidEnvironment.getInstance(context, isNeverForLocationFlagSet = false),
            scope,
        )
    }

    /** 单连接持有的通道句柄（探测/spec 两路径统一）。 */
    private class Conn(
        val peripheral: Peripheral,
        /** 本连接的订阅/状态观测子域: disconnect / 被动断链时整体取消。 */
        val scope: CoroutineScope,
        /** 默认写特征（char16=null 落这里）: 探测=B2A RX(FFE1); spec=spec.writeChar16。 */
        val writeChar: RemoteCharacteristic?,
        /** char16 寻址写: 16-bit 短码 -> 可写特征; 查无静默丢弃。 */
        val writeChars: Map<String, RemoteCharacteristic>,
        /** 连接级写类型（spec.writeWithResponse 决定; 默认 WITHOUT_RESPONSE, 同现有无响应写流控）。 */
        val writeType: WriteType,
    )

    private val conns = ConcurrentHashMap<String, Conn>()
    private val links = ConcurrentHashMap<String, MutableStateFlow<LinkState>>()
    // 设备级持久通知流（与连接生命周期解耦: 订阅可先于 connect——console attach 早于连接完成）
    private val notifyFlows = ConcurrentHashMap<String, MutableSharedFlow<BleNotification>>()
    // 连接后发现的 16-bit 短码服务表(会话宿主 confirm 二次确认用; 断连清理).
    private val discoveredServices16 = ConcurrentHashMap<String, List<String>>()

    private fun link(id: String): MutableStateFlow<LinkState> =
        links.getOrPut(id) { MutableStateFlow(LinkState.DISCONNECTED) }

    private fun notifyFlow(id: String): MutableSharedFlow<BleNotification> =
        notifyFlows.getOrPut(id) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    // ---- 扫描 ----

    override fun scan(): Flow<List<ScannedDevice>> = flow {
        emit(emptyList())
        val found = LinkedHashMap<String, ScannedDevice>()
        try {
            // scan() 默认无过滤 + INFINITE 超时: 累积去重, 上游取消 -> 冷流取消 -> 底层停扫。
            centralManager.scan().collect { sr ->
                val dev = sr.toScanned() ?: return@collect
                if (found.size < 5) {
                    Log.d(TAG, "scanResult ${dev.name} ${dev.address} rssi=${dev.rssi} adv=${dev.advertisedServices}")
                }
                found[dev.id] = dev
                emit(found.values.toList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 蓝牙关/无权限等: 与 AndroidBleClient 一致——不崩, 保留已见列表。
            Log.w(TAG, "scan failed: ${e.message}")
        }
    }

    private fun ScanResult.toScanned(): ScannedDevice? {
        val addr = peripheral.identifier.toString()
        // nRF Connect 式全量展示: 无名设备占位名（便于按 MAC 定位目标 / 确认周围广播活性）。
        val name = advertisingData.name ?: peripheral.name ?: "(unnamed)"
        val adv = advertisingData.serviceUuids.mapNotNull { extract16(it.toString()) }
        // 扫描层去识别化(W2): 只上报原始广播(profileId=null/kind=DUT), 识别归 DeviceProfileCatalog
        // (消费投影层 annotate 打标); 名称/MAC/RSSI/广播 service 原样带出.
        return ScannedDevice(
            id = addr,
            name = name,
            address = addr,
            rssi = rssi,
            kind = DeviceKind.DUT,
            profileId = null,
            advertisedServices = adv,
        )
    }

    // ---- 连接 / 订阅 ----

    override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {
        val l = link(device.id)
        if (l.value == LinkState.CONNECTED || l.value == LinkState.CONNECTING) return
        val peripheral = centralManager.getPeripheralById(device.id) ?: run {
            Log.w(TAG, "connect: unknown peripheral ${device.id}")
            l.value = LinkState.DISCONNECTED
            return
        }
        l.value = LinkState.CONNECTING
        Log.d(TAG, "connect start ${device.id} spec=${spec?.serviceUuid16 ?: "probe"}")

        val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        try {
            // [OTA 复位回连修-1] 连接前注册 services 观察——库设计的重连姿势（源码注释原文:
            // "This is useful when the peripheral reconnects but the services observer was already set."）:
            // services() 置 serviceDiscoveryRequested=true, 库在 initiateConnection()（Connected 即刻）自行触发发现;
            // 被动断链的 close() 会清该标志（连带 servicesDiscovered/serviceDiscoveryRequested 复位、impl.close()）,
            // 故**每次连接前都必须重新注册**——manager 缓存 getOrPut 同 id 永远同实例, 复用实例全靠此姿势复活。
            val servicesFlow = peripheral.services()

            centralManager.connect(peripheral, CentralManager.ConnectionOptions.Direct(timeout = CONNECT_TIMEOUT))
            // MTU: Nordic 2.0 只提供 requestHighestValueLength（请求 517）; 设备侧协商收敛到其上限（S7 预期 247）。
            // 该调用挂起到 MtuChanged 事件（库源码证据见 [awaitMtuConverged]）; 失败非致命（轮询兜底后沿用现值）。
            runCatching { peripheral.requestHighestValueLength() }
                .onFailure { Log.w(TAG, "requestHighestValueLength failed: ${it.message}") }
            // OTA sliceMax=(MTU-15)x17 依赖 negotiatedMtu, 必须在置 CONNECTED 前收敛; 此日志值即真机验证依据。
            // （曾误报 mtu~23: 旧日志经 negotiatedMtu() 读 conns[deviceId], 而 Conn 订阅完成后才入表 -> 恒回退 23,
            //   并非库未协商——现直接读 peripheral 并等收敛后打印。）
            val mtu = awaitMtuConverged(peripheral)
            Log.d(TAG, "connected ${device.id} mtu=$mtu")

            // [OTA 复位回连修-2] 发现兜底再触发一次（幂等: 库内 servicesDiscovered 闩防重入）——
            // 覆盖 initiateConnection 未触发的边角; 正常路径此时已 Discovering/Discovered, 本调用是 no-op。
            if (servicesFlow.value is RemoteServices.Unknown) peripheral.services()

            // [OTA 复位回连修-3] 发现收敛看齐库的全部终态, 不再只等 Discovered:
            // - Discovered -> 成功;
            // - Failed -> **快速失败**（旧代码对 Failed 视而不见, 把它烧成 10s 超时）。刚复位的设备 GATT 服务表
            //   未就绪时, 库在 status!=SUCCESS 或服务数<2 时发 ServiceDiscoveryFailed（Failed.Reason.EmptyResult
            //   专为此设）——首连成功/复位回连失败的差异正在此;
            // - 超时 -> 记录末态定位卡点: Unknown=发现从未启动（库内 discoverServices 未跑）;
            //   Discovering=发现已发出但回调丢失（库忽略 gatt.discoverServices() 的 false 返回值, 无重试）。
            // 失败即快速 teardown, 重连重试属上层 provisioner 既有语义（60s 预算内自然再来）, 本层不盲重试。
            val settled = withTimeoutOrNull(SERVICES_TIMEOUT) {
                servicesFlow.first { it is RemoteServices.Discovered || it is RemoteServices.Failed }
            }
            val discovered = when (settled) {
                is RemoteServices.Discovered -> settled
                is RemoteServices.Failed -> {
                    Log.w(TAG, "services discovery FAILED ${device.id}: ${settled.reason}")
                    null
                }
                else -> {
                    Log.w(TAG, "services discovery timeout ${device.id}, lastState=${servicesFlow.value}")
                    null
                }
            }
            if (discovered == null) {
                teardown(device.id, peripheral, connScope)
                return
            }
            val services = discovered.services
            Log.d(TAG, "services ${device.id}: ${services.map { extract16(it.uuid.toString()) }}")
            // 存 16-bit 短码服务表(会话宿主 confirm 二次确认用).
            discoveredServices16[device.id] = services.mapNotNull { extract16(it.uuid.toString()) }

            // 通道定位（spec 声明式 / 现状探测 B2A->HRS）——与 AndroidBleClient.onServicesDiscovered 逐条对齐。
            val plan = resolveChannels(services, spec)
            if (plan == null || plan.notify.isEmpty()) {
                Log.w(TAG, "no usable channel ${device.id} (spec=${spec != null})")
                teardown(device.id, peripheral, connScope)
                return
            }

            // 订阅 Notify: subscribe() 订阅即使能通知; 等首个 Notify 的 onSubscription（CCC 使能）才算 CONNECTED
            // （对齐 AndroidBleClient「CCC 写成功才置 CONNECTED」）。多特征各自合流进设备级 notifyFlow。
            val firstReady = CompletableDeferred<Boolean>()
            plan.notify.forEachIndexed { idx, ch ->
                connScope.launch {
                    try {
                        ch.subscribe(onSubscription = { if (idx == 0 && !firstReady.isCompleted) firstReady.complete(true) })
                            .collect { bytes ->
                                val cid = extract16(ch.uuid.toString())
                                if (FRAME_LOG) Log.d(TAG, "RX ${ch.uuid} <- ${bytes.toHex()}")
                                notifyFlow(device.id).tryEmit(BleNotification(device.id, clock.nowMs(), bytes, cid))
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "subscribe ${ch.uuid} ended: ${e.message}")
                        if (idx == 0 && !firstReady.isCompleted) firstReady.complete(false)
                    }
                }
            }
            val ready = withTimeoutOrNull(SUBSCRIBE_TIMEOUT) { firstReady.await() } ?: false
            if (!ready) {
                Log.w(TAG, "notify subscribe not ready ${device.id}")
                teardown(device.id, peripheral, connScope)
                return
            }

            conns[device.id] = Conn(peripheral, connScope, plan.writeChar, plan.writeChars, plan.writeType)
            l.value = LinkState.CONNECTED
            Log.d(TAG, "CONNECTED ${device.id}")
            // 被动断链观测（设备自复位 / OTA 重启 / 蓝牙关）: state 转 Disconnected -> 收敛 DISCONNECTED + 清理;
            // 不自动重连（同 v1 AndroidBleClient——断了就 DISCONNECTED）。置于 CONNECTED 之后启动:
            // StateFlow 现值若已 Disconnected（连上瞬断）, 首个 emission 即触发清理, 不会被后置的 CONNECTED 覆盖。
            connScope.launch {
                peripheral.state.collect { st ->
                    when (st) {
                        is ConnectionState.Disconnected, ConnectionState.Disconnecting -> {
                            Log.d(TAG, "state=$st -> DISCONNECTED ${device.id}")
                            l.value = LinkState.DISCONNECTED
                            conns.remove(device.id)
                            discoveredServices16.remove(device.id)
                            connScope.cancel()
                        }
                        else -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            // 调用方协程取消（连接页返回销毁 VM 等）: 必须释放底层连接, 否则幽灵连接占死设备（真机踩过, 见 AndroidBleClient）。
            Log.d(TAG, "connect cancelled ${device.id}, releasing")
            teardown(device.id, peripheral, connScope)
            throw e
        } catch (e: Exception) {
            // 连接失败不抛业务异常（BleClient 契约）: 收敛 DISCONNECTED, 由调用方观测 + 超时兜底。
            Log.w(TAG, "connect failed ${device.id}: ${e.message}")
            teardown(device.id, peripheral, connScope)
        }
    }

    /** 通道定位结果。 */
    private class Plan(
        val notify: List<RemoteCharacteristic>,
        val writeChar: RemoteCharacteristic?,
        val writeChars: Map<String, RemoteCharacteristic>,
        val writeType: WriteType,
    )

    private fun resolveChannels(services: List<RemoteService>, spec: GattSpec?): Plan? {
        if (spec != null) {
            // spec 路径（W2 起接入）: 按声明定位服务/写特征/多 Notify; 协议知识由 spec 提供, 不进本实现。
            val svc = services.firstOrNull { extract16(it.uuid.toString()) == spec.serviceUuid16 } ?: return null
            val byShort = svc.characteristics.mapNotNull { c -> extract16(c.uuid.toString())?.let { it to c } }.toMap()
            val w = spec.writeChar16
            val writeChar = w?.let { byShort[it] }
            val writeChars = if (w != null && writeChar != null) mapOf(w to writeChar) else emptyMap()
            val notify = spec.notifyChar16s.mapNotNull { byShort[it] }
            val writeType = if (spec.writeWithResponse) WriteType.WITH_RESPONSE else WriteType.WITHOUT_RESPONSE
            return Plan(notify, writeChar, writeChars, writeType)
        }
        // 探测路径（现状 B2A -> HRS）: 跨服务收集特征, 按 16-bit 短码分组。
        val byShort = services.flatMap { it.characteristics }
            .mapNotNull { c -> extract16(c.uuid.toString())?.let { it to c } }
            .groupBy({ it.first }, { it.second })
        val b2aTx = byShort[PROBE_B2A_TX_16]?.firstOrNull()
        val b2aRx = byShort[PROBE_B2A_RX_16]?.firstOrNull()
        val hrs = byShort[PROBE_HRS_MEASURE_16]?.firstOrNull()
        return when {
            // B2A 优先（RX+TX 同在即认定, spec §1）: notify=TX(FFE2), write=RX(FFE1)。
            b2aTx != null && b2aRx != null ->
                Plan(listOf(b2aTx), b2aRx, mapOf(PROBE_B2A_RX_16 to b2aRx), WriteType.WITHOUT_RESPONSE)
            hrs != null -> Plan(listOf(hrs), null, emptyMap(), WriteType.WITHOUT_RESPONSE)
            else -> null
        }
    }

    /**
     * 释放连接: 取消子域 + 收敛 DISCONNECTED（**无条件立即到达**）+ 底层断开 fire-and-forget。
     *
     * [OTA 复位回连修-4/Bug2] disconnect 必须包死限时: peripheral.disconnect() 是 suspend,
     * 库内经 `await(action={impl.disconnect(reason)}, condition={it.isDisconnected})` 等断链事件
     * （await 默认 timeout=Duration.INFINITE; 事件层 MutableSharedFlow(extraBufferCapacity=64) 全 tryEmit,
     * 无送达保证）——真机 OTA 复位回连场景实证可无限挂起。
     *
     * [连接页卡死修 2026-07-15] 挂等 disconnectBounded 仍不够: 真机实证（发现超时 lastState=Discovering
     * 后的 teardown）`withTimeoutOrNull(3s)` 的取消**切不掉** disconnect() 内部的清理挂起——body 不结束
     * withTimeoutOrNull 就不返回（结构化并发）, 连"timed out"日志都打不出, teardown 挂死 -> connect()
     * 永不返回 -> 调用方 busy 永挂（连接页按钮冻结在"连接中"、无法重试）。
     * 故断开改 **fire-and-forget**（丢 app 级 [scope]）: 收尾（conns/link/服务表）必达优先, 底层断开
     * 尽力而为; 挂死的断开协程待系统 supervision timeout 的断链事件到来自行完结。
     */
    private fun teardown(id: String, peripheral: Peripheral, connScope: CoroutineScope) {
        conns.remove(id)
        discoveredServices16.remove(id)
        // [Mutex 卡死修 2026-07-15] 断开在前、connScope.cancel 在后（且同协程串行）:
        // 取消订阅 collect 会让库发 CCC disable 写, 若链路仍在, 该写在库内**全局 OperationMutex**
        // 上等一个可能永不到来的写回调——挂死后同进程一切后续 GATT 操作（含新连接的服务发现）
        // 排队永不执行（真机实证: 断开→重连 Discovering 超时 3/4 复现, 仅杀进程可解）。
        // 物理断链后再取消, 收尾写直接短路（未连接）不占锁。
        scope.launch {
            disconnectBounded(peripheral, id)
            connScope.cancel()
        }
        link(id).value = LinkState.DISCONNECTED
    }

    /** 限时断开（NonCancellable 内 3s 上限, 语义见 [teardown] KDoc）; 超时/异常均不外抛。 */
    private suspend fun disconnectBounded(peripheral: Peripheral, id: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(DISCONNECT_TIMEOUT) { runCatching { peripheral.disconnect() } }
                ?: Log.w(TAG, "disconnect timed out (>$DISCONNECT_TIMEOUT), abandon link $id")
        }
    }

    override suspend fun disconnect(deviceId: String) {
        // 同 [teardown]: 断开 fire-and-forget（调用方不被库内挂起拖死）+ 断链后才取消订阅域
        // （Mutex 卡死修: 取消触发的 CCC 收尾写不得先于物理断链, 语义见 teardown 注释）。
        conns.remove(deviceId)?.let { c ->
            scope.launch {
                disconnectBounded(c.peripheral, deviceId)
                c.scope.cancel()
            }
        }
        discoveredServices16.remove(deviceId)
        link(deviceId).value = LinkState.DISCONNECTED
    }

    override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)

    override fun notifications(deviceId: String): Flow<BleNotification> = notifyFlow(deviceId)

    /** 连接后发现的 16-bit 短码服务表(会话宿主 confirm 用); 未连接/未发现回空. */
    override fun discoveredService16s(deviceId: String): List<String> = discoveredServices16[deviceId] ?: emptyList()

    /**
     * 已协商 MTU（观测）: Nordic 2.0 无直取 MTU 的 API, 由 maximumWriteValueLength(WITHOUT_RESPONSE)+3 反推
     * （WITHOUT_RESPONSE 可写长度 = min(mtu-3, 512), S7 量级下即 ATT_MTU-3）。
     * 返回原始协商 MTU（如 247）; 未连接/未协商回 23。连接期间 MTU 收敛由 [awaitMtuConverged] 保证。
     */
    override fun negotiatedMtu(deviceId: String): Int =
        conns[deviceId]?.let { peripheralMtu(it.peripheral) } ?: ATT_MTU_DEFAULT

    /** 直接从 peripheral 反推原始 MTU（不经 conns——连接流程中 Conn 尚未入表时亦可读）; 未连接/异常回 23。 */
    private fun peripheralMtu(peripheral: Peripheral): Int =
        runCatching { peripheral.maximumWriteValueLength(WriteType.WITHOUT_RESPONSE) + ATT_WRITE_HEADER }
            .getOrDefault(ATT_MTU_DEFAULT)

    /**
     * 等待 MTU 协商收敛（置 CONNECTED 前调用; OTA sliceMax 推算的前置）。
     *
     * 库源码证据（tag 2.0.0-beta03, client-core-android `Peripheral`）:
     * - `requestHighestValueLength()` = `impl.events.onSubscription { impl.requestMtu(ATT_MTU_MAX) }`
     *   `.takeWhile { !it.isDisconnectionEvent }.filterIsInstance(MtuChanged::class).firstOrNull()`
     *   ——**挂起到 MtuChanged 事件到达**才返回。
     * - `maximumWriteValueLength(WITHOUT_RESPONSE)` = `min(mtu - 3, 512)`, 其中 `private var mtu = ATT_MTU_DEFAULT`
     *   由同事件流的处理器 `is MtuChanged -> mtu = event.mtu` 更新——即协商后的值经此属性暴露, 无独立 MTU 直读 API。
     *
     * 轮询兜底的原因: 内部 mtu 字段更新与 requestHighestValueLength 的恢复点是**同一 SharedFlow 的两个收集协程**,
     * 二者调度顺序无契约保证, 存在"请求已返回、字段未写入"的窗口; 另覆盖 requestMtu 失败但系统已代协商等边角。
     * 超时不算失败（个别设备可坚持默认 23）: 记警告并沿用现值。
     */
    private suspend fun awaitMtuConverged(peripheral: Peripheral): Int {
        var mtu = peripheralMtu(peripheral)
        if (mtu > ATT_MTU_DEFAULT) return mtu
        withTimeoutOrNull(MTU_CONVERGE_TIMEOUT) {
            while (mtu <= ATT_MTU_DEFAULT) {
                delay(MTU_POLL_MS)
                mtu = peripheralMtu(peripheral)
            }
        } ?: Log.w(TAG, "MTU not converged within $MTU_CONVERGE_TIMEOUT, keep mtu=$mtu")
        return mtu
    }

    override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
        val conn = conns[deviceId] ?: return
        // char16=null 写默认写特征（现状）; 否则按 16-bit 短码寻址, 查无静默丢弃（同「未连接静默丢弃」契约）。
        val ch = if (char16 == null) {
            conn.writeChar ?: return
        } else {
            conn.writeChars[extract16(char16) ?: return] ?: return
        }
        if (link(deviceId).value != LinkState.CONNECTED) return
        if (FRAME_LOG) Log.d(TAG, "TX ${ch.uuid} -> ${bytes.toHex()}")
        try {
            // 写流控由库内保证（见类 KDoc「写流控证据」）: RemoteCharacteristic.write 内部 OperationMutex 串行 +
            // 挂起等 CharacteristicWrite 完成事件（源自 onCharacteristicWrite）, 对 WITHOUT_RESPONSE 亦然——
            // 等价 AndroidBleClient 的 Mutex+等回调, 故此处直接映射, 不再叠加自写 Mutex。
            ch.write(bytes, conn.writeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 写失败不上抛（同「未连接静默丢弃」契约）: 可靠性由上层命令队列超时兜底。
            Log.w(TAG, "write failed ${ch.uuid}: ${e.message}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "BtNordicBle"
        // 现状探测路径(spec==null)的已知通道短码——B2A(RX+TX 同在即认定)/HRS 心率测量。
        // 声明式路径(spec!=null)由各 profile 的 GattSpec 提供; 探测路径是迁移期遗留兜底(设计 §3.4),
        // 故这些 S7/HRS 短码在本层自持, 不再耦合 s7 包的识别工具(W5 去耦)。
        private const val PROBE_B2A_TX_16 = "FFE2" // B2A 表->App Notify
        private const val PROBE_B2A_RX_16 = "FFE1" // B2A App->表 Write Without Response
        private const val PROBE_HRS_MEASURE_16 = "2A37" // 标准心率测量
        /**
         * 逐帧 TX/RX hex 日志开关（编译期常量, **默认关**）: 排查帧级问题（golden log 工作法）改 true 重编。
         * 常开会拖慢 OTA 吞吐——W1.6 首轮 A/B 实测 24MB 传输 logcat 4.4 万行, 均速 42 vs 自写基线 51.7 KB/s
         * （自写路径无逐帧日志, 对照不公平）; hex 字符串构造本身也是每帧开销, 故调用点整块 if 门控（常量折叠零残留）。
         */
        private const val FRAME_LOG = false
        /** WITHOUT_RESPONSE 单写 ATT 头（Write Command）: 由可写长度反推原始 MTU 用。 */
        private const val ATT_WRITE_HEADER = 3
        /** BLE 默认 ATT MTU（未协商基线; 库内 mtu 字段初值同此）。 */
        private const val ATT_MTU_DEFAULT = 23
        private val CONNECT_TIMEOUT = 15.seconds
        private val SERVICES_TIMEOUT = 10.seconds
        private val SUBSCRIBE_TIMEOUT = 5.seconds
        /** 断开等待硬上限（Bug2, 见 [teardown]）: 库内断链 await 可无限挂起, 超时放弃等待但收尾必达。 */
        private val DISCONNECT_TIMEOUT = 3.seconds
        /** MTU 收敛轮询（见 [awaitMtuConverged]）: 正常在首轮/一两轮内命中, 超时仅边角。 */
        private val MTU_CONVERGE_TIMEOUT = 2.seconds
        private const val MTU_POLL_MS = 50L
    }
}
