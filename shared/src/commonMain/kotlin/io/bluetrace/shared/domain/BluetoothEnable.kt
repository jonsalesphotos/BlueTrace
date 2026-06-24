package io.bluetrace.shared.domain

/**
 * 「去开启蓝牙」按钮的落点（§5.2 启动E/启动B）。
 *
 * 根因：Android 12+(API 31) 拉起系统蓝牙开启弹窗 `BluetoothAdapter.ACTION_REQUEST_ENABLE`
 * 需先持有 `BLUETOOTH_CONNECT`；首启权限门控时该权限尚未授予，`startActivity` 抛
 * `SecurityException("...requires android.permission.BLUETOOTH_CONNECT")` → 崩溃。
 * 故未授时不碰开启弹窗，改去系统蓝牙设置页（用户可直接开蓝牙，且打开设置页不需该权限）。
 */
enum class BluetoothEnableTarget {
    /** 已具备条件：拉起系统「开启蓝牙？」弹窗（一键开）。 */
    SYSTEM_ENABLE_DIALOG,

    /** 缺 BLUETOOTH_CONNECT（会崩）：退而打开系统蓝牙设置页让用户手动开。 */
    BLUETOOTH_SETTINGS,
}

/**
 * @param needsConnectPermission 运行时是否需要 BLUETOOTH_CONNECT（API ≥ 31 为 true；≤30 不需要）。
 * @param connectGranted         是否已授予 BLUETOOTH_CONNECT。
 */
fun bluetoothEnableTarget(needsConnectPermission: Boolean, connectGranted: Boolean): BluetoothEnableTarget =
    if (needsConnectPermission && !connectGranted) BluetoothEnableTarget.BLUETOOTH_SETTINGS
    else BluetoothEnableTarget.SYSTEM_ENABLE_DIALOG
