package io.bluetrace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = BT.primary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BT.primaryC,
    onPrimaryContainer = BT.onPrimaryC,
    secondary = BT.primaryDeep,
    tertiary = BT.tertiary,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = BT.tertiaryC,
    onTertiaryContainer = BT.onTertiaryC,
    error = BT.error,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = BT.errorC,
    background = BT.bg,
    onBackground = BT.onSurface,
    surface = BT.surface,
    onSurface = BT.onSurface,
    surfaceVariant = BT.surface2,
    onSurfaceVariant = BT.onSurfaceV,
    outline = BT.outline,
    outlineVariant = BT.outlineV,
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

@Composable
fun BlueTraceTheme(content: @Composable () -> Unit) {
    // 一期仅浅色（暗色二期，§1）；忽略系统暗色，固定浅色方案。
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LightColors,
        typography = BtTypography,
        content = content,
    )
}
