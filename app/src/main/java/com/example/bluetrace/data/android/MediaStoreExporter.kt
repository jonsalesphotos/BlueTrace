package com.example.bluetrace.data.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface ExportResult {
    data class Success(val displayPath: String) : ExportResult
    data class Error(val message: String) : ExportResult
}

/**
 * 整会话文件夹经 MediaStore 导出为 zip 到公共 `Download/BlueTrace/`（§6.4），无需任何存储运行时权限。
 * 流程：插入 IS_PENDING 记录 → openOutputStream 写 zip → 清 IS_PENDING。
 */
class MediaStoreExporter(private val context: Context) {

    private fun sessionsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "sessions")
    }

    suspend fun exportSession(folderName: String, onProgress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val srcDir = File(sessionsDir(), folderName)
            if (!srcDir.exists() || !srcDir.isDirectory) {
                return@withContext ExportResult.Error("会话文件夹不存在")
            }
            val files = srcDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return@withContext ExportResult.Error("会话为空，无可导出内容")

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
                ?: return@withContext ExportResult.Error("无法创建导出文件（存储不可用）")

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
                    return@withContext ExportResult.Error("无法写入导出文件")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                ExportResult.Success("Download/BlueTrace/$displayName")
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                ExportResult.Error(e.message ?: "导出失败")
            }
        }

    /** 应用日志导出到 `Download/BlueTrace/logs/`（设置E）。 */
    suspend fun exportLog(content: String, fileName: String): ExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueTrace/logs")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext ExportResult.Error("无法创建日志文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportResult.Success("Download/BlueTrace/logs/$fileName")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            ExportResult.Error(e.message ?: "日志导出失败")
        }
    }
}
