package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.DeviceLimits
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 已连接设备登记(应用级单例)。扁平列表 + 限额(DUT≤3 + 参考≤1, D-V4-3)。
 * "返回不断开(连接后台保持, §5.3)" → 跨屏共享此状态。BLE 实际连接走 [BleClient]。
 *
 * B2 事件驱动化: [add] 时对该设备启动 linkState 监听, 变为 [LinkState.DISCONNECTED]
 * 即自动 [remove]——被动断连(超距/没电/设备重启且重连放弃)不再依赖各调用方手动对齐;
 * [LinkState.RECONNECTING] 视为仍在册(协议栈自动重连中, 设备卡显示琥珀点)。
 * 调用方在明确断开时仍可主动 [remove](即时反馈, 与监听移除幂等)。
 */
class ConnectionRegistry(
    private val bleClient: BleClient,
    private val scope: CoroutineScope,
) {
    private val _connected = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val connected: StateFlow<List<ScannedDevice>> = _connected

    /** 已启动监听的设备 id(监听常驻不取消, 重复 add 复用; CAS 更新避免并发重复启动)。 */
    private val watched = MutableStateFlow<Set<String>>(emptySet())

    fun isConnected(id: String): Boolean = _connected.value.any { it.id == id }

    fun dutCount(): Int = _connected.value.count { it.kind == DeviceKind.DUT }
    fun referenceCount(): Int = _connected.value.count { it.kind == DeviceKind.REFERENCE }
    fun count(): Int = _connected.value.size

    /** 是否还能再连该 kind(达上限则不可, 第 4 台 DUT / 第 2 参考禁用)。 */
    fun canConnect(kind: DeviceKind): Boolean = when (kind) {
        DeviceKind.DUT -> dutCount() < DeviceLimits.MAX_DUT
        DeviceKind.REFERENCE -> referenceCount() < DeviceLimits.MAX_REFERENCE
    }

    fun add(device: ScannedDevice) {
        _connected.update { cur -> if (cur.any { it.id == device.id }) cur else cur + device }
        val before = watched.getAndUpdate { it + device.id }
        if (device.id !in before) {
            scope.launch {
                bleClient.linkState(device.id).collect { st ->
                    if (st == LinkState.DISCONNECTED) remove(device.id)
                }
            }
        }
    }

    fun remove(id: String) {
        _connected.update { cur -> cur.filterNot { it.id == id } }
    }

    fun clear() {
        _connected.value = emptyList()
    }
}
