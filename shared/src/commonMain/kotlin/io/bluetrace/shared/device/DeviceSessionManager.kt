package io.bluetrace.shared.device

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一份设备会话(会话宿主缓存单位): 设备 id + 识别到的档案 + 控制面(profile 无 controlPlane 则 null).
 */
data class DeviceSession(
    val deviceId: String,
    val profile: DeviceProfile,
    val control: DeviceControl?,
)

/**
 * 设备会话宿主(应用级, 设计 V2 §3 分层图): 每设备一份会话生命周期——获取 / 复用 / 关闭.
 * 长事务不落 viewModelScope, 由构造注入的 app 级 [scope] 持有.
 *
 * **本波(W3)产出接口 + 实现 + 单测; 运行时消费方(VM/UI)后续接(W5).** 现有 VM 连接路径
 * (ConsoleConnect 等)不动, 故本波真机行为零变化.
 *
 * ⚠️ **gattSpec 激活**: [acquire] 用 `connect(device, profile.gattSpec)` 走 W1 声明式路径
 * (本类是该路径首个调用方).S7 现网连接走探测路径(spec=null)验证过, **spec 路径真机未跑过**——
 * spec 路径真机回归随 W5 一并做.
 */
class DeviceSessionManager(
    private val catalog: DeviceProfileCatalog,
    private val ble: BleClient,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
) {
    private val sessions = HashMap<String, DeviceSession>()

    // 串行 acquire/release: 保"每设备至多一份会话 + 重复 acquire 复用".app 级宿主串行获取足够
    // (多设备 OTA 本就串行编排); 跨设备并行获取属后续优化, 本波不做.
    private val mutex = Mutex()

    /**
     * 获取(或复用)设备会话: identify -> connect(**gattSpec 激活**) -> confirm 二次确认 -> 缓存.
     * - identify 无命中(不支持的设备) -> null(不连接);
     * - 连接后 linkState 非 CONNECTED(连接失败) -> null;
     * - confirm(false)(广播命中但服务表不符) -> 断开并 null;
     * - 成功 -> 缓存并返回 [DeviceSession](control 可空: profile 无 controlPlane 则 null).
     *
     * 重复 acquire 同设备复用已缓存会话(不重连).
     */
    suspend fun acquire(device: ScannedDevice): DeviceSession? = mutex.withLock {
        sessions[device.id]?.let { return@withLock it }

        val profile = catalog.identify(device) ?: return@withLock null

        // gattSpec 激活: W1 声明式发现 / 订阅路径(本类首个调用方).连接失败不抛业务异常, 以 linkState 收敛为准.
        ble.connect(device, profile.gattSpec)
        if (ble.linkState(device.id).value != LinkState.CONNECTED) return@withLock null

        // 连接后二次确认(服务表校验; 同解码侧 ProtocolProfile 先例): false=识别撤销, 断开归还.
        if (!profile.confirm(ble.discoveredService16s(device.id))) {
            ble.disconnect(device.id)
            return@withLock null
        }

        val control = profile.controlPlane?.create(ble, device.id, scope, clock, zone)
        val session = DeviceSession(device.id, profile, control)
        sessions[device.id] = session
        session
    }

    /** 取已缓存会话(不建立连接). */
    fun get(deviceId: String): DeviceSession? = sessions[deviceId]

    /**
     * 归还会话: 断链 + 关控制面清缓存.
     *
     * **连接保持语义**(§5.3 退出不断连)由调用方决定是否 release——退屏想保连接就**不要** release;
     * 明确要放开设备(让位其他设备 / 彻底断开)才 release.
     *
     * **先断链后 close**(2026-07-15 真机定序): 控制面 close 会取消订阅 collect, BLE 库(Nordic)在
     * 订阅 flow 取消时发 CCC disable 写——若链路仍在, 该写与紧随的 disconnect 在库内**全局操作锁**上
     * 竞态可挂死(挂死后同进程内一切后续 GATT 操作排队永不执行, 新连接服务发现 Discovering 超时,
     * 仅杀进程可解). 断链在前, 取消触发的收尾写直接短路(未连接), 不占锁.
     */
    suspend fun release(deviceId: String): Unit = mutex.withLock {
        val session = sessions.remove(deviceId) ?: return@withLock
        ble.disconnect(deviceId)
        session.control?.close()
    }
}
