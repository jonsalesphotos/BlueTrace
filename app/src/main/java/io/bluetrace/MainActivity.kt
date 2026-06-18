package io.bluetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.bluetrace.ui.BlueTraceApp
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12 SplashScreen：系统画品牌脉冲 logo；保持到启动决策就绪（§5.1）。
        val splashScreen = installSplashScreen()
        val ready = AtomicBoolean(false)
        splashScreen.setKeepOnScreenCondition { !ready.get() }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BlueTraceApp(onReady = { ready.set(true) })
        }
    }
}
