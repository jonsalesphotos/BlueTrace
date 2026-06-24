package io.bluetrace.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 复现并锁定崩溃根因：API 31+ 且未授 BLUETOOTH_CONNECT 时，绝不能拉起 ACTION_REQUEST_ENABLE
 * （会抛 SecurityException 崩溃），必须落到蓝牙设置页。
 */
class BluetoothEnableTest {

    @Test
    fun api31_withoutConnectPermission_goesToSettings_notEnableDialog() {
        // 首启权限门控的真机场景：Android 13、BLUETOOTH_CONNECT 未授 → 复现崩溃的前置条件
        assertEquals(
            BluetoothEnableTarget.BLUETOOTH_SETTINGS,
            bluetoothEnableTarget(needsConnectPermission = true, connectGranted = false),
        )
    }

    @Test
    fun api31_withConnectPermission_usesEnableDialog() {
        assertEquals(
            BluetoothEnableTarget.SYSTEM_ENABLE_DIALOG,
            bluetoothEnableTarget(needsConnectPermission = true, connectGranted = true),
        )
    }

    @Test
    fun belowApi31_alwaysUsesEnableDialog() {
        // ≤30 不需要 BLUETOOTH_CONNECT，开启弹窗一律安全，与授予态无关
        assertEquals(
            BluetoothEnableTarget.SYSTEM_ENABLE_DIALOG,
            bluetoothEnableTarget(needsConnectPermission = false, connectGranted = false),
        )
        assertEquals(
            BluetoothEnableTarget.SYSTEM_ENABLE_DIALOG,
            bluetoothEnableTarget(needsConnectPermission = false, connectGranted = true),
        )
    }
}
