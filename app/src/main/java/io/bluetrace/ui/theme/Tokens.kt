package io.bluetrace.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计 tokens（v4_android.html `<style>` 单一真源，§8）。色 + 文字双编码，色彩不作唯一信息载体。
 *
 * **主题感知**：表面 / 文本 / 容器色随亮/暗模式切换（见 [BtScheme] + [LightScheme]/[DarkScheme]）；
 * 品牌主色与传感器固定配色不随模式（[BT] 顶部的固定 val）。
 * [BT] 的主题感知属性用 `@Composable get()` 读 [LocalBtScheme]（同 Material3 colorScheme 写法），
 * 因此各屏 `BT.bg`/`BT.surface` 等**调用点不变**即自动跟随模式。
 */
@Immutable
data class BtScheme(
    val bg: Color,
    val surface: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val onSurface: Color,
    val onSurfaceV: Color,
    val outline: Color,
    val outlineV: Color,
    val primaryC: Color,
    val onPrimaryC: Color,
    val tertiaryC: Color,
    val onTertiaryC: Color,
    val errorC: Color,
    val warningC: Color,
    val onWarningC: Color,
    val successC: Color,
    val onSuccessC: Color,
)

/** 浅色（一期默认，原型口径）。 */
val LightScheme = BtScheme(
    bg = Color(0xFFF3F5F8), surface = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF6F7FB), surface2 = Color(0xFFECEEF3), surface3 = Color(0xFFE2E5EC),
    onSurface = Color(0xFF1B1D22), onSurfaceV = Color(0xFF4C525C),
    outline = Color(0xFF8B919C), outlineV = Color(0xFFD7DAE0),
    primaryC = Color(0xFFDCF1FB), onPrimaryC = Color(0xFF0E5E89),
    tertiaryC = Color(0xFFECE3FF), onTertiaryC = Color(0xFF3A2A66),
    errorC = Color(0xFFFFE3E3),
    warningC = Color(0xFFFFE8C9), onWarningC = Color(0xFF7A4A0A),
    successC = Color(0xFFD4F4E2), onSuccessC = Color(0xFF0B5733),
)

/** 深色（蓝灰调，与启动屏深底 #11151C 一致）。容器为深色底 + 浅色前景文字。 */
val DarkScheme = BtScheme(
    bg = Color(0xFF11151C), surface = Color(0xFF1A1F28),
    surface1 = Color(0xFF1F242E), surface2 = Color(0xFF262C37), surface3 = Color(0xFF2F3742),
    onSurface = Color(0xFFE7EAEF), onSurfaceV = Color(0xFF9AA4B2),
    outline = Color(0xFF5C6470), outlineV = Color(0xFF333A45),
    primaryC = Color(0xFF123243), onPrimaryC = Color(0xFF9DD9F5),
    tertiaryC = Color(0xFF28203F), onTertiaryC = Color(0xFFCBB8F2),
    errorC = Color(0xFF3E1A1C),
    warningC = Color(0xFF3D2E12), onWarningC = Color(0xFFF0C078),
    successC = Color(0xFF123528), onSuccessC = Color(0xFF8FE0B4),
)

/** 当前色板（[BlueTraceTheme] 按外观模式 provide）。 */
val LocalBtScheme = staticCompositionLocalOf { LightScheme }

object BT {
    // —— 固定品牌 / 语义主色（不随模式；中间调，深浅底皆可读）——
    val primary = Color(0xFF2BAEEA)        // DUT / CTA
    val primaryDeep = Color(0xFF1C8EC5)
    val tertiary = Color(0xFF7C5BC9)       // 参考心率带 / 用户
    val error = Color(0xFFE5484D)
    val warning = Color(0xFFE89F40)
    val success = Color(0xFF2AAE6D)        // 在线 / 已连接

    // 传感器固定配色（§8.2，色 + 文字双编码）
    val sPpg = Color(0xFF2AAE6D)   // 绿 高频
    val sEcg = Color(0xFFE5484D)   // 红 高频
    val sImu = Color(0xFF2BAEEA)   // 蓝 高频（ACC/IMU）
    val sMag = Color(0xFF7C5BC9)   // 紫 低频（地磁）
    val sTemp = Color(0xFFE89F40)  // 橙 低频（温度）

    // —— 主题感知（表面 / 文本 / 容器，随亮/暗切换）——
    val bg: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.bg
    val surface: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.surface
    val surface1: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.surface1
    val surface2: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.surface2
    val surface3: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.surface3
    val onSurface: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onSurface
    val onSurfaceV: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onSurfaceV
    val outline: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.outline
    val outlineV: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.outlineV
    val primaryC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.primaryC
    val onPrimaryC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onPrimaryC
    val tertiaryC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.tertiaryC
    val onTertiaryC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onTertiaryC
    val errorC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.errorC
    val warningC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.warningC
    val onWarningC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onWarningC
    val successC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.successC
    val onSuccessC: Color @Composable @ReadOnlyComposable get() = LocalBtScheme.current.onSuccessC

    // 半径
    val radiusSm = 8.dp
    val radius = 14.dp
    val radiusLg = 20.dp
}

/** 传感器/采集类型 id → 固定配色（色 + 文字双编码）。@Composable：else 分支取主题感知的 onSurfaceV。 */
@Composable
@ReadOnlyComposable
fun sensorColor(id: String): Color = when (id) {
    "ppg_g", "ppg_ir", "ppg" -> BT.sPpg
    "ecg" -> BT.sEcg
    "acc", "imu", "gyro" -> BT.sImu
    "mag" -> BT.sMag
    "temp" -> BT.sTemp
    "hr" -> BT.tertiary
    else -> BT.onSurfaceV
}
