package io.bluetrace.data.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.bluetrace.R
import io.bluetrace.shared.data.StorageMonitor
import io.bluetrace.shared.data.StoragePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface ExportResult {
    data class Success(val displayPath: String) : ExportResult
    data class Error(val message: String) : ExportResult
    /** 导出D：存储不足（导出前预检阻断，§5.8）。 */
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ExportResult
}

/**
 * 整会话文件夹经 MediaStore 导出为 zip 到公共 `Download/BlueTrace/`（§6.4），无需任何存储运行时权限。
 * 流程：导出前存储预检 → 插入 IS_PENDING 记录 → openOutputStream 写 zip → 清 IS_PENDING。
 */
class MediaStoreExporter(
    private val context: Context,
    private val storageMonitor: StorageMonitor,
) {

    private fun sessionsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "sessions")
    }

    suspend fun exportSession(folderName: String, onProgress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val srcDir = File(sessionsDir(), folderName)
            if (!srcDir.exists() || !srcDir.isDirectory) {
                return@withContext ExportResult.Error(context.getString(R.string.export_err_no_folder))
            }
            val files = srcDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return@withContext ExportResult.Error(context.getString(R.string.export_err_empty))

            // 导出前真实存储预检（§5.8）：需 ≥ 待导出大小 × 余量系数
            val required = (files.sumOf { it.length() } * StoragePolicy.EXPORT_HEADROOM_FACTOR).toLong()
            val available = storageMonitor.usableBytes()
            if (available < required) {
                return@withContext ExportResult.InsufficientSpace(required, available)
            }

            val displayName = "$folderName.zip"
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueTrace")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(collection, values)
                ?: return@withContext ExportResult.Error(context.getString(R.string.export_err_create))

            try {
                resolver.openOutputStream(uri)?.use { os ->
                    ZipOutputStream(os.buffered()).use { zip ->
                        files.forEachIndexed { index, file ->
                            val entryName = file.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry("$folderName/$entryName"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            onProgress((index + 1f) / files.size)
                        }
                    }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return@withContext ExportResult.Error(context.getString(R.string.export_err_write))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                ExportResult.Success("Download/BlueTrace/$displayName")
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                ExportResult.Error(e.message ?: context.getString(R.string.export_err_generic))
            }
        }

    /** 应用日志导出到 `Download/BlueTrace/logs/`（设置E）。 */
    suspend fun exportLog(content: String, fileName: String): ExportResult =
        exportLogBytes(content.toByteArray(), fileName)

    /** 字节精确导出到公共 `Download/BlueTrace/logs/`（设备固件日志为二进制/ASCII，须字节保真）。 */
    suspend fun exportLogBytes(content: ByteArray, fileName: String): ExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueTrace/logs")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext ExportResult.Error(context.getString(R.string.export_err_log_create))
        try {
            resolver.openOutputStream(uri)?.use { it.write(content) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportResult.Success("Download/BlueTrace/logs/$fileName")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            ExportResult.Error(e.message ?: context.getString(R.string.export_err_generic))
        }
    }
}
