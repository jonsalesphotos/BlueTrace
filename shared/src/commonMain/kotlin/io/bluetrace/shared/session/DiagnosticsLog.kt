package io.bluetrace.shared.session

import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer

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

/**
 * 滚动 .log 文件实现（v7）：每条事件追加写入 `logsDir/app-YYYY-MM-DD.log`（按天滚动、保留 [retainDays] 个最新），
 * 跨进程持久；同时保留内存尾窗（[entries]，capacity 上限）供「应用日志」屏实时显示（UI 不读盘）。
 *
 * **并发安全**：[writerScope] 必须是**单线程**（如 `Dispatchers.IO.limitedParallelism(1)`）。所有 [add] 的写入
 * 经 `writerScope.launch` 串行化、共享**单个长生命周期 [sink]**、每行 `flush`（durable、保序、无并发追加竞态）。
 * 行格式：`2026-06-24 14:00:39.123 WARN [ble] message`（本地时区；毫秒由 epochMs%1000 单取，因 parts 只到秒）。
 */
class FileDiagnosticsLog(
    private val fileSystem: FileSystem,
    private val logsDir: Path,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val writerScope: CoroutineScope, // 单线程！
    private val capacity: Int = 500,
    private val retainDays: Int = 7,
) : DiagnosticsLog {
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    override val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    private var sink: BufferedSink? = null   // 单个长生命周期 sink
    private var sinkDay: String? = null      // 当前 sink 所属 "YYYY-MM-DD"

    init {
        fileSystem.createDirectories(logsDir)          // 构造期只建目录（轻）
        writerScope.launch { pruneOld() }              // 清理旧文件异步（避免主线程磁盘 IO）
    }

    override fun add(level: LogLevel, tag: String, message: String) {
        val e = DiagnosticEntry(clock.nowMs(), level, tag, message)
        _entries.update { (it + e).takeLast(capacity) }   // 尾窗：StateFlow.update 原子
        writerScope.launch { appendLocked(e) }            // 文件：单线程串行保序
    }

    override fun clear() {
        _entries.value = emptyList()
        writerScope.launch { closeSink(); deleteAllLogs() }
    }

    override fun export(): String = readAllLogs()

    /** 崩溃专用（非接口）：在调用线程内同步落一条并 flush。崩溃 handler 须先 [cancelWriter] 再调它。 */
    fun appendBlocking(level: LogLevel, tag: String, message: String) =
        appendLocked(DiagnosticEntry(clock.nowMs(), level, tag, message))

    /** 停掉异步写（崩溃前调用），让 [appendBlocking] 独占 sink。 */
    fun cancelWriter() {
        writerScope.coroutineContext.cancelChildren()
    }

    /** flush + 关闭当前 sink（优雅关闭 / 测试用；下次写入自动重开当天文件）。 */
    fun close() = closeSink()

    private fun appendLocked(e: DiagnosticEntry) {
        val day = dayOf(e.epochMs)
        if (day != sinkDay) {
            closeSink()
            sink = fileSystem.appendingSink(logsDir / "app-$day.log").buffer()
            sinkDay = day
        }
        sink!!.writeUtf8(format(e)).writeUtf8("\n").flush()
    }

    private fun closeSink() {
        runCatching { sink?.close() }
        sink = null; sinkDay = null
    }

    private fun pruneOld() {
        if (!fileSystem.exists(logsDir)) return
        logFiles().sortedByDescending { it.name }.drop(retainDays)
            .forEach { runCatching { fileSystem.delete(it) } }
    }

    private fun deleteAllLogs() {
        if (!fileSystem.exists(logsDir)) return
        logFiles().forEach { runCatching { fileSystem.delete(it) } }
    }

    private fun readAllLogs(): String {
        if (!fileSystem.exists(logsDir)) return ""
        return logFiles().sortedBy { it.name }
            .joinToString("") { p -> runCatching { fileSystem.source(p).buffer().use { it.readUtf8() } }.getOrDefault("") }
    }

    private fun logFiles(): List<Path> =
        fileSystem.list(logsDir).filter { it.name.startsWith("app-") && it.name.endsWith(".log") }

    /** "YYYY-MM-DD"（连字符；与 dateCompact 的 yyyyMMdd 不同，故手拼）。 */
    private fun dayOf(epochMs: Long): String {
        val p = epochMsToLocalParts(epochMs, zone.offsetSeconds())
        return "${p.year}-${p2(p.month)}-${p2(p.day)}"
    }

    private fun format(e: DiagnosticEntry): String {
        val p = epochMsToLocalParts(e.epochMs, zone.offsetSeconds())
        val ms = e.epochMs.mod(1000L).toInt()
        return "${p.year}-${p2(p.month)}-${p2(p.day)} ${p2(p.hour)}:${p2(p.minute)}:${p2(p.second)}.${p3(ms)} ${e.level} [${e.tag}] ${e.message}"
    }

    private fun p2(v: Int) = if (v < 10) "0$v" else v.toString()
    private fun p3(v: Int) = v.toString().padStart(3, '0')
}
