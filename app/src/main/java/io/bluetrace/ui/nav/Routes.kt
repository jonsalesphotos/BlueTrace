package io.bluetrace.ui.nav

import kotlinx.serialization.Serializable

/** 类型安全 Navigation Compose 路由（@Serializable，D-V4-17 / §7.2）。 */
sealed interface Route {
    @Serializable data object Splash : Route
    @Serializable data object PermissionGate : Route
    @Serializable data object Main : Route

    // 顶级 Tab（显示底部 Bar）
    @Serializable data object CollectHome : Route
    @Serializable data object DataHome : Route
    @Serializable data object SettingsHome : Route

    // 采集子链（隐藏 Bar）
    @Serializable data object DeviceConnect : Route
    @Serializable data object SubjectSelect : Route
    @Serializable data class SubjectEdit(val subjectId: String? = null) : Route
    @Serializable data object CollectionRun : Route
    @Serializable data object SessionSummary : Route

    // 数据子页
    @Serializable data class SessionDetail(val folderName: String) : Route

    // 设置子页
    @Serializable data object EnvCheck : Route
    @Serializable data object Gnss : Route
    @Serializable data object ExportLocation : Route
    @Serializable data object Storage : Route
    @Serializable data object AppLog : Route
    @Serializable data object DeviceMaintenance : Route
    @Serializable data object About : Route
    @Serializable data object PowerSaveGuide : Route
    @Serializable data object BluetoothOff : Route
}

/** 顶级目的地（仅这三个显示底部 Bar，§5.1 / ③）。显示名走 strings 资源（见 BlueTraceApp.tabLabelRes）。 */
enum class TopLevel {
    COLLECT,
    DATA,
    SETTINGS,
}
