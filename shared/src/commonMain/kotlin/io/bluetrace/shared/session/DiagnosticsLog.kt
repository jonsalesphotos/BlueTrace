package io.bluetrace.shared.session

import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, WARN, ERROR }

/** 全局诊断日志一条（应用日志 · 设置E）。运行期出错/事件，跨会话一份、**不进会话文件夹**（§5.4）。 */
data class DiagnosticEntry(
    val epochMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** 应用日志（运行错误/事件，非开发调试日志）。坏包/CRC/解码失败/未知 msgType/重连 → 进这里，不打断采集。 */
interface DiagnosticsLog {
    val entries: StateFlow<List<DiagnosticEntry>>
    fun add(level: LogLevel, tag: String, message: String)
    fun clear()
    fun export(): String
}

/** 内存滚动实现（上限自动截断）。v1 跨进程不持久（app 可包一层落盘）。 */
class InMemoryDiagnosticsLog(
    private val clock: EpochClock,
    private val capacity: Int = 500,
) : DiagnosticsLog {
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    override val entries: StateFlow<List<DiagnosticEntry>> = _entries

    override fun add(level: LogLevel, tag: String, message: String) {
        val e = DiagnosticEntry(clock.nowMs(), level, tag, message)
        _entries.update { cur -> (cur + e).takeLast(capacity) }
    }

    override fun clear() {
        _entries.value = emptyList()
    }

    override fun export(): String =
        _entries.value.joinToString("\n") { "${it.epochMs} ${it.level} [${it.tag}] ${it.message}" }
}
