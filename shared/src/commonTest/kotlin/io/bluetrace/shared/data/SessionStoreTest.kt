package io.bluetrace.shared.data

import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.StopReason
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
        mainScene = "Wear",
        subScene = "Wearing",
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
        assertEquals("Wear", read.mainScene)
        assertEquals("Wearing", read.subScene)
        assertEquals("normal", read.stopReason)

        val summary = store.detail("Wear_shb_1")!!
        assertEquals("shb", summary.subjectAlias)
        assertEquals(SceneSelection("Wear", "Wearing"), summary.scene)
        assertEquals(StopReason.NORMAL, summary.stopReason)
        assertEquals(2, summary.sensorCount)
    }

    @Test
    fun editSession_renamesFolder_andRewritesManifest() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        val old = "Wear_Wearing_shb_20260521_153000_0427"
        store.writeManifest(SessionLayout(root / old), manifest(old, end = 1L, reason = "normal").copy(folderName = old, sessionId = old))

        // 改采集人 lina + 场景 HR/OutdoorRun → 期望按新 5 段名重命名
        val summary = store.editSession(old, "lina", Sex.FEMALE, "1990-2", 165, 55.0, SceneSelection("HR", "OutdoorRun"))!!
        val expected = "HR_OutdoorRun_lina_20260521_153000_0427"
        assertEquals(expected, summary.folderName)
        assertEquals(SceneSelection("HR", "OutdoorRun"), summary.scene)
        assertEquals("lina", summary.subjectAlias)
        // 旧夹消失、新夹存在、manifest 同步
        assertNull(store.detail(old))
        assertNotNull(store.detail(expected))
        val m = store.readManifest(expected)!!
        assertEquals("HR", m.mainScene)
        assertEquals("OutdoorRun", m.subScene)
        assertEquals("lina", m.subject.alias)
    }

    @Test
    fun editSession_conflict_returnsNull_andKeepsOriginal() {
        val fs = FakeFileSystem()
        val root = "/sessions".toPath()
        val store = SessionStore(fs, root)
        val a = "Wear_Wearing_shb_20260521_153000_0427"
        val bTarget = "HR_OutdoorRun_shb_20260521_153000_0427"
        store.writeManifest(SessionLayout(root / a), manifest(a, 1L, "normal").copy(folderName = a, sessionId = a))
        store.writeManifest(SessionLayout(root / bTarget), manifest(bTarget, 1L, "normal").copy(folderName = bTarget, sessionId = bTarget))
        // a 改成与 bTarget 同名 → 冲突 → null，且 a 原样保留
        val r = store.editSession(a, "shb", Sex.MALE, "1992-5", 175, 75.0, SceneSelection("HR", "OutdoorRun"))
        assertNull(r)
        assertNotNull(store.detail(a))
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
