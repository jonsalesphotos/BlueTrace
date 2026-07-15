package io.bluetrace.shared.domain

import kotlinx.serialization.Serializable

/**
 * 采集类型（运行C 采集类型选择，D-V4-12）：纯开关 = 该路是否落盘/上传（透明传输、不控采样率）。
 * 注：传感器总控 / 设备端算法（配置A/B）暂不实现，选择能力并入此处。
 */
@Serializable
enum class CollectType(
    val id: String,
    val label: String,
    val defaultOn: Boolean,
) {
    // label 恒英文 token（直接上屏，i18n 红线；2026-07-14 审查修复 MAG/TEMP 中文泄漏）
    PPG_G("ppg_g", "PPG_G", true),
    PPG_IR("ppg_ir", "PPG_IR", true),
    ACC("acc", "ACC", true),
    GYRO("gyro", "GYRO", false),
    MAG("mag", "MAG", false),
    TEMP("temp", "TEMP", false);

    companion object {
        val defaults: Set<CollectType> get() = entries.filter { it.defaultOn }.toSet()
    }
}

/**
 * 解码后的数据流标识 —— 每路落一个 CSV（§6.1 "每模块解码 CSV"）。
 * HR 来自参考心率带（不属 CollectType）。
 */
enum class DecodedStream(val csvName: String, val channels: List<String>) {
    PPG_G("ppg_g", listOf("ppg")),
    PPG_IR("ppg_ir", listOf("ppg")),
    ACC("acc", listOf("x", "y", "z")),
    GYRO("gyro", listOf("x", "y", "z")),
    MAG("mag", listOf("x", "y", "z")),
    TEMP("temp", listOf("temp")),
    HR("hr", listOf("bpm"));

    companion object {
        fun ofCollectType(t: CollectType): DecodedStream = when (t) {
            CollectType.PPG_G -> PPG_G
            CollectType.PPG_IR -> PPG_IR
            CollectType.ACC -> ACC
            CollectType.GYRO -> GYRO
            CollectType.MAG -> MAG
            CollectType.TEMP -> TEMP
        }
    }
}
