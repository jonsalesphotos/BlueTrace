package io.bluetrace.shared.domain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 采集前置流草稿（v6）：本次采集所选 [SceneSelection]，**持久化到 [AppPreferences]**。
 * - 首次启动（未选过）→ scenes.json 第一个主场景的第一个子场景（[SceneCatalog.firstSelection]）。
 * - 之后启动 → 保留上次选择。
 * 采集主界面「采集场景」tile 与 场景选择页 共享此真源（场景选择页写、采集主界面读）。
 */
class CollectDraft(
    private val prefs: AppPreferences,
    catalog: SceneCatalog,
    private val scope: CoroutineScope,
) {
    private val fallback = catalog.firstSelection
    val scene: StateFlow<SceneSelection> =
        prefs.sceneSelection
            .map { it ?: fallback }
            .stateIn(scope, SharingStarted.Eagerly, fallback)

    fun setScene(s: SceneSelection) { scope.launch { prefs.setSceneSelection(s) } }
}
