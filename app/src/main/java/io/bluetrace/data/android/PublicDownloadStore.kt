package io.bluetrace.data.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * 公共导出目录树(`Download/BlueTrace/`, 经 MediaStore, 免存储权限). **目录名恒英文**(i18n 红线):
 *
 * ```
 * Download/BlueTrace/
 *   log/               ← 日志主文件夹
 *     ota/             ← OTA 执行日志(单/多设备每次运行一个 .log, 运行中逐行落盘)
 *     firmware/        ← 设备固件日志(从手表拉取的原始字节)
 *     app/             ← APP 运行日志(滚动日志镜像 + 设置页导出 + 控制台操作日志)
 *   config/            ← 工程配置 JSON 镜像(真源在 app 私有 files/config/)
 *   rawdata/
 *     <YYYY-MM-DD>/    ← 会话数据 zip 按导出日期归档(工程配置 export.rawdataByDate 可关)
 * ```
 */
object PublicTree {
    const val ROOT = "BlueTrace"
    const val LOG_OTA = "$ROOT/log/ota"
    const val LOG_FIRMWARE = "$ROOT/log/firmware"
    const val LOG_APP = "$ROOT/log/app"
    const val CONFIG = "$ROOT/config"
    const val RAWDATA = "$ROOT/rawdata"

    /** 遗留目录(v7 及以前的设备/应用日志混放处), 首次列举时迁移.  */
    const val LEGACY_LOGS = "$ROOT/logs"

    /** MediaStore RELATIVE_PATH 形式(带 Download 前缀与尾斜杠).  */
    fun relPath(subdir: String): String = "${Environment.DIRECTORY_DOWNLOADS}/$subdir/"

    /** 展示/日志用路径.  */
    fun display(subdir: String): String = "Download/$subdir"
}

/**
 * Download 公共目录写入助手(本 app 拥有的行免任何运行时权限).
 * 供 [ConfigStore] 镜像 / [AppLogMirror] / [OtaRunLogStore] 复用;
 * 会话导出与固件日志沿用各自专用件([MediaStoreExporter]/[DeviceLogStore]).
 */
class PublicDownloadStore(private val context: Context) {

    private val collection get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 查本 app 在 `Download/<subdir>/` 下的同名行(不存在/不可见返回 null).  */
    fun findOwn(subdir: String, name: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf("${PublicTree.display(subdir)}%", name)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID)))
            }
        }
        return null
    }

    /** 新建一行(用 octet-stream: text/plain 会被 MediaStore 强制补 .txt). 失败返回 null.  */
    fun insert(subdir: String, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, PublicTree.relPath(subdir))
        }
        return try {
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 覆盖写(upsert): 已有本 app 同名行则截断重写, 否则新建. 返回展示路径(失败 null).
     * 用于配置镜像与应用日志镜像这类"同名最新版"文件.
     */
    fun upsertBytes(subdir: String, name: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val uri = findOwn(subdir, name) ?: insert(subdir, name) ?: return null
        return try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return null
            "${PublicTree.display(subdir)}/$name"
        } catch (e: Exception) {
            null
        }
    }
}
