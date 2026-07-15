package io.bluetrace.data.android

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用运行日志公共镜像：把私有 `files/log/app/app-*.log`（[io.bluetrace.shared.session.FileDiagnosticsLog]
 * 的滚动真源）逐个覆盖写到公共 `Download/BlueTrace/log/app/`。
 *
 * 每次 App 启动后台跑一次（≤保留天数个小文件，开销可忽略）：崩溃后重开 App 即可在文件管理器
 * 拿到含崩溃栈的日志，无需 adb / 无需手点导出。当前进程新增行不实时镜像（真源仍在私有目录，
 * 设置页"导出日志"可随时取最新）。
 */
class AppLogMirror(private val context: Context) {

    suspend fun mirror() = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "log/app")
        val files = dir.listFiles { f: File -> f.isFile && f.name.startsWith("app-") && f.name.endsWith(".log") }
            ?: return@withContext
        val store = PublicDownloadStore(context)
        for (f in files) {
            try {
                store.upsertBytes(PublicTree.LOG_APP, f.name, f.readBytes())
            } catch (e: Exception) {
                // 单文件失败不影响其余
            }
        }
    }
}
