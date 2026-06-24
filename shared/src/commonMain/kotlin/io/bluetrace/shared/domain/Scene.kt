package io.bluetrace.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 采集场景模型（v6 · `scenes.json` 驱动）。主场景 = 采集目的 / 算法目标（为 HR/Wear/SpO2… 算法采数据）；
 * 子场景 = 该算法下的采集场景。`token` 用于 config / manifest / 文件名（**恒英文**，language-zh-en-only）；
 * `zh/en` 仅 UI 显示（中文环境显示 zh）。app 从 `assets/scenes.json` 导入，可增删替换无需改代码。
 */
@Serializable
data class SubScene(val token: String, val zh: String, val en: String)

@Serializable
data class Scene(val token: String, val zh: String, val en: String, val subs: List<SubScene> = emptyList())

/** 本次采集所选场景（主·子 token，恒英文）。落 config / manifest / 文件名（5 段命名前两段）。 */
@Serializable
data class SceneSelection(
    @SerialName("main") val mainToken: String,
    @SerialName("sub") val subToken: String,
)

/**
 * 场景词表（结构对齐 `Docs/architecture/scenes.json`；app 从同源 `assets/scenes.json` 加载）。
 */
@Serializable
data class SceneCatalog(
    val version: Int = 1,
    val autoDefaultUserSubs: List<String> = emptyList(),
    val scenes: List<Scene> = emptyList(),
) {
    fun scene(mainToken: String): Scene? = scenes.firstOrNull { it.token == mainToken }
    fun sub(mainToken: String, subToken: String): SubScene? =
        scene(mainToken)?.subs?.firstOrNull { it.token == subToken }

    /** 子场景命中 autoDefaultUserSubs（Unwear/…）→ 采集对象自动切 Default 用户（无人/纯数据采集）。 */
    fun isAutoDefaultUser(subToken: String): Boolean = subToken in autoDefaultUserSubs

    /** 默认选择 = JSON 第一个主场景的第一个子场景（用户裁决）；空词表兜底 Wear/Wearing。 */
    val firstSelection: SceneSelection
        get() = scenes.firstOrNull()?.let { s -> s.subs.firstOrNull()?.let { SceneSelection(s.token, it.token) } }
            ?: SceneSelection("Wear", "Wearing")

    companion object {
        /** 解析失败兜底（仅默认 Wear/Wearing），保证 UI 不崩。 */
        val EMPTY = SceneCatalog(
            scenes = listOf(Scene("Wear", "佩戴", "Wear", listOf(SubScene("Wearing", "佩戴中", "Wearing")))),
        )
    }
}

private val sceneJson = Json { ignoreUnknownKeys = true }

/** 解析 scenes.json 文本为 [SceneCatalog]（失败兜底 [SceneCatalog.EMPTY]）。 */
fun parseSceneCatalog(json: String): SceneCatalog =
    runCatching { sceneJson.decodeFromString<SceneCatalog>(json) }.getOrDefault(SceneCatalog.EMPTY)
