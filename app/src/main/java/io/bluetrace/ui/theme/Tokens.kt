package io.bluetrace.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计 tokens（v4_android.html `<style>` 单一真源，§8）。色 + 文字双编码，色彩不作唯一信息载体。
 * 一期浅色；视觉细节以原型为准。
 */
object BT {
    // 主色 / 容器
    val primary = Color(0xFF2BAEEA)        // DUT / CTA
    val primaryDeep = Color(0xFF1C8EC5)
    val primaryC = Color(0xFFDCF1FB)
    val onPrimaryC = Color(0xFF0E5E89)

    val tertiary = Color(0xFF7C5BC9)       // 参考心率带 / 用户
    val tertiaryC = Color(0xFFECE3FF)
    val onTertiaryC = Color(0xFF3A2A66)

    val error = Color(0xFFE5484D)
    val errorC = Color(0xFFFFE3E3)
    val warning = Color(0xFFE89F40)
    val warningC = Color(0xFFFFE8C9)
    val onWarningC = Color(0xFF7A4A0A)
    val success = Color(0xFF2AAE6D)        // 在线 / 已连接
    val successC = Color(0xFFD4F4E2)
    val onSuccessC = Color(0xFF0B5733)

    // 传感器固定配色（§8.2，色+文字双编码）
    val sPpg = Color(0xFF2AAE6D)   // 绿 高频
    val sEcg = Color(0xFFE5484D)   // 红 高频
    val sImu = Color(0xFF2BAEEA)   // 蓝 高频（ACC/IMU）
    val sMag = Color(0xFF7C5BC9)   // 紫 低频（地磁）
    val sTemp = Color(0xFFE89F40)  // 橙 低频（温度）

    // 表面 / 文本 / 描边
    val bg = Color(0xFFF3F5F8)
    val surface = Color(0xFFFFFFFF)
    val surface1 = Color(0xFFF6F7FB)
    val surface2 = Color(0xFFECEEF3)
    val surface3 = Color(0xFFE2E5EC)
    val onSurface = Color(0xFF1B1D22)
    val onSurfaceV = Color(0xFF4C525C)
    val outline = Color(0xFF8B919C)
    val outlineV = Color(0xFFD7DAE0)

    val radiusSm = 8.dp
    val radius = 14.dp
    val radiusLg = 20.dp
}

/** 传感器/采集类型 id → 固定配色（色+文字双编码）。 */
fun sensorColor(id: String): Color = when (id) {
    "ppg_g", "ppg_ir", "ppg" -> BT.sPpg
    "ecg" -> BT.sEcg
    "acc", "imu", "gyro" -> BT.sImu
    "mag" -> BT.sMag
    "temp" -> BT.sTemp
    "hr" -> BT.tertiary
    else -> BT.onSurfaceV
}
