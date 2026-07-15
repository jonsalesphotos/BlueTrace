package io.bluetrace.data.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备固件日志存储（app 级单例）：拉取的设备固件日志经 **MediaStore** 落到**公共**
 * `Download/BlueTrace/log/firmware/`（免存储权限、文件管理器可直接翻看），可列举、按名读取。
 *
 * 文件名以 **MAC 区分**：`s7_devlog_<MAC无冒号大写>_<时间戳>.log`。
 * minSdk 29（Android 10）起用 RELATIVE_PATH scoped storage，无需 WRITE/READ 运行时权限。
 *
 * 迁移链（首次 [list] 时一次性跑，幂等）：
 * - 早期 app 私有 `<外部文件目录>/devlogs/`（adb 才能取）→ 公共目录；
 * - v7 公共 `Download/BlueTrace/logs/` 混放目录 → v8 `log/firmware/`（2026-07-14 目录树重组）。
 */
class DeviceLogStore(private val context: Context) {

    data class Entry(val name: String, val sizeBytes: Long, val modifiedMs: Long)

    private val collection get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** MediaStore 里 RELATIVE_PATH 存为带尾斜杠形式。 */
    private val relPath = PublicTree.relPath(PublicTree.LOG_FIRMWARE)

    /** 展示用目录（顶栏副标题 / toast）。 */
    private val displayDir = PublicTree.display(PublicTree.LOG_FIRMWARE)

    /** v7 遗留公共混放目录（迁移来源）。 */
    private val legacyPublicDir = PublicTree.display(PublicTree.LEGACY_LOGS)

    /** 遗留的 app 私有目录（迁移来源）。 */
    private val legacyDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "devlogs")

    fun dirPath(): String = displayDir

    /** 存一条日志到公共 Download，返回展示路径。mac 用于区分设备。 */
    suspend fun save(bytes: ByteArray, mac: String, ts: String): String = withContext(Dispatchers.IO) {
        val macCompact = mac.filter { it.isLetterOrDigit() }.uppercase()
        val name = "s7_devlog_${macCompact}_$ts.log"
        writeToDownloads(name, bytes) ?: displayDir
    }

    /**
     * 迁移链（幂等；App 启动后台 + 首次列举各跑一次）：
     * 私有 `devlogs/` → 公共；旧公共 `logs/` **整目录合并**进 `log/`（s7_devlog* → `log/firmware/`，
     * 其余 App 侧日志 → `log/app/`）；`.log.txt` 改名。搬空后尽力删除遗留空目录。
     */
    suspend fun migrateLegacyDirs() = withContext(Dispatchers.IO) {
        migrateLegacy()
        migrateLegacyPublicDir()
        renameLegacyTxtToLog()
    }

    /** 列举全部设备日志（新→旧）。列前先跑迁移链。 */
    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        migrateLegacy()
        migrateLegacyPublicDir()
        renameLegacyTxtToLog()
        val projection = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        // 限定在 logs 目录、只取本 app 拉取的设备日志（s7_devlog 前缀）
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$displayDir%", "s7_devlog%")
        val sort = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        val out = mutableListOf<Entry>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val si = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val mi = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (c.moveToNext()) {
                // DATE_MODIFIED 是 epoch **秒**，转毫秒
                out += Entry(c.getString(ni), c.getLong(si), c.getLong(mi) * 1000L)
            }
        }
        out
    }

    /** 按文件名读取内容（原始字节 → 文本，字节保真）。 */
    suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf("$displayDir%", name)
        var result: String? = null
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val uri = ContentUris.withAppendedId(collection, id)
                result = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
        }
        result
    }

    /** 写字节到 `Download/<destRel>/<name>`（默认固件日志目录），返回展示路径（失败返回 null）。 */
    private fun writeToDownloads(name: String, bytes: ByteArray, destRel: String = relPath): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            // 用 octet-stream 而非 text/plain：后者会被 MediaStore 强制补 .txt（.log→.log.txt）
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, destRel)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run { resolver.delete(uri, null, null); return null }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$displayDir/$name"
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * v7→v8：旧公共 `Download/BlueTrace/logs/` **整目录合并**进 `log/`（用户 2026-07-14：log 与 logs 重复）。
     * 分流：`s7_devlog*`（手表拉的固件日志）→ `log/firmware/`；其余（bluetrace_log/s7_oplog 等 App 侧产物）
     * → `log/app/`。优先 MediaStore 原地改 RELATIVE_PATH（物理移动）；个别 ROM 不支持 → 回退读字节重写+删旧行。
     * 幂等：旧目录搬空后查询命中 0 即空转；搬空后尽力删除遗留空目录（删不掉留空壳无害）。
     */
    private fun migrateLegacyPublicDir() {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$legacyPublicDir%")
        val hits = mutableListOf<Pair<Long, String>>()
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idi = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) hits += c.getLong(idi) to c.getString(ni)
        }
        var failed = 0
        for ((id, name) in hits) {
            val uri = ContentUris.withAppendedId(collection, id)
            val destRel = if (name.startsWith("s7_devlog")) relPath else PublicTree.relPath(PublicTree.LOG_APP)
            try {
                val v = ContentValues().apply { put(MediaStore.Downloads.RELATIVE_PATH, destRel) }
                resolver.update(uri, v, null, null)
            } catch (e: Exception) {
                // 改 RELATIVE_PATH 失败（ROM 差异 / 非本 app 行）→ 回退复制+删除；再失败则留在原地下次重试
                try {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: run { failed++; continue }
                    if (writeToDownloads(name, bytes, destRel) != null) resolver.delete(uri, null, null) else failed++
                } catch (e2: Exception) {
                    failed++ // 留在旧目录，不阻塞列举
                }
            }
        }
        if (failed == 0) {
            // 全部搬空 → 尽力删空目录（scoped storage 下可能无权限，失败即留空壳）
            runCatching {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PublicTree.LEGACY_LOGS).delete()
            }
        }
    }

    /** 把遗留私有目录 `devlogs/` 的日志逐个搬进 Download，成功后删原件（幂等：搬空后即空转）。 */
    private fun migrateLegacy() {
        val files = legacyDir.listFiles()?.filter { it.isFile } ?: return
        for (f in files) {
            try {
                if (writeToDownloads(f.name, f.readBytes()) != null) f.delete()
            } catch (_: Exception) {
                // 迁移失败：保留原文件，下次 list 再试
            }
        }
    }

    /** 早期用 text/plain 导出的 `s7_devlog_*.log.txt` 尽力改名回 `.log`（非本 app 拥有的旧文件会失败，跳过）。 */
    private fun renameLegacyTxtToLog() {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$displayDir%", "s7_devlog%.log.txt")
        val hits = mutableListOf<Pair<Long, String>>()
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idi = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) hits += c.getLong(idi) to c.getString(ni)
        }
        for ((id, name) in hits) {
            val newName = name.removeSuffix(".txt")
            if (newName == name) continue
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, newName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                }
                resolver.update(ContentUris.withAppendedId(collection, id), v, null, null)
            } catch (_: Exception) {
                // 非本 app 拥有的旧文件改名会抛 RecoverableSecurityException，跳过
            }
        }
    }
}
