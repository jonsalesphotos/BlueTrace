package io.bluetrace.data.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.bluetrace.shared.s7.OtaEntryInfo
import io.bluetrace.shared.s7.OtaFile
import io.bluetrace.shared.s7.OtaPackage
import io.bluetrace.shared.s7.OtaPackageValidation
import io.bluetrace.shared.s7.OtaPackageValidator
import io.bluetrace.shared.s7.S7FileTrans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** 选包结果：解出的包（读/校验失败为 null）+ 校验 + 展示摘要 + 错误消息。 */
data class OtaZipLoadResult(
    val pkg: OtaPackage?,
    val validation: OtaPackageValidation?,
    val sourceName: String,
    val entries: List<OtaEntryInfo> = emptyList(),
    val error: String? = null,
)

/**
 * 从 SAF Uri 读 zip 烧录包 → List<OtaFile> → 简单校验 → [OtaPackage]（Android，`java.util.zip`）。
 * 校验规则见 [OtaPackageValidator]。zip 内文件为 deflate 压缩，[ZipInputStream] 自动解压。
 * 文件顺序按 golden 推送序（[OtaPackageValidator.sortByPushOrder]）。全部 fileType=FT_FW(2) 对齐真机官方 App。
 */
class OtaZipLoader(private val context: Context) {

    suspend fun load(uri: Uri): OtaZipLoadResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        try {
            val files = ArrayList<OtaFile>()
            val ins = context.contentResolver.openInputStream(uri)
                ?: return@withContext OtaZipLoadResult(null, null, name, error = "无法打开文件（openInputStream 返回空）")
            ins.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val base = entry.name.substringAfterLast('/') // 只取文件名（zip 条目可能带目录前缀）
                            val bytes = zip.readBytes()
                            if (base.isNotEmpty()) files.add(OtaFile(base, bytes, S7FileTrans.FT_FW))
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (files.isEmpty()) {
                return@withContext OtaZipLoadResult(null, OtaPackageValidator.validate(emptyList()), name, error = "zip 内无文件")
            }
            val ordered = OtaPackageValidator.sortByPushOrder(files) { it.name }
            val entries = ordered.map { OtaEntryInfo(it.name, it.bytes.size.toLong()) }
            OtaZipLoadResult(
                pkg = OtaPackage(files = ordered),
                validation = OtaPackageValidator.validate(entries),
                sourceName = name,
                entries = entries,
            )
        } catch (e: Exception) {
            OtaZipLoadResult(null, null, name, error = "解析 zip 失败：${e.message}")
        }
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "package.zip"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "package.zip"
    }
}
