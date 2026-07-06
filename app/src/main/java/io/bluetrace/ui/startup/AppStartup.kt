package io.bluetrace.ui.startup

import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.session.RunStatus
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 启动决策结果 → 决定根导航起点 + 是否直落运行页 + 是否提示恢复。 */
data class StartDecision(
    val firstLaunch: Boolean,
    val collecting: Boolean,
    val recoveredCount: Int,
)

/**
 * 启动页展示规则（§5.1 / v2-B）。**进程级 flag**：
 * - **冷启动**（进程刚起，[coldHandled]=false）：做首启判定 + 进程恢复（开口会话自动收尾），系统 SplashScreen 一闪。
 * - **暖/热启动**（app 在后台存活、Activity 重建）：[coldHandled]=true → **不再走启动逻辑**，直接落当前界面
 *   （采集中 → 运行页，否则 → 采集 Tab）。
 */
object AppStartup {
    @Volatile
    private var coldHandled = false

    /** 冷启动探测（只读，不改状态）：true = 当前进程尚未处理过冷启动。用于应用内启动屏「只冷启展示一次」。 */
    fun peekCold(): Boolean = !coldHandled

    suspend fun decide(
        prefs: AppPreferences,
        store: SessionStore,
        clock: EpochClock,
        controller: SessionController,
    ): StartDecision {
        val collecting = controller.state.value.status == RunStatus.COLLECTING

        if (coldHandled) {
            // 暖/热启动：直接落当前，不展示启动逻辑、不重判首启/恢复
            return StartDecision(firstLaunch = false, collecting = collecting, recoveredCount = 0)
        }
        coldHandled = true

        // 冷启动：进程被全杀后无活会话 → 扫开口会话自动收尾（§5.10）
        var recovered = 0
        if (!collecting) {
            // SessionStore 自守 IO（架构评估 A3），调用方不再切线程
            val opens = store.openSessions()
            opens.forEach { store.autoFinalizeOpenSession(it, clock.nowMs()) }
            recovered = opens.size
        }
        val firstLaunch = !prefs.firstLaunchCompleted.first()
        return StartDecision(firstLaunch = firstLaunch, collecting = collecting, recoveredCount = recovered)
    }
}
