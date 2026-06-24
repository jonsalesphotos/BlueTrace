package io.bluetrace.ui.screen.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.DEFAULT_SUBJECT
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.isDefault
import io.bluetrace.ui.components.CircleCheck
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.screen.subject.subjectBioLine
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.SubjectViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 事后改采集人 / 场景（结束摘要 结束A 与 会话详情 数据C 共用，§D/E）。
 * 重选 用户(含 Default) + 场景(主·子) → onConfirm 调 SessionStore.editSession：
 * 回写 manifest（alias+体征+主/子场景英文 token）并按新 5 段名重命名会话文件夹；冲突由调用方提示。
 * 本地态完全隔离，**不写 CollectDraft**（那是采集前置态，与事后归类无关）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSubjectSceneSheet(
    initialAlias: String,
    initialScene: SceneSelection,
    onDismiss: () -> Unit,
    onConfirm: (subject: Subject, scene: SceneSelection) -> Unit,
    subjectVm: SubjectViewModel = koinViewModel(),
) {
    val catalog = koinInject<SceneCatalog>()
    val ui by subjectVm.uiState.collectAsStateWithLifecycle()
    val people = remember(ui.subjects) { listOf(DEFAULT_SUBJECT) + ui.subjects }
    var selectedSubject by remember(ui.subjects) { mutableStateOf(people.firstOrNull { it.alias == initialAlias }) }
    var sceneMain by remember { mutableStateOf(initialScene.mainToken) }
    var sceneSub by remember { mutableStateOf(initialScene.subToken) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.edit_subject_scene_title), fontSize = 17.sp, fontWeight = FontWeight.W800, color = BT.onSurface)

            SectionHeader(stringResource(R.string.subject_select_title))
            people.forEach { s ->
                EditUserRow(s, selected = selectedSubject?.id == s.id) { selectedSubject = s }
            }

            SectionHeader(stringResource(R.string.scene_select_title))
            // 主场景：横向 chips（避免与外层纵向滚动冲突）
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                catalog.scenes.forEach { sc ->
                    MainChip(sc.zh, selected = sc.token == sceneMain) {
                        sceneMain = sc.token
                        sceneSub = catalog.scene(sc.token)?.subs?.firstOrNull()?.token ?: sceneSub
                    }
                }
            }
            // 子场景：纵向单选 pills
            catalog.scene(sceneMain)?.subs?.forEach { sub ->
                SubPill(sub.zh, selected = sub.token == sceneSub) { sceneSub = sub.token }
            }

            PrimaryButton(
                stringResource(R.string.action_save),
                onClick = {
                    val subj = selectedSubject ?: return@PrimaryButton
                    onConfirm(subj, SceneSelection(sceneMain, sceneSub))
                    onDismiss()
                },
                modifier = Modifier.padding(top = 4.dp),
                enabled = selectedSubject != null,
            )
        }
    }
}

@Composable
private fun EditUserRow(subject: Subject, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) BT.tertiaryC else BT.surface,
        shape = RoundedCornerShape(BT.radius),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, BT.tertiary) else null,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(BT.tertiaryC), contentAlignment = Alignment.Center) {
                Icon(if (subject.isDefault()) Icons.Filled.Monitor else Icons.Filled.Person, contentDescription = null, tint = BT.tertiary, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (subject.isDefault()) stringResource(R.string.subject_default) else subject.alias, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                }
                if (!subject.isDefault()) Text(subjectBioLine(subject), fontSize = 11.sp, color = BT.onSurfaceV)
            }
            CircleCheck(checked = selected, color = BT.tertiary)
        }
    }
}

@Composable
private fun MainChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) BT.primaryC else BT.surface2).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.W800 else FontWeight.W600, color = if (selected) BT.primary else BT.onSurfaceV)
    }
}

@Composable
private fun SubPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(BT.radius)).background(if (selected) BT.primary else BT.surface2).clickable { onClick() }.padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.W800 else FontWeight.W600, color = if (selected) Color.White else BT.onSurfaceV)
    }
}
