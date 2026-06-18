package io.bluetrace

import android.app.Application
import io.bluetrace.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BlueTraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@BlueTraceApplication)
            modules(appModule)
        }
    }
}
