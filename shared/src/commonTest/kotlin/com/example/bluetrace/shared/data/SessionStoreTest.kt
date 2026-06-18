package com.example.bluetrace.shared.data

import com.example.bluetrace.shared.domain.CollectMode
import com.example.bluetrace.shared.domain.Sex
import com.example.bluetrace.shared.domain.StopReason
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SessionStoreTest {

    private fun manifest(folder: String, end: Long?, reason: String?) = SessionManifest(
        sessionId = folder,
        folderName = folder,
        startEpochMs = 1779348600_000L,
        endEpochMs = end,
        timezone = "Asia/Shanghai",
        utcOffsetSeconds = 8 * 3600,
        subject = ManifestSubject("shb", Sex.MALE, "1992-5", 175, 75.0),
        mode = CollectMode.WEAR,
        sampling = ManifestSampling(listOf("ppg_g", "acc")),
        devices = listOf(ManifestDevice("DUT", "C4:7B:8D:0A:04:27", "BT-DUT-0427")),
        stopReason = reason,
    )

    @Test
    fun writeRead_manifestRoundtrip_unixStartAndTimezone() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        val layout = SessionLayout(root / "Wear_shb_1")
        store.writeManifest(layout, manifest("Wear_shb_1", end = 1779348700_000L, reason = "normal"))

        val read = store.readManifest("Wear_shb_1")!!
        assertEquals(1779348600_000L, read.startEpochMs)
        assertEquals("Asia/Shanghai", read.timezone)
        assertEquals(CollectMode.WEAR, read.mode)
        assertEquals("normal", read.stopReason)

        val summary = store.detail("Wear_shb_1")!!
        assertEquals("shb", summary.subjectAlias)
        assertEquals(StopReason.NORMAL, summary.stopReason)
        assertEquals(2, summary.sensorCount)
    }

    @Test
    fun list_sortsByStartDescending() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        store.writeManifest(SessionLayout(root / "a"), manifest("a", 1L, "normal").copy(startEpochMs = 1000))
        store.writeManifest(SessionLayout(root / "b"), manifest("b", 1L, "normal").copy(startEpochMs = 3000))
        store.writeManifest(SessionLayout(root / "c"), manifest("c", 1L, "normal").copy(startEpochMs = 2000))
        val ordered = store.list().map { it.folderName }
        assertEquals(listOf("b", "c", "a"), ordered)
    }

    @Test
    fun openSession_autoFinalize_setsInterrupted() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        // 开口会话：stopReason == null
        store.writeManifest(SessionLayout(root / "open1"), manifest("open1", end = null, reason = null))

        val opens = store.openSessions()
        assertEquals(1, opens.size)

        val summary = store.autoFinalizeOpenSession(opens[0], endEpochMs = 1779348999_000L)
        assertEquals(StopReason.INTERRUPTED, summary.stopReason)
        assertEquals(1779348999_000L, summary.endEpochMs)

        // 收尾后不再是开口会话
        assertEquals(0, store.openSessions().size)
        assertNotNull(store.detail("open1"))
    }

    @Test
    fun delete_removesFolder() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        store.writeManifest(SessionLayout(root / "z"), manifest("z", 1L, "normal"))
        assertNotNull(store.detail("z"))
        store.delete("z")
        assertNull(store.detail("z"))
    }
}
