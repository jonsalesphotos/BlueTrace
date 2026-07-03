package io.bluetrace.domain

import android.content.Context
import java.io.File

/**
 * 设备日志文件夹（app 级单例）：拉取的设备固件日志逐条存文件，可列举、按名读取。
 * 目录 = `<外部文件目录>/devlogs/`（adb pull 可取：/sdcard/Android/data/io.bluetrace/files/devlogs/）。
 * 文件名以 **MAC 区分**：`s7_devlog_<MAC无冒号>_<时间戳>.log`。
 */
class DeviceLogStore(context: Context) {

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "devlogs")

    data class Entry(val name: String, val sizeBytes: Long, val modifiedMs: Long)

    fun dirPath(): String = dir.absolutePath

    /** 存一条日志，返回绝对路径。mac 用于区分设备。 */
    fun save(bytes: ByteArray, mac: String, ts: String): String {
        dir.mkdirs()
        val macCompact = mac.filter { it.isLetterOrDigit() }.uppercase()
        val f = File(dir, "s7_devlog_${macCompact}_$ts.log")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** 列举全部日志（新→旧）。 */
    fun list(): List<Entry> =
        (dir.listFiles()?.filter { it.isFile } ?: emptyList())
            .sortedByDescending { it.lastModified() }
            .map { Entry(it.name, it.length(), it.lastModified()) }

    /** 按文件名读取内容（防目录穿越）。 */
    fun read(name: String): String? {
        val f = File(dir, name)
        if (!f.exists() || f.parentFile != dir) return null
        return f.readText()
    }
}
