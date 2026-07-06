package io.bluetrace.data.android

import android.content.Context

/**
 * Mock/真实 BLE 后端开关（重启生效）。
 * DI 在 Application.onCreate 同步初始化，故用 SharedPreferences 同步读，不走 DataStore（异步）。
 * 默认真实 GATT（2026-07-02 用户定"纯真实 BLE"）；Mock 供无设备演示/UI 回归
 * （设置页 DEBUG 行切换，或 adb：`adb shell am start ...` 前改 shared_prefs/bluetrace_dev.xml）。
 */
object BleBackendSwitch {
    private const val PREFS = "bluetrace_dev"
    private const val KEY_USE_MOCK = "use_mock_ble"

    fun useMock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_MOCK, false)

    fun setUseMock(context: Context, useMock: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_MOCK, useMock).apply()
    }
}
