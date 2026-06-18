package com.example.bluetrace.data.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.bluetrace.shared.domain.EnvironmentRepository
import com.example.bluetrace.shared.domain.EnvironmentState
import com.example.bluetrace.shared.domain.Requirement
import com.example.bluetrace.shared.domain.RequirementId
import com.example.bluetrace.shared.domain.RequirementSeverity
import com.example.bluetrace.shared.domain.RequirementStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 运行时权限请求清单（§5.2）。 */
object BlueTracePermissions {
    /** 硬性：附近设备（扫描/连接）。API 31+ 用新权限；≤30 走定位。 */
    val hardScanConnect: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    val location: Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val notifications: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
}

/** 真实环境/权限状态源（§5.2）：蓝牙开关 + 运行时权限 + 电池优化。 */
class AndroidEnvironmentRepository(private val context: Context) : EnvironmentRepository {

    private val _state = MutableStateFlow(compute())
    override val state: StateFlow<EnvironmentState> = _state

    override fun refresh() {
        _state.value = compute()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun compute(): EnvironmentState {
        val reqs = buildList {
            add(Requirement(RequirementId.BLUETOOTH_ON, RequirementSeverity.HARD, bluetoothStatus()))
            add(Requirement(RequirementId.BLE_SCAN_CONNECT, RequirementSeverity.HARD, scanConnectStatus()))
            add(Requirement(RequirementId.LOCATION, RequirementSeverity.SUGGESTED, locationStatus()))
            add(Requirement(RequirementId.NOTIFICATIONS, RequirementSeverity.SUGGESTED, notificationsStatus()))
            add(Requirement(RequirementId.BATTERY_UNRESTRICTED, RequirementSeverity.SUGGESTED, batteryStatus()))
        }
        return EnvironmentState(reqs)
    }

    private fun bluetoothStatus(): RequirementStatus {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: return RequirementStatus.OFF
        return if (adapter.isEnabled) RequirementStatus.GRANTED else RequirementStatus.OFF
    }

    private fun scanConnectStatus(): RequirementStatus =
        if (BlueTracePermissions.hardScanConnect.all { granted(it) }) RequirementStatus.GRANTED
        else RequirementStatus.MISSING

    private fun locationStatus(): RequirementStatus =
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) RequirementStatus.GRANTED
        else RequirementStatus.MISSING

    private fun notificationsStatus(): RequirementStatus =
        if (BlueTracePermissions.notifications.isEmpty() || BlueTracePermissions.notifications.all { granted(it) })
            RequirementStatus.GRANTED
        else RequirementStatus.MISSING

    private fun batteryStatus(): RequirementStatus {
        val pm = context.getSystemService(PowerManager::class.java) ?: return RequirementStatus.MISSING
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) RequirementStatus.GRANTED
        else RequirementStatus.MISSING
    }
}
