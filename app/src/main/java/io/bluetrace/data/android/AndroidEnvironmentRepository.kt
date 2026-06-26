package io.bluetrace.data.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.EnvironmentState
import io.bluetrace.shared.domain.Requirement
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementSeverity
import io.bluetrace.shared.domain.RequirementStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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

/**
 * 真实环境/权限状态源（§5.2）：蓝牙开关 + 运行时权限 + 电池优化。
 *
 * Flow-first 设计：[state] = 「蓝牙状态广播流 ⊕ 手动刷新触发器」combine → stateIn(WhileSubscribed)。
 * - 蓝牙：[bluetoothStateChanges] 用 callbackFlow 包系统广播；**订阅即发一次** → 每次界面回前台
 *   重订阅都按真实 adapter 状态重算，且 awaitClose 反注册，仅在被观察时持有接收器。
 * - 无广播信号的项（运行时权限 / 电池优化 / 回前台兜底）：调 [refresh] bump 触发器重算。
 *
 * 关键：小米/HyperOS「软关闭·明早自动开启」回到 App 时仍显示旧值，根因是后台广播被限流丢失或
 * 非标准下发——靠「回前台重订阅 + 屏幕 ON_RESUME 兜底 refresh」按当前 adapter.isEnabled 校正，
 * 而非只信任广播（见 ui/screen/permission/PermissionScreens.kt 的 LifecycleEventEffect）。
 */
class AndroidEnvironmentRepository(
    private val context: Context,
    scope: CoroutineScope,
) : EnvironmentRepository {

    /** 被用户"永久拒绝"的条目（系统不再弹）→ BLOCKED。授予后自动清除。
     *  恒在主线程访问（compute 经 flowOn(Main) 运行；markPermanentlyDenied 由 UI 调），无需并发保护。 */
    private val blocked = mutableSetOf<RequirementId>()

    /** 无系统广播的项（权限/电池）变化、或回前台兜底时 bump 一下 → combine 重新计算。 */
    private val refreshTrigger = MutableStateFlow(0L)

    override val state: StateFlow<EnvironmentState> =
        combine(bluetoothStateChanges(), refreshTrigger) { _, _ -> compute() }
            .flowOn(Dispatchers.Main.immediate) // compute()/blocked 恒在主线程，免数据竞争
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), compute())

    override fun refresh() {
        refreshTrigger.update { it + 1 }
    }

    override fun markPermanentlyDenied(id: RequirementId) {
        blocked.add(id)
        refresh()
    }

    /**
     * 蓝牙总开关变化流（callbackFlow 包 [BluetoothAdapter.ACTION_STATE_CHANGED] 系统广播）。
     * 订阅即先发一次 → 每次（重）订阅按当前真实状态重算；RECEIVER_NOT_EXPORTED 与
     * CollectionService 既有写法一致；awaitClose 反注册不漏。
     */
    private fun bluetoothStateChanges(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
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

    /** 蓝牙总开关：可读时以 adapter.isEnabled 为准；API 31+ 未授 CONNECT 时用系统开关设置兜底。 */
    private fun bluetoothStatus(): RequirementStatus {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val adapterEnabled = adapter?.let {
            if (canReadBluetoothAdapterState()) runCatching { it.isEnabled }.getOrNull()
            else null
        }
        return bluetoothSwitchStatus(
            hasAdapter = adapter != null,
            adapterEnabled = adapterEnabled,
            globalBluetoothOn = globalBluetoothOn(),
        )
    }

    private fun canReadBluetoothAdapterState(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun globalBluetoothOn(): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) == 1 }
            .getOrDefault(false)

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
