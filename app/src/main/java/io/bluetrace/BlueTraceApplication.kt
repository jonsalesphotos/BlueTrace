package io.bluetrace

import android.app.Application
import io.bluetrace.data.android.AppLogMirror
import io.bluetrace.data.android.ConfigStore
import io.bluetrace.di.appModule
import io.bluetrace.shared.session.DiagnosticsLog
import io.bluetrace.shared.session.FileDiagnosticsLog
import io.bluetrace.shared.session.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BlueTraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@BlueTraceApplication)
            modules(appModule)
        }.koin

        // 崩溃日志：未捕获异常同步落盘到 .log（先停异步写、独占 sink），再交还系统默认 handler。
        val diagnostics = koin.get<DiagnosticsLog>()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            (diagnostics as? FileDiagnosticsLog)?.let {
                it.cancelWriter()
                it.appendBlocking(LogLevel.ERROR, "crash", e.stackTraceToString())
            }
            prev?.uncaughtException(t, e)
        }

        // v8 公共目录树启动镜像 + 遗留目录合并（后台尽力而为）：config JSON + 应用滚动日志
        // → Download/BlueTrace/（崩溃后重开 App 即可在文件管理器拿到含崩溃栈的日志，免 adb）；
        // 旧 logs/ 整目录合并进 log/（不必等用户打开日志列表页才迁移）。
        koin.get<CoroutineScope>().launch {
            runCatching { koin.get<ConfigStore>().mirrorToPublic() }
            runCatching { AppLogMirror(this@BlueTraceApplication).mirror() }
            runCatching { koin.get<io.bluetrace.data.android.DeviceLogStore>().migrateLegacyDirs() }
        }
    }
}
