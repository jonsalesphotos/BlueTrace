package io.bluetrace.data.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
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
    /** 导出D: 存储不足(导出前预检阻断, §5.8).  */
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ExportResult
}

/**
 * 整会话文件夹经 MediaStore 导出为 zip 到公共 `Download/BlueTrace/rawdata/<YYYY-MM-DD>/`
 * (§6.4; v8 目录树——按**导出日期**归档, 工程配置 `export.rawdataByDate=false` 可退回平铺),
 * 无需任何存储运行时权限. 流程: 导出前存储预检 → 插入 IS_PENDING 记录 → openOutputStream 写 zip → 清 IS_PENDING.
 */
class MediaStoreExporter(
    private val context: Context,
    private val storageMonitor: StorageMonitor,
    private val configStore: ConfigStore,
) {

    private fun sessionsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "sessions")
    }

    /** 本次导出的 rawdata 目标子目录(`BlueTrace/rawdata[/<YYYY-MM-DD>]`, 日期=导出时本地日).  */
    private fun rawdataSubdir(): String =
        if (configStore.current.export.rawdataByDate) {
            "${PublicTree.RAWDATA}/${java.time.LocalDate.now()}" // ISO yyyy-MM-dd, locale 无关
        } else {
            PublicTree.RAWDATA
        }

    /**
     * @param selectedRelativePaths null=整夹; 非空=仅打包这些相对路径(数据C 逐项勾选导出),
     *        zip 文件名加 `_partial` 后缀区分(token 恒英文, 红线).
     */
    suspend fun exportSession(
        folderName: String,
        selectedRelativePaths: Set<String>? = null,
        onProgress: (Float) -> Unit,
    ): ExportResult =
        withContext(Dispatchers.IO) {
            val srcDir = File(sessionsDir(), folderName)
            if (!srcDir.exists() || !srcDir.isDirectory) {
                return@withContext ExportResult.Error(context.getString(R.string.export_err_no_folder))
            }
            val files = srcDir.walkTopDown().filter { it.isFile }.toList().let { all ->
                if (selectedRelativePaths == null) all
                else all.filter { it.relativeTo(srcDir).path.replace(File.separatorChar, '/') in selectedRelativePaths }
            }
            if (files.isEmpty()) return@withContext ExportResult.Error(context.getString(R.string.export_err_empty))

            // 导出前真实存储预检(§5.8): 需 ≥ 待导出大小 × 余量系数
            val required = (files.sumOf { it.length() } * StoragePolicy.EXPORT_HEADROOM_FACTOR).toLong()
            val available = storageMonitor.usableBytes()
            if (available < required) {
                return@withContext ExportResult.InsufficientSpace(required, available)
            }

            val displayName = if (selectedRelativePaths == null) "$folderName.zip" else "${folderName}_partial.zip"
            val subdir = rawdataSubdir()
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, PublicTree.relPath(subdir))
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
                ExportResult.Success("${PublicTree.display(subdir)}/$displayName")
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 用户取消: 删掉 IS_PENDING 悬挂记录(否则 Download 里留永不可见的幽灵文件), 取消须继续传播
                runCatching { resolver.delete(uri, null, null) }
                throw ce
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                ExportResult.Error(e.message ?: context.getString(R.string.export_err_generic))
            }
        }

    /** 应用日志导出到 `Download/BlueTrace/log/app/`(设置E).  */
    suspend fun exportLog(content: String, fileName: String): ExportResult =
        exportLogBytes(content.toByteArray(), fileName)

    /** 字节精确导出到公共 `Download/BlueTrace/log/app/`(App 侧运行/操作日志, 须字节保真).  */
    suspend fun exportLogBytes(content: ByteArray, fileName: String): ExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            // octet-stream: text/plain 会被 MediaStore 强制补 .txt(.log→.log.txt, 同 DeviceLogStore 的坑)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, PublicTree.relPath(PublicTree.LOG_APP))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext ExportResult.Error(context.getString(R.string.export_err_log_create))
        try {
            // 输出流打开失败(返回 null)不能算成功——否则用户拿到"已导出"但文件是空的
            resolver.openOutputStream(uri)?.use { it.write(content) } ?: run {
                resolver.delete(uri, null, null)
                return@withContext ExportResult.Error(context.getString(R.string.export_err_write))
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportResult.Success("${PublicTree.display(PublicTree.LOG_APP)}/$fileName")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw ce
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            ExportResult.Error(e.message ?: context.getString(R.string.export_err_generic))
        }
    }
}
