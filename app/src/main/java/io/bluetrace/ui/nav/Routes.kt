package io.bluetrace.ui.nav

import kotlinx.serialization.Serializable

/** 类型安全 Navigation Compose 路由（@Serializable，D-V4-17 / §7.2）。 */
sealed interface Route {
    // 根级（启动屏由 Android12 SplashScreen 承载，无 Compose Splash 目的地）
    @Serializable data object PermissionGate : Route
    @Serializable data object Main : Route
    @Serializable data object PowerSaveGuide : Route // 启动F：从权限门控建议组进入（根级）

    // 三个 Tab 的嵌套子图标记（独立返回栈，§7.2①②）
    @Serializable data object CollectGraph : Route
    @Serializable data object DataGraph : Route
    @Serializable data object SettingsGraph : Route

    // 顶级 Tab 根目的地（仅这三个显示底部 Bar）
    @Serializable data object CollectHome : Route
    @Serializable data object DataHome : Route
    @Serializable data object SettingsHome : Route

    // 采集子链（隐藏 Bar，嵌 CollectGraph）
    @Serializable data object DeviceConnect : Route
    @Serializable data object SubjectSelect : Route
    @Serializable data class SubjectEdit(val subjectId: String? = null) : Route
    @Serializable data object CollectionRun : Route
    @Serializable data object SessionSummary : Route
    @Serializable data object BluetoothOff : Route // 启动E：蓝牙关检测处进入

    // 数据子页（嵌 DataGraph）
    @Serializable data class SessionDetail(val folderName: String) : Route

    // 设置子页（嵌 SettingsGraph）
    @Serializable data object EnvCheck : Route
    @Serializable data object ExportLocation : Route
    @Serializable data object Storage : Route
    @Serializable data object AppLog : Route
    @Serializable data object DeviceMaintenance : Route
    @Serializable data object About : Route
    @Serializable data object Appearance : Route // 外观：亮/暗/跟随系统（§8）
    @Serializable data object Language : Route // 语言：中/英单选，无跟随系统（设置H）
}

/** 顶级目的地（仅这三个显示底部 Bar，§5.1 / ③）。显示名走 strings 资源（见 BlueTraceApp.tabLabelRes）。 */
enum class TopLevel {
    COLLECT,
    DATA,
    SETTINGS,
}
