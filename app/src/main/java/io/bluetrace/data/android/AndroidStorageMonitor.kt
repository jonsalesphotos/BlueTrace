package io.bluetrace.data.android

import android.content.Context
import android.os.StatFs
import io.bluetrace.shared.data.StorageMonitor
import java.io.File

/** 真实可用空间（StatFs，§5.2 / §5.8）。落在会话存储所在卷上。 */
class AndroidStorageMonitor(private val context: Context) : StorageMonitor {
    private fun storageDir(): File = context.getExternalFilesDir(null) ?: context.filesDir

    override fun usableBytes(): Long {
        val dir = storageDir()
        return runCatching { StatFs(dir.path).availableBytes }.getOrElse { dir.usableSpace }
    }
}
