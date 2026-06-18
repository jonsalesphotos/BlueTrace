package com.example.bluetrace.shared.data

import com.example.bluetrace.shared.domain.CollectType
import com.example.bluetrace.shared.domain.QualitySummary
import com.example.bluetrace.shared.domain.SessionFile
import com.example.bluetrace.shared.domain.SessionFileCategory
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.domain.StopReason
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
        fileSystem.sink(layout.manifest).buffer().use {
            it.writeUtf8(BlueTraceJson.encodeToString(manifest))
        }
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
            mode = m.mode,
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
