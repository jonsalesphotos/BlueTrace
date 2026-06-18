package com.example.bluetrace.domain

import com.example.bluetrace.shared.domain.DeviceKind
import com.example.bluetrace.shared.domain.DeviceLimits
import com.example.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 已连接设备登记（app 级单例）。扁平列表 + 限额（DUT≤3 + 参考≤1，D-V4-3）。
 * "返回不断开（连接后台保持，§5.3）" → 跨屏共享此状态。BLE 实际连接走 BleClient。
 */
class ConnectionRegistry {
    private val _connected = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val connected: StateFlow<List<ScannedDevice>> = _connected

    fun isConnected(id: String): Boolean = _connected.value.any { it.id == id }

    fun dutCount(): Int = _connected.value.count { it.kind == DeviceKind.DUT }
    fun referenceCount(): Int = _connected.value.count { it.kind == DeviceKind.REFERENCE }
    fun count(): Int = _connected.value.size

    /** 是否还能再连该 kind（达上限则不可，第 4 台 DUT / 第 2 参考禁用）。 */
    fun canConnect(kind: DeviceKind): Boolean = when (kind) {
        DeviceKind.DUT -> dutCount() < DeviceLimits.MAX_DUT
        DeviceKind.REFERENCE -> referenceCount() < DeviceLimits.MAX_REFERENCE
    }

    fun add(device: ScannedDevice) {
        _connected.update { cur -> if (cur.any { it.id == device.id }) cur else cur + device }
    }

    fun remove(id: String) {
        _connected.update { cur -> cur.filterNot { it.id == id } }
    }

    fun clear() {
        _connected.value = emptyList()
    }
}
