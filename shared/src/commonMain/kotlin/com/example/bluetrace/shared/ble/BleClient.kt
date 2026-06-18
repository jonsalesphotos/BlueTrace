package com.example.bluetrace.shared.ble

import com.example.bluetrace.shared.domain.LinkState
import com.example.bluetrace.shared.domain.ScannedDevice
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleNotification) return false
        return deviceId == other.deviceId &&
            receivedAtMs == other.receivedAtMs &&
            rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
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

    /** 连接（挂起到连上或失败）。 */
    suspend fun connect(device: ScannedDevice)

    /** 断开（再点断开 / 退出不调用——连接后台保持，§5.3）。 */
    suspend fun disconnect(deviceId: String)

    /** 每设备连接状态流（扫描行徽章 + 运行设备卡"重连中"）。 */
    fun linkState(deviceId: String): StateFlow<LinkState>

    /** 每设备原始 Notify 流（订阅即开始接收）。 */
    fun notifications(deviceId: String): Flow<BleNotification>
}
