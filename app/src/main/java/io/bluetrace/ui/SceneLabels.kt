package io.bluetrace.ui

import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.shared.domain.SceneSelection

/**
 * 场景中文显示 "主.zh · 子.zh"（如 "佩戴 · 佩戴中"）。token 恒英文（用于文件名/manifest），不在屏内露出。
 * 词表缺该 token 时回退显示 token 本身（不崩）。
 */
fun sceneLabelZh(catalog: SceneCatalog, sel: SceneSelection): String {
    val mainZh = catalog.scene(sel.mainToken)?.zh ?: sel.mainToken
    val subZh = catalog.sub(sel.mainToken, sel.subToken)?.zh ?: sel.subToken
    return "$mainZh · $subZh"
}
