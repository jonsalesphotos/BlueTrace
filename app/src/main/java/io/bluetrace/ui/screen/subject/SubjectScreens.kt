package io.bluetrace.ui.screen.subject

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.CircleCheck
import io.bluetrace.ui.components.EmptyState
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.SubjectViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun sexLabel(sex: Sex): String = stringResource(
    when (sex) {
        Sex.MALE -> R.string.sex_male
        Sex.FEMALE -> R.string.sex_female
        Sex.OTHER -> R.string.sex_other
    },
)

/** 用户A/B · 用户选择（点行即时选中并返回，§5.1）。 */
@Composable
fun SubjectSelectScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    vm: SubjectViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.subject_select_title), subtitle = stringResource(R.string.subject_select_subtitle), onBack = onBack)

        if (ui.subjects.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.PersonOff,
                title = stringResource(R.string.subject_empty_title),
                subtitle = stringResource(R.string.subject_empty_sub),
                actionText = stringResource(R.string.subject_new),
                onAction = onNew,
                modifier = Modifier.padding(top = 40.dp),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                item { SectionHeader(stringResource(R.string.subject_sec_profiles)) }
                items(ui.subjects, key = { it.id }) { subject ->
                    val current = subject.id == ui.currentId
                    Surface(
                        color = BT.surface,
                        shape = RoundedCornerShape(BT.radius),
                        border = if (current) androidx.compose.foundation.BorderStroke(1.5.dp, BT.tertiary) else null,
                        modifier = Modifier.fillMaxWidth().clickable { vm.select(subject.id); onBack() },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(subject.alias, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                                    if (current) PillTag(stringResource(R.string.subject_tag_current), BT.onTertiaryC, BT.tertiaryC)
                                }
                                Text(subjectBioLine(subject), fontSize = 11.sp, color = BT.onSurfaceV)
                            }
                            CircleCheck(checked = current, color = BT.tertiary)
                        }
                    }
                }
            }
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                OutlineBtn(stringResource(R.string.subject_new), onNew, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 体征摘要行（性别本地化 + 其余字段）。i18n 拼接放 UI 层（不在 commonMain，§7.6）。 */
@Composable
fun subjectBioLine(subject: Subject): String {
    val parts = buildList {
        add(sexLabel(subject.sex))
        if (subject.birth.isNotBlank()) add(subject.birth)
        subject.heightCm?.let { add("${it}cm") }
        subject.weightKg?.let { add("${it}kg") }
    }
    return parts.joinToString(" · ")
}

/** 用户C · 用户编辑表单（别名/性别/出生年月/身高/体重，本地不上传）。 */
@Composable
fun SubjectEditScreen(
    subjectId: String?,
    onBack: () -> Unit,
    vm: SubjectViewModel = koinViewModel(),
) {
    var loadedId by rememberSaveable { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
    var birth by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(subjectId) {
        if (subjectId != null) {
            vm.load(subjectId)?.let { s ->
                loadedId = s.id; alias = s.alias; sex = s.sex; birth = s.birth
                height = s.heightCm?.toString() ?: ""; weight = s.weightKg?.toString() ?: ""
            }
        }
    }
    val effectiveId = remember(loadedId) { loadedId ?: vm.newId() }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = if (subjectId == null) stringResource(R.string.subject_edit_new_title) else stringResource(R.string.subject_edit_title),
            subtitle = stringResource(R.string.subject_edit_subtitle),
            onBack = onBack,
            actions = {
                Text(
                    stringResource(R.string.action_save), fontSize = 14.sp, fontWeight = FontWeight.W700, color = if (alias.isNotBlank()) BT.primary else BT.outline,
                    modifier = Modifier.clickable(enabled = alias.isNotBlank()) { saveAndBack(vm, effectiveId, alias, sex, birth, height, weight, onBack) },
                )
            },
        )
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(stringResource(R.string.subject_field_alias)) {
                OutlinedTextField(value = alias, onValueChange = { alias = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.subject_field_alias_hint), fontSize = 13.sp) })
            }
            Field(stringResource(R.string.subject_field_sex)) {
                Row(Modifier.background(BT.surface2, RoundedCornerShape(999.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Sex.entries.forEach { s ->
                        val sel = s == sex
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) BT.surface else androidx.compose.ui.graphics.Color.Transparent).clickable { sex = s }.padding(horizontal = 16.dp, vertical = 7.dp),
                        ) { Text(sexLabel(s), fontSize = 12.sp, fontWeight = if (sel) FontWeight.W700 else FontWeight.W500, color = if (sel) BT.onSurface else BT.onSurfaceV) }
                    }
                }
            }
            Field(stringResource(R.string.subject_field_birth)) {
                OutlinedTextField(value = birth, onValueChange = { birth = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.subject_field_birth_hint), fontSize = 13.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Field(stringResource(R.string.subject_field_height)) { OutlinedTextField(value = height, onValueChange = { height = it.filter(Char::isDigit) }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
                Box(Modifier.weight(1f)) { Field(stringResource(R.string.subject_field_weight)) { OutlinedTextField(value = weight, onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
            }
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius)) {
                Text(stringResource(R.string.subject_privacy_note), fontSize = 11.5.sp, color = BT.onPrimaryC, modifier = Modifier.padding(12.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton(stringResource(R.string.subject_save), onClick = { saveAndBack(vm, effectiveId, alias, sex, birth, height, weight, onBack) }, enabled = alias.isNotBlank())
        }
    }
}

private fun saveAndBack(
    vm: SubjectViewModel, id: String, alias: String, sex: Sex, birth: String, height: String, weight: String, onBack: () -> Unit,
) {
    vm.save(
        Subject(
            id = id,
            alias = alias.trim(),
            sex = sex,
            birth = birth.trim(),
            heightCm = height.toIntOrNull(),
            weightKg = weight.toDoubleOrNull(),
        ),
    )
    onBack()
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = BT.onSurfaceV)
        content()
    }
}
