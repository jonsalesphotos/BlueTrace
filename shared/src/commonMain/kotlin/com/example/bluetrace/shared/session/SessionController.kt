package com.example.bluetrace.shared.session

import com.example.bluetrace.shared.domain.SessionConfig
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.domain.StopReason
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 采集会话编排（§7.3）。**Fake/真实实现都实现此接口**（v1 = [DefaultSessionController] 喂 Mock BLE）；
 * 协议冻结后换真实 BLE/解码，接口与上层 UI 不变。
 */
interface SessionController {
    val state: StateFlow<RunState>

    /** 实时流（每条 Notify 一行 `[MM:SS.cc] HEX` + 标签行，§5.6）。UI 维护有界尾窗。 */
    val logLines: SharedFlow<RunLogLine>

    /** 会话结束（任意路径：长按结束 / 存储满 / 收尾）后置为非空摘要 → UI 跳结束A。 */
    val finished: StateFlow<SessionSummary?>

    val activeConfig: SessionConfig?

    fun start(config: SessionConfig)

    /** 暂停 = 仅停数据框滚动显示（D-V4-11）。 */
    fun setDisplayPaused(paused: Boolean)

    /** Pin 瞬时点标签（§5.5）。 */
    fun pin(text: String)

    /** Start ⇄ Stop 区间标签切换（§5.5）。 */
    fun toggleIntervalLabel(text: String)

    /** 长按 2 秒结束 → 收尾落盘 → 结束摘要（§5.8）。 */
    suspend fun stop(reason: StopReason = StopReason.NORMAL): SessionSummary

    /** 演示：模拟存储满 → 自动结束并保存（§5.4）。 */
    fun simulateStorageFull()

    /** 演示：注入断联 → 设备卡内联"重连中" → 自动续写（§5.4）。 */
    fun injectDisconnect()

    /** 结束摘要消费完后回到就绪态。 */
    fun reset()
}
