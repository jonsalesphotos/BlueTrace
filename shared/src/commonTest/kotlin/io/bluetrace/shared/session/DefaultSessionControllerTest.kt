package io.bluetrace.shared.session

import io.bluetrace.shared.ble.MockBleClient
import io.bluetrace.shared.data.SessionLayout
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.domain.StopReason
import io.bluetrace.shared.dutAssigned
import io.bluetrace.shared.protocol.MockSampleDecoder
import io.bluetrace.shared.refAssigned
import io.bluetrace.shared.sessionConfig
import io.bluetrace.shared.TestZone
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultSessionControllerTest {

    private class Fixture(scope: TestScope) {
        val clock = virtualClock { scope.testScheduler.currentTime }
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        val diag = InMemoryDiagnosticsLog(clock)
        val mock = MockBleClient(clock, scope.backgroundScope, emitIntervalMs = 100, connectDelayMs = 600, reconnectDelayMs = 2000)
        val controller = DefaultSessionController(
            bleClient = mock,
            decoder = MockSampleDecoder(),
            fileSystem = fs,
            sessionsRoot = root,
            sessionStore = store,
            clock = clock,
            zone = TestZone(),
            diagnostics = diag,
            scope = scope.backgroundScope,
        )
        val dut = ScannedDevice("dut-0427", "BT-DUT-0427", "C4:7B:8D:0A:04:27", -52, DeviceKind.DUT)
        val ref = ScannedDevice("ref-h10", "Polar H10", "A0:9E:1A:55:0D:10", -60, DeviceKind.REFERENCE)
    }

    @Test
    fun fullSession_streamsData_thenStops_writesD6AndManifest() = runTest {
        val f = Fixture(this)
        backgroundScope.launch { f.mock.connect(f.dut) }
        backgroundScope.launch { f.mock.connect(f.ref) }
        advanceTimeBy(700); runCurrent()

        val config = sessionConfig(listOf(dutAssigned(), refAssigned()), start = f.clock.nowMs())
        f.controller.start(config)
        advanceTimeBy(2000); runCurrent()

        val running = f.controller.state.value
        assertEquals(RunStatus.COLLECTING, running.status)
        assertTrue(running.datasCount > 0, "should accumulate data lines")
        assertTrue(running.devices.all { it.link == LinkState.CONNECTED })
        assertTrue(running.devices.first { it.kind == DeviceKind.DUT }.activeSensors.isNotEmpty())
        assertTrue(running.elapsedMs >= 2000)
        // 参考心率带应有实时 HR
        assertNotNull(running.devices.first { it.kind == DeviceKind.REFERENCE }.hr)

        // 标签：Pin + Start 区间
        f.controller.pin("rest baseline")
        f.controller.toggleIntervalLabel("walk")
        advanceTimeBy(300); runCurrent()
        assertTrue(f.controller.state.value.labelIntervalOpen)

        val summary = f.controller.stop(StopReason.NORMAL)
        assertEquals(StopReason.NORMAL, summary.stopReason)
        assertTrue(summary.totalLines > 0)
        assertEquals(RunStatus.STOPPED, f.controller.state.value.status)
        assertNotNull(f.controller.finished.value)

        // D-6 文件夹落地
        val layout = SessionLayout(f.root / summary.folderName)
        assertTrue(f.fs.exists(layout.rawHex(DeviceKind.DUT)), "raw/dut.hexlog")
        assertTrue(f.fs.exists(layout.rawHex(DeviceKind.REFERENCE)), "raw/reference.hexlog")
        assertTrue(f.fs.exists(layout.csv("hr")), "csv/hr.csv")
        assertTrue(f.fs.exists(layout.manifest), "session_manifest.json")

        val manifest = f.store.readManifest(summary.folderName)!!
        assertEquals("normal", manifest.stopReason)
        assertEquals("Asia/Shanghai", manifest.timezone)
        assertNotNull(manifest.endEpochMs)
        assertTrue(manifest.startEpochMs > 0)
    }

    @Test
    fun storageFull_autoEnds_andSaves() = runTest {
        val f = Fixture(this)
        backgroundScope.launch { f.mock.connect(f.dut) }
        advanceTimeBy(700); runCurrent()
        f.controller.start(sessionConfig(listOf(dutAssigned()), start = f.clock.nowMs()))
        advanceTimeBy(1000); runCurrent()

        f.controller.simulateStorageFull()
        advanceTimeBy(300); runCurrent()

        val fin = f.controller.finished.value
        assertNotNull(fin)
        assertEquals(StopReason.STORAGE_FULL, fin.stopReason)
        assertEquals(RunStatus.STOPPED, f.controller.state.value.status)
        assertEquals("storage_full", f.store.readManifest(fin.folderName)!!.stopReason)
    }

    @Test
    fun injectDisconnect_reconnects_andCountsInQuality() = runTest {
        val f = Fixture(this)
        backgroundScope.launch { f.mock.connect(f.dut) }
        advanceTimeBy(700); runCurrent()
        f.controller.start(sessionConfig(listOf(dutAssigned()), start = f.clock.nowMs()))
        advanceTimeBy(500); runCurrent()

        f.controller.injectDisconnect()
        advanceTimeBy(50); runCurrent()
        assertTrue(f.controller.state.value.devices.any { it.link == LinkState.RECONNECTING })

        advanceTimeBy(2200); runCurrent()
        assertEquals(1, f.controller.state.value.reconnectCount)
        assertTrue(f.controller.state.value.devices.all { it.link == LinkState.CONNECTED })

        val summary = f.controller.stop()
        assertTrue(summary.quality.reconnectCount >= 1)
        assertTrue(summary.quality.disconnectTotalMs > 0)
    }

    @Test
    fun gnssEnabled_writesGpsCsv() = runTest {
        val f = Fixture(this)
        backgroundScope.launch { f.mock.connect(f.dut) }
        advanceTimeBy(700); runCurrent()
        f.controller.start(sessionConfig(listOf(dutAssigned()), gnss = true, start = f.clock.nowMs()))
        advanceTimeBy(2500); runCurrent()
        val summary = f.controller.stop()
        val layout = SessionLayout(f.root / summary.folderName)
        assertTrue(f.fs.exists(layout.gpsCsv), "gps.csv should exist when GNSS enabled")
    }
}
