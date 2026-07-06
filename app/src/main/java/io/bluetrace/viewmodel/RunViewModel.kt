package io.bluetrace.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.domain.StopReason
import io.bluetrace.shared.session.RunLogLine
import io.bluetrace.shared.session.SessionController
import kotlinx.coroutines.launch

/** 采集运行（运行A/B/C/D）。包裹 [SessionController]：状态 / 实时流 / 标签 / 暂停 / 结束 / 演示注入。 */
class RunViewModel(private val controller: SessionController) : ViewModel() {

    val state = controller.state
    val finished = controller.finished
    val activeConfig get() = controller.activeConfig

    /** 实时流尾窗（有界，§5.6 锚底无滚动条）。 */
    val logLines = mutableStateListOf<RunLogLine>()

    init {
        viewModelScope.launch {
            controller.logLines.collect { line ->
                logLines.add(line)
                if (logLines.size > MAX_LINES) {
                    logLines.removeRange(0, logLines.size - MAX_LINES)
                }
            }
        }
    }

    fun pin(text: String) = controller.pin(text)
    fun toggleIntervalLabel(text: String) = controller.toggleIntervalLabel(text)
    fun setPaused(paused: Boolean) = controller.setDisplayPaused(paused)
    fun setEnabledTypes(types: Set<CollectType>) = controller.setEnabledTypes(types)
    fun setGnss(enabled: Boolean) = controller.setGnss(enabled)

    // 演示钩子（异常清单 §5.4）
    fun injectDisconnect() = controller.injectDisconnect()
    fun simulateStorageFull() = controller.simulateStorageFull()

    suspend fun stop(): SessionSummary? = controller.stop(StopReason.NORMAL)

    fun reset() = controller.reset()

    companion object {
        const val MAX_LINES = 250
    }
}
