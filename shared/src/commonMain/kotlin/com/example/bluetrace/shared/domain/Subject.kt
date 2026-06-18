package com.example.bluetrace.shared.domain

import kotlinx.serialization.Serializable

/** 性别（UI 段控 男|女|其他）。 */
@Serializable
enum class Sex(val label: String) {
    MALE("男"),
    FEMALE("女"),
    OTHER("其他"),
}

/**
 * 采集用户（F-SUBJ-1，UI 称"用户"、工程实体名 Subject，D-V4-8）。
 * 本地存储、可多条、可选当前；别名写入文件名前缀 + manifest（隐私=建议别名、不上传）。
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
) {
    /** "Male · 1992-5 · 175cm · 75.0kg" 这类体征摘要行。 */
    fun bioLine(): String = buildList {
        add(sex.label)
        if (birth.isNotBlank()) add(birth)
        heightCm?.let { add("${it}cm") }
        weightKg?.let { add("${it}kg") }
    }.joinToString(" · ")
}
