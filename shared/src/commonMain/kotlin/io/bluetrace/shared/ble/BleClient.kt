package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一条原始 Notify（设备→App）。真实端就是 GATT notification 的 value；与 [MockBleClient] 同形。
 * 落 `raw/<source>.hexlog`（`<receivedAtMs>: HEX`，source of truth，§6.1）+ 喂 SampleDecoder。
 */
data class BleNotification(
    val deviceId: String,
    val receivedAtMs: Long,
    val rawBytes: ByteArray,
    /** 来源特征 UUID（可空）：多特征设备（如 S7 的 FFE2 与自研 DUT 数据特征并存）按此分流解码；Mock/单特征可不填。 */
    val characteristicId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleNotification) return false
        return deviceId == other.deviceId &&
            receivedAtMs == other.receivedAtMs &&
            rawBytes.contentEquals(other.rawBytes) &&
            characteristicId == other.characteristicId
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + (characteristicId?.hashCode() ?: 0)
        return result
    }
}

/**
 * BLE Central 抽象（D-V4-19）：扫描 / 连接 / 断开 / 连接状态流 / Notify 流。
 * commonMain 接口，androidMain 后期接 Nordic Kotlin-BLE（iOS Core Bluetooth）；v1 用 [MockBleClient]。
 * App **不主动配链路参数**（MTU/连接间隔设备自定，D-5），只订阅观测。
 */
interface BleClient {
    /** 扫描窗口：持续 emit「当前可见设备」累积列表（去重）；上游取消即停扫描。 */
    fun scan(): Flow<List<ScannedDevice>>

    /**
     * 连接（挂起到连上或失败）。失败契约：**不抛业务异常**，以 `linkState(deviceId)` 收敛到
     * DISCONNECTED 为准（调用方以观测状态 + 超时兜底）；协程被取消时实现方必须释放底层链路资源。
     */
    suspend fun connect(device: ScannedDevice)

    /** 断开（再点断开 / 退出不调用——连接后台保持，§5.3）。 */
    suspend fun disconnect(deviceId: String)

    /** 每设备连接状态流（扫描行徽章 + 运行设备卡"重连中"）。 */
    fun linkState(deviceId: String): StateFlow<LinkState>

    /**
     * 系统蓝牙适配器开关变化（app 监听 ACTION_STATE_CHANGED 广播驱动，§5.4 横切A）。
     * "蓝牙关 → 已连设备转重连中"是**产品行为**而非 Mock 演示钩子，故进接口；
     * 真实实现可保持默认 no-op（适配器关闭时 GATT 会收到真实断连回调，无需模拟）。
     */
    fun onAdapterStateChanged(off: Boolean) {}

    /** 演示钩子（DEBUG 构建）：注入一次断联→自动重连。真实实现保持默认 no-op。 */
    fun debugInjectDisconnect(deviceId: String) {}

    /**
     * 已协商 MTU（观测，非配置——App 不主动配，D-5）。OTA 分片尺寸推算需要（sliceMaxSize=(MTU−15)×17）。
     * 返回**原始协商 MTU**（如 247，非 MTU−3 可用值）；每帧 3B ATT 写头由 [S7FileTrans.maxParamPerFrame] 扣除。
     * 未连接/未协商时返回 BLE 默认 ATT MTU 23（保守）。真实端在 onMtuChanged 回调里存下协商值。
     */
    fun negotiatedMtu(deviceId: String): Int = 23

    /** 每设备原始 Notify 流（订阅即开始接收）。 */
    fun notifications(deviceId: String): Flow<BleNotification>

    /**
     * 下行写（App→设备，命令通道）。真实端 = 对协议写特征 Write Without Response
     * （S7 = FFE1，自研 DUT = DUT_WRITE，UUID 由 profile 决定）；Mock 端路由到对应模拟应答器。
     * 设备未连接时静默丢弃（与真实 GATT 行为一致，可靠性由上层命令队列超时兜底）。
     */
    suspend fun write(deviceId: String, bytes: ByteArray)
}
