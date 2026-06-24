package io.bluetrace.shared.session

import io.bluetrace.shared.TEST_BASE_EPOCH
import io.bluetrace.shared.TestZone
import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileDiagnosticsLogTest {

    // TEST_BASE_EPOCH = 2026-05-21 15:30:00 +08（TestZone）
    private val day1 = "/logs".toPath() / "app-2026-05-21.log"
    private fun read(fs: FakeFileSystem, p: okio.Path) = fs.source(p).buffer().use { it.readUtf8() }
    private fun logNames(fs: FakeFileSystem) = fs.list("/logs".toPath()).map { it.name }.sorted()

    @Test
    fun add_writesFormattedLine_withMillis() = runTest {
        val fs = FakeFileSystem()
        var now = TEST_BASE_EPOCH + 123   // .123 毫秒
        val log = FileDiagnosticsLog(fs, "/logs".toPath(), EpochClock { now }, TestZone(),
            CoroutineScope(StandardTestDispatcher(testScheduler)))
        log.add(LogLevel.WARN, "ble", "disconnected")
        advanceUntilIdle()
        log.close()   // 关 sink 后才能读（FakeFileSystem 不允许读"写句柄打开中"的文件）
        assertEquals("2026-05-21 15:30:00.123 WARN [ble] disconnected\n", read(fs, day1))
        assertEquals(1, log.entries.value.size)   // 内存尾窗
    }

    @Test
    fun appendBlocking_writesSynchronously() {
        val fs = FakeFileSystem()
        val log = FileDiagnosticsLog(fs, "/logs".toPath(), EpochClock { TEST_BASE_EPOCH }, TestZone(),
            CoroutineScope(StandardTestDispatcher()))
        log.appendBlocking(LogLevel.ERROR, "crash", "boom")   // 同步：无需 advance
        log.close()
        assertTrue(read(fs, day1).endsWith("ERROR [crash] boom\n"))
    }

    @Test
    fun crossMidnight_rollsToNewFile() = runTest {
        val fs = FakeFileSystem()
        var now = TEST_BASE_EPOCH
        val log = FileDiagnosticsLog(fs, "/logs".toPath(), EpochClock { now }, TestZone(),
            CoroutineScope(StandardTestDispatcher(testScheduler)))
        log.add(LogLevel.INFO, "a", "d1"); advanceUntilIdle()
        now += 86_400_000L                 // +1 天
        log.add(LogLevel.INFO, "a", "d2"); advanceUntilIdle()
        assertEquals(listOf("app-2026-05-21.log", "app-2026-05-22.log"), logNames(fs))
    }

    @Test
    fun clear_deletesLogFiles_andEntries() = runTest {
        val fs = FakeFileSystem()
        val log = FileDiagnosticsLog(fs, "/logs".toPath(), EpochClock { TEST_BASE_EPOCH }, TestZone(),
            CoroutineScope(StandardTestDispatcher(testScheduler)))
        log.add(LogLevel.INFO, "a", "x"); advanceUntilIdle()
        assertTrue(logNames(fs).isNotEmpty())
        log.clear(); advanceUntilIdle()
        assertTrue(logNames(fs).isEmpty())
        assertFalse(log.entries.value.isNotEmpty())
    }

    @Test
    fun retain_prunesOldestBeyondRetainDays() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/logs".toPath())
        // 预置 10 个按日文件 app-2026-05-10..19.log
        (10..19).forEach { d -> fs.write("/logs".toPath() / "app-2026-05-$d.log") { writeUtf8("x") } }
        val log = FileDiagnosticsLog(fs, "/logs".toPath(), EpochClock { TEST_BASE_EPOCH }, TestZone(),
            CoroutineScope(StandardTestDispatcher(testScheduler)), retainDays = 7)
        advanceUntilIdle()   // init 的 pruneOld 异步执行
        val remain = logNames(fs)
        assertEquals(7, remain.size)
        assertEquals(listOf("app-2026-05-13.log", "app-2026-05-19.log"), listOf(remain.first(), remain.last()))
    }
}
