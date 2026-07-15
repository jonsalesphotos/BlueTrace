package io.bluetrace.data.android

import android.content.Context

/**
 * BLE 后端三向开关（重启生效）。优先级 Mock > Nordic > 自写（见 AppModule 的 single<BleClient>）。
 * DI 在 Application.onCreate 同步初始化，故用 SharedPreferences 同步读，不走 DataStore（异步）。
 * 默认真实自写 GATT（[AndroidBleClient]，2026-07-02 用户定"纯真实 BLE"）；
 * Mock 供无设备演示/UI 回归；Nordic（[NordicBleClient]，W1.5 引入）为 W1.6 真机 A/B 闸门候选默认。
 * （设置页 DEBUG 行切换，或 adb：`adb shell am start ...` 前改 shared_prefs/bluetrace_dev.xml）。
 */
object BleBackendSwitch {
    private const val PREFS = "bluetrace_dev"
    private const val KEY_USE_MOCK = "use_mock_ble"
    private const val KEY_USE_NORDIC = "use_nordic_ble"

    fun useMock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_MOCK, false)

    fun setUseMock(context: Context, useMock: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_MOCK, useMock).apply()
    }

    /** Nordic 实现开关（独立于 Mock；Mock 打开时优先 Mock，本开关不生效）。默认关（走自写 GATT）。 */
    fun useNordic(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_NORDIC, false)

    fun setUseNordic(context: Context, useNordic: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USE_NORDIC, useNordic).apply()
    }
}
