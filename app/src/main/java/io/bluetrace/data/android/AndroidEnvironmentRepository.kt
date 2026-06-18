package io.bluetrace.data.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.EnvironmentState
import io.bluetrace.shared.domain.Requirement
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementSeverity
import io.bluetrace.shared.domain.RequirementStatus
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

    /** 被用户"永久拒绝"的条目（系统不再弹）→ BLOCKED。授予后自动清除。 */
    private val blocked = mutableSetOf<RequirementId>()

    override fun refresh() {
        _state.value = compute()
    }

    override fun markPermanentlyDenied(id: RequirementId) {
        blocked.add(id)
        _state.value = compute()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 缺失态再细分：若该条被标记永久拒绝则 BLOCKED；否则 MISSING。授予则清除标记。 */
    private fun missingOrBlocked(id: RequirementId, isGranted: Boolean): RequirementStatus = when {
        isGranted -> { blocked.remove(id); RequirementStatus.GRANTED }
        id in blocked -> RequirementStatus.BLOCKED
        else -> RequirementStatus.MISSING
    }

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
        missingOrBlocked(RequirementId.BLE_SCAN_CONNECT, BlueTracePermissions.hardScanConnect.all { granted(it) })

    private fun locationStatus(): RequirementStatus =
        missingOrBlocked(RequirementId.LOCATION, granted(Manifest.permission.ACCESS_FINE_LOCATION))

    private fun notificationsStatus(): RequirementStatus =
        missingOrBlocked(
            RequirementId.NOTIFICATIONS,
            BlueTracePermissions.notifications.isEmpty() || BlueTracePermissions.notifications.all { granted(it) },
        )

    private fun batteryStatus(): RequirementStatus {
        val pm = context.getSystemService(PowerManager::class.java) ?: return RequirementStatus.MISSING
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) RequirementStatus.GRANTED
        else RequirementStatus.MISSING
    }
}
