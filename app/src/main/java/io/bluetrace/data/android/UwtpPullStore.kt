package io.bluetrace.data.android

import android.content.Context
import io.bluetrace.shared.uwtp.PullSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/**
 * UWTP 离线文件拉取的落盘与断点持久化(App 侧记录传输进度, 工作稿 D-7):
 *
 * ```
 * <externalFiles>/uwtp/<MAC>/
 *   file_<id>.col2         ← 完成件(FINISH 后由 .part 原子改名)
 *   file_<id>.part         ← 传输中(长度恒 == 已收连续偏移, 会话退出时已截齐)
 *   file_<id>.resume.json  ← 断点 {fileId, objectToken, totalSize, contiguous}
 * ```
 *
 * 续传契约: 断连/中止后凭 {contiguous + objectToken} 重新 BEGIN; OBJECT_CHANGED 时清断点从 0 重来。
 */
class UwtpPullStore(private val context: Context) {

    @Serializable
    data class Resume(
        val fileId: Long,
        val objectToken: Long,
        val totalSize: Long,
        val contiguous: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(mac: String): File =
        File(context.getExternalFilesDir(null), "uwtp/${sanitize(mac)}").apply { mkdirs() }

    private fun sanitize(mac: String): String = mac.replace(":", "").ifEmpty { "unknown" }

    fun partFile(mac: String, fileId: Long): File = File(dir(mac), "file_$fileId.part")

    fun finalFile(mac: String, fileId: Long): File = File(dir(mac), "file_$fileId.col2")

    private fun resumeFile(mac: String, fileId: Long): File = File(dir(mac), "file_$fileId.resume.json")

    /** 读断点(损坏/与 .part 不符则视为无断点并清理)。 */
    fun loadResume(mac: String, fileId: Long): Resume? {
        val f = resumeFile(mac, fileId)
        if (!f.exists()) return null
        val r = runCatching { json.decodeFromString<Resume>(f.readText()) }.getOrNull()
        val part = partFile(mac, fileId)
        if (r == null || r.fileId != fileId || r.contiguous <= 0 || !part.exists() || part.length() < r.contiguous) {
            clearResume(mac, fileId)
            return null
        }
        return r
    }

    fun saveResume(mac: String, resume: Resume) {
        runCatching { resumeFile(mac, resume.fileId).writeText(json.encodeToString(Resume.serializer(), resume)) }
    }

    fun clearResume(mac: String, fileId: Long) {
        resumeFile(mac, fileId).delete()
        partFile(mac, fileId).delete()
    }

    /** 完成收口: .part 改名为 .col2(旧完成件覆盖), 清断点。返回完成件。 */
    fun finalize(mac: String, fileId: Long): File? {
        val part = partFile(mac, fileId)
        val final = finalFile(mac, fileId)
        if (!part.exists()) return null
        final.delete()
        val ok = part.renameTo(final)
        resumeFile(mac, fileId).delete()
        return if (ok) final else null
    }
}

/** RandomAccessFile 定位写 sink(app 侧实现, 100MB 级不过内存)。 */
class RandomAccessPullSink(file: File) : PullSink {
    private val raf = RandomAccessFile(file, "rw")

    override fun writeAt(offset: Long, data: ByteArray) {
        raf.seek(offset)
        raf.write(data)
    }

    override fun truncate(size: Long) {
        raf.setLength(size)
    }

    override fun flush() {
        runCatching { raf.channel.force(false) }
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
