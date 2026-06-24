package io.bluetrace

import android.app.Application
import io.bluetrace.di.appModule
import io.bluetrace.shared.session.DiagnosticsLog
import io.bluetrace.shared.session.FileDiagnosticsLog
import io.bluetrace.shared.session.LogLevel
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
    }
}
