package io.bluetrace.shared.data

import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.QualitySummary
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.SessionFile
import io.bluetrace.shared.domain.SessionFileCategory
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.StopReason
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * 会话文件夹仓库（数据 Tab 列表/详情/删除 + 进程恢复扫描）。读 `session_manifest.json` 还原 [SessionSummary]。
 * 纯 commonMain（注入 okio [FileSystem]），可 JVM 单测。
 */
class SessionStore(
    private val fileSystem: FileSystem,
    private val sessionsRoot: Path,
) {
    fun ensureRoot() {
        fileSystem.createDirectories(sessionsRoot)
    }

    fun writeManifest(layout: SessionLayout, manifest: SessionManifest) {
        fileSystem.createDirectories(layout.sessionDir)
        // 原子替换：先写临时文件再 atomicMove。直接 truncate 覆盖写时，结束时刻被杀会留下截断 JSON，
        // 该会话将从列表/开口扫描中静默消失（raw 在盘上但 App 里不可见）。
        val tmp = layout.sessionDir / (SessionLayout.MANIFEST_NAME + ".tmp")
        fileSystem.sink(tmp).buffer().use {
            it.writeUtf8(BlueTraceJson.encodeToString(manifest))
        }
        fileSystem.atomicMove(tmp, layout.manifest)
    }

    fun readManifest(folderName: String): SessionManifest? {
        val path = sessionsRoot / folderName / SessionLayout.MANIFEST_NAME
        return readManifestAt(path)
    }

    private fun readManifestAt(path: Path): SessionManifest? {
        if (!fileSystem.exists(path)) return null
        return runCatching {
            val text = fileSystem.source(path).buffer().use { it.readUtf8() }
            BlueTraceJson.decodeFromString<SessionManifest>(text)
        }.getOrNull()
    }

    /** 所有会话摘要，按起点时间倒序（最新在前）。 */
    fun list(): List<SessionSummary> {
        if (!fileSystem.exists(sessionsRoot)) return emptyList()
        return fileSystem.list(sessionsRoot)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .mapNotNull { dir -> readManifestAt(dir / SessionLayout.MANIFEST_NAME)?.let(::toSummary) }
            .sortedByDescending { it.startEpochMs }
    }

    fun detail(folderName: String): SessionSummary? = readManifest(folderName)?.let(::toSummary)

    fun delete(folderName: String) {
        val dir = sessionsRoot / folderName
        if (fileSystem.exists(dir)) fileSystem.deleteRecursively(dir)
    }

    /**
     * 事后改采集人 / 场景（结束摘要 结束A / 会话详情 数据C 共用，§0.3）：
     * 重写该会话 `manifest`（alias + 体征 + 主/子场景英文 token）**并**按新 5 段名重命名会话文件夹。
     * 原始 HEX 等内容不动；新名冲突或移动失败 → 回滚（不改任何东西）并返回 null。
     */
    fun editSession(
        folderName: String,
        newAlias: String,
        newSex: Sex,
        newBirth: String,
        newHeightCm: Int?,
        newWeightKg: Double?,
        scene: SceneSelection,
    ): SessionSummary? {
        val m = readManifest(folderName) ?: return null
        val deviceAddr = m.devices.firstOrNull { it.role == DeviceKind.DUT.name }?.address
            ?: m.devices.firstOrNull()?.address
        val newFolder = sessionFolderName(scene, newAlias, m.startEpochMs, m.utcOffsetSeconds, deviceAddr)
        val oldDir = sessionsRoot / folderName
        val newDir = sessionsRoot / newFolder
        val renaming = newFolder != folderName
        if (renaming && fileSystem.exists(newDir)) return null // 命名冲突，回滚（未动）

        val updated = m.copy(
            sessionId = newFolder,
            folderName = newFolder,
            subject = ManifestSubject(newAlias, newSex, newBirth, newHeightCm, newWeightKg),
            mainScene = scene.mainToken,
            subScene = scene.subToken,
        )
        return runCatching {
            if (renaming) {
                fileSystem.atomicMove(oldDir, newDir)
                writeManifest(SessionLayout(newDir), updated)
            } else {
                writeManifest(SessionLayout(oldDir), updated)
            }
            toSummary(updated)
        }.getOrElse {
            // 移动/写 manifest 失败：尽量回滚（若已移走则移回），不抛。
            // writeManifest 是原子替换 → 半途失败时目录里仍是完好的旧 manifest，移回后状态一致。
            if (renaming && fileSystem.exists(newDir) && !fileSystem.exists(oldDir)) {
                runCatching { fileSystem.atomicMove(newDir, oldDir) }
            }
            null
        }
    }

    /** 没写结束标记的「开口」会话（进程被全杀后遗留，§5.10）。 */
    fun openSessions(): List<SessionManifest> {
        if (!fileSystem.exists(sessionsRoot)) return emptyList()
        return fileSystem.list(sessionsRoot)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .mapNotNull { readManifestAt(it / SessionLayout.MANIFEST_NAME) }
            .filter { it.stopReason == null }
    }

    /**
     * 开口会话自动收尾（§5.10）：endEpochMs = 最后一条记录时间（估为最后修改时间或现在），
     * stopReason=interrupted，并补 files 清单 → 变成数据 Tab 一条正常会话。
     */
    fun autoFinalizeOpenSession(manifest: SessionManifest, endEpochMs: Long): SessionSummary {
        val layout = SessionLayout(sessionsRoot / manifest.folderName)
        val files = scanFiles(layout)
        val finalized = manifest.copy(
            endEpochMs = endEpochMs,
            stopReason = StopReason.INTERRUPTED.id,
            files = files.map { it.toManifestFile() },
        )
        writeManifest(layout, finalized)
        return toSummary(finalized)
    }

    private fun scanFiles(layout: SessionLayout): List<SessionFile> {
        val result = ArrayList<SessionFile>()
        fun addIfExists(path: Path, category: SessionFileCategory) {
            val md = fileSystem.metadataOrNull(path) ?: return
            val rel = path.toString().removePrefix(layout.sessionDir.toString()).trimStart('/', '\\').replace('\\', '/')
            result += SessionFile(rel, category, md.size ?: 0L)
        }
        if (fileSystem.exists(layout.rawDir)) {
            fileSystem.list(layout.rawDir).forEach { addIfExists(it, SessionFileCategory.RAW_HEX) }
        }
        if (fileSystem.exists(layout.csvDir)) {
            fileSystem.list(layout.csvDir).forEach {
                val cat = if (it.name == "ppg_acc.csv") SessionFileCategory.COMBO_CSV else SessionFileCategory.DECODED_CSV
                addIfExists(it, cat)
            }
        }
        addIfExists(layout.gpsCsv, SessionFileCategory.GNSS_CSV)
        return result
    }

    private fun toSummary(m: SessionManifest): SessionSummary {
        val files = m.files.map { it.toSessionFile() }
        return SessionSummary(
            sessionId = m.sessionId,
            folderName = m.folderName,
            startEpochMs = m.startEpochMs,
            endEpochMs = m.endEpochMs ?: m.startEpochMs,
            subjectAlias = m.subject.alias,
            scene = SceneSelection(m.mainScene, m.subScene),
            deviceCount = m.devices.size,
            sensorCount = m.sampling.enabledTypes.size,
            totalLines = files.sumOf { it.lineCount },
            totalBytes = files.sumOf { it.sizeBytes },
            files = files,
            quality = QualitySummary(m.quality.reconnectCount, m.quality.disconnectTotalMs, m.quality.droppedPackets),
            stopReason = m.stopReason?.let { id -> StopReason.entries.firstOrNull { it.id == id } } ?: StopReason.NORMAL,
            enabledTypes = m.sampling.enabledTypes.mapNotNull { id -> CollectType.entries.firstOrNull { it.id == id } }.toSet(),
            gnssEnabled = m.gnssEnabled,
        )
    }
}

private fun ManifestFile.toSessionFile(): SessionFile =
    SessionFile(path, categoryOf(category), sizeBytes, lineCount)

private fun SessionFile.toManifestFile(): ManifestFile =
    ManifestFile(relativePath, category.name, lineCount, sizeBytes)

private fun categoryOf(name: String): SessionFileCategory =
    SessionFileCategory.entries.firstOrNull { it.name == name } ?: SessionFileCategory.DECODED_CSV
