package io.bluetrace.ui.screen.collect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.domain.CollectDraft
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import org.koin.compose.koinInject

/**
 * 采集场景选择（v6 · 页 A）。两级单选：左=主场景（算法目标）/ 右=子场景（采集场景），来自 scenes.json。
 * 选子场景 → 写本次 [CollectDraft] 并回采集主界面（tile 更新）；非人子场景（autoDefaultUserSubs）→
 * 采集主界面自动切 Default 用户（逻辑在 CollectHomeViewModel）。token 恒英文，屏内只显示 zh。
 */
@Composable
fun SceneSelectScreen(onDone: () -> Unit) {
    val catalog = koinInject<SceneCatalog>()
    val draft = koinInject<CollectDraft>()
    val current by draft.scene.collectAsState()

    var viewedMain by rememberSaveable { mutableStateOf(current.mainToken) }
    val mainScene = remember(viewedMain, catalog) { catalog.scene(viewedMain) ?: catalog.scenes.firstOrNull() }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.scene_select_title),
            onBack = onDone,
            actions = {
                Text(
                    stringResource(R.string.scene_select_done),
                    fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.primary,
                    modifier = Modifier.clickable { onDone() },
                )
            },
        )
        Row(Modifier.fillMaxSize()) {
            // 左：主场景列（算法目标）
            Column(
                Modifier.width(104.dp).fillMaxHeight().background(BT.bg)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                catalog.scenes.forEach { s ->
                    MainSceneItem(
                        label = s.zh,
                        icon = mainSceneIcon(s.token),
                        selected = s.token == viewedMain,
                        onClick = { viewedMain = s.token },
                    )
                }
            }
            // 右：子场景单选（当前算法下的采集场景）
            Column(
                Modifier.weight(1f).fillMaxHeight().background(BT.surface)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                mainScene?.subs?.forEach { sub ->
                    val selected = viewedMain == current.mainToken && sub.token == current.subToken
                    SubSceneOption(
                        label = sub.zh,
                        selected = selected,
                        onClick = {
                            draft.setScene(SceneSelection(viewedMain, sub.token))
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainSceneItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (selected) BT.surface else Color.Transparent)
            .clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) BT.primaryC else BT.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) BT.primary else BT.onSurfaceV, modifier = Modifier.size(19.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.W800 else FontWeight.W600, color = if (selected) BT.onSurface else BT.onSurfaceV)
    }
}

@Composable
private fun SubSceneOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BT.primary else BT.surface2)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.W800 else FontWeight.W600, color = if (selected) Color.White else BT.onSurfaceV)
    }
}

private fun mainSceneIcon(token: String): ImageVector = when (token) {
    "HR" -> Icons.Filled.Favorite
    "SpO2" -> Icons.Filled.Air
    "Wear" -> Icons.Filled.Watch
    "Step" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "Sleep" -> Icons.Filled.Bedtime
    "Stress" -> Icons.Filled.Bolt
    "Swim" -> Icons.Filled.Pool
    else -> Icons.Filled.Sensors
}
