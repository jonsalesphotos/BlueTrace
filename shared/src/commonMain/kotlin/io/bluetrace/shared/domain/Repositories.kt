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
}

/** 首启标记等偏好（app 用 DataStore 实现）。 */
interface AppPreferences {
    val firstLaunchCompleted: Flow<Boolean>
    suspend fun setFirstLaunchCompleted(value: Boolean)
    val gnssEnabled: Flow<Boolean>
    suspend fun setGnssEnabled(value: Boolean)
}
