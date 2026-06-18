package io.bluetrace.shared.domain

import kotlinx.serialization.Serializable

/** 性别枚举。**显示名走资源**（app `sexLabel()`，§7.6）——commonMain 不放本地化文案。 */
@Serializable
enum class Sex {
    MALE,
    FEMALE,
    OTHER,
}

/**
 * 采集用户（F-SUBJ-1，UI 称"用户"、工程实体名 Subject，D-V4-8）。
 * 本地存储、可多条、可选当前；别名写入文件名前缀 + manifest（隐私=建议别名、不上传）。
 *
 * 注：体征摘要行（性别+生日+身高+体重）由 UI 层本地化拼接（app `subjectBioLine()`），
 * 不在 commonMain 拼字符串，避免 i18n 泄漏（§7.6）。
 */
@Serializable
data class Subject(
    val id: String,
    val alias: String,
    val sex: Sex = Sex.OTHER,
    val birth: String = "", // 形如 "1992-5"
    val heightCm: Int? = null,
    val weightKg: Double? = null,
    val note: String? = null,
)
