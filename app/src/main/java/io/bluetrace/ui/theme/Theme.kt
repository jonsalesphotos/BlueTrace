package io.bluetrace.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.ThemeMode
import org.koin.compose.koinInject

private val LightColors = lightColorScheme(
    primary = BT.primary,
    onPrimary = Color.White,
    primaryContainer = LightScheme.primaryC,
    onPrimaryContainer = LightScheme.onPrimaryC,
    secondary = BT.primaryDeep,
    tertiary = BT.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = LightScheme.tertiaryC,
    onTertiaryContainer = LightScheme.onTertiaryC,
    error = BT.error,
    onError = Color.White,
    errorContainer = LightScheme.errorC,
    background = LightScheme.bg,
    onBackground = LightScheme.onSurface,
    surface = LightScheme.surface,
    onSurface = LightScheme.onSurface,
    surfaceVariant = LightScheme.surface2,
    onSurfaceVariant = LightScheme.onSurfaceV,
    outline = LightScheme.outline,
    outlineVariant = LightScheme.outlineV,
)

private val DarkColors = darkColorScheme(
    primary = BT.primary,
    onPrimary = Color.White,
    primaryContainer = DarkScheme.primaryC,
    onPrimaryContainer = DarkScheme.onPrimaryC,
    secondary = BT.primaryDeep,
    tertiary = BT.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = DarkScheme.tertiaryC,
    onTertiaryContainer = DarkScheme.onTertiaryC,
    error = BT.error,
    onError = Color.White,
    errorContainer = DarkScheme.errorC,
    background = DarkScheme.bg,
    onBackground = DarkScheme.onSurface,
    surface = DarkScheme.surface,
    onSurface = DarkScheme.onSurface,
    surfaceVariant = DarkScheme.surface2,
    onSurfaceVariant = DarkScheme.onSurfaceV,
    outline = DarkScheme.outline,
    outlineVariant = DarkScheme.outlineV,
)

private val BtTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.W700),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.W700),
        titleSmall = base.titleSmall.copy(fontSize = 13.5.sp, fontWeight = FontWeight.W700),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
        bodySmall = base.bodySmall.copy(fontSize = 11.5.sp),
        labelMedium = base.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.W600),
        labelSmall = base.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.W600),
    )
}

/** 等宽样式（实时流 / 日志 / MAC / 时间戳），tabular。 */
val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
val MonoBody = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.W600)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 全局外观（§8）：按 [AppPreferences.themeMode] 解析 亮/暗/跟随系统 → 切换 colorScheme + [LocalBtScheme]，
 * 并同步系统栏图标明暗。各屏 `BT.*` 与 `MaterialTheme.colorScheme` 自动跟随。
 */
@Composable
fun BlueTraceTheme(content: @Composable () -> Unit) {
    val prefs = koinInject<AppPreferences>()
    val mode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context.findActivity()
        if (activity != null) {
            SideEffect {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalBtScheme provides if (dark) DarkScheme else LightScheme) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BtTypography,
            content = content,
        )
    }
}
