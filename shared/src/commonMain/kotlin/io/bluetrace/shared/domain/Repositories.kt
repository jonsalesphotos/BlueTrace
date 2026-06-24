package io.bluetrace.shared.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 用户（Subject）本地 CRUD（§7.3，F-SUBJ-1）。app 用 DataStore 实现；可多条、可选当前。
 */
interface SubjectRepository {
    val subjects: Flow<List<Subject>>
    val currentId: Flow<String?>
    suspend fun upsert(subject: Subject)
    suspend fun delete(id: String)
    suspend fun setCurrent(id: String?)
}

/**
 * 环境与权限状态源（§5.2）。app 读真实蓝牙开关 + 运行时权限；commonMain 只认状态模型。
 */
interface EnvironmentRepository {
    val state: StateFlow<EnvironmentState>
    /** 静默复检（启动/回前台/系统设置返回后调用）。 */
    fun refresh()
    /** 标记某权限被"永久拒绝"（系统不再弹）→ 该条转 BLOCKED 态，引导去应用设置（§5.2 启动D）。 */
    fun markPermanentlyDenied(id: RequirementId)
}

/** 全局外观模式（§8）：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 界面语言（设置H）：仅 中 / 英，无"跟随系统"（red-line：language-zh-en-only）。
 * 仅切 UI 显示；会话文件名 / 场景 token / manifest 字段恒英文，不随此变。
 */
enum class AppLanguage { ZH, EN }

/** 首启标记等偏好（app 用 DataStore 实现）。 */
interface AppPreferences {
    val firstLaunchCompleted: Flow<Boolean>
    suspend fun setFirstLaunchCompleted(value: Boolean)
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(value: ThemeMode)
    val language: Flow<AppLanguage>
    suspend fun setLanguage(value: AppLanguage)
}
