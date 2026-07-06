package io.bluetrace.ui.screen.subject

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import io.bluetrace.shared.domain.DEFAULT_SUBJECT
import io.bluetrace.shared.domain.DEFAULT_SUBJECT_ID
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
import kotlin.math.roundToInt

/**
 * 性别显示名：按用户裁决(v3)照原型用英文枚举 Male/Female/Other，**不随语言本地化**。
 * 这是对 SPEC §7.6(枚举走资源本地化)的有意偏离——用户口径优先于 SPEC/原型静态稿。
 */
@Composable
fun sexLabel(sex: Sex): String = when (sex) {
    Sex.MALE -> "Male"
    Sex.FEMALE -> "Female"
    Sex.OTHER -> "Other"
}

/**
 * 用户A/B · 用户选择（v6 重设计）：Default 行 + 单一「用户」单选 + 底部「确认」。
 * 先选后确认；返回与确认等效（选了则应用并回，未选则保持原状=无人）。
 */
@Composable
fun SubjectSelectScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEditSubject: (String) -> Unit,
    vm: SubjectViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // currentId 是异步 Flow，首帧多为 null → 用 LaunchedEffect 同步初值（含 __default__ / null）
    LaunchedEffect(ui.currentId) { selectedId = ui.currentId }

    // 返回 = 确认：选了则应用并回；未选则仅返回（不改当前）
    val applyAndBack = { selectedId?.let { vm.select(it) }; onBack() }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.subject_select_title), onBack = applyAndBack)

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
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                // Default 行：非人采集对象（对空/对物/临时），佩戴未佩戴类场景自动用它。再点已选=取消（变无人）
                item { DefaultRow(selected = selectedId == DEFAULT_SUBJECT_ID) { selectedId = if (selectedId == DEFAULT_SUBJECT_ID) null else DEFAULT_SUBJECT_ID } }
                item { SectionHeader(stringResource(R.string.subject_sec_user)) }
                items(ui.subjects, key = { it.id }) { s ->
                    SubjectRow(s, selected = selectedId == s.id, onEdit = { onEditSubject(s.id) }) { selectedId = if (selectedId == s.id) null else s.id }
                }
            }
            Row(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(stringResource(R.string.subject_new), onNew, Modifier.weight(1f))
                PrimaryButton(
                    stringResource(R.string.subject_confirm),
                    onClick = applyAndBack,
                    modifier = Modifier.weight(1f),
                    enabled = selectedId != null,
                )
            }
        }
    }
}

/** Default 伪用户行（监视器图标 + Default + 「默认」tag；选中态同用户行）。 */
@Composable
private fun DefaultRow(selected: Boolean, onClick: () -> Unit) {
    SelectableSurface(selected, onClick) {
        SubjectIcon(Icons.Filled.Monitor)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(DEFAULT_SUBJECT.alias, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
            PillTag(stringResource(R.string.subject_default), BT.onSurfaceV, BT.surface2)
        }
        CircleCheck(checked = selected, color = BT.tertiary)
    }
}

/** 用户选择列表行（单选；选中=描边高亮 + ✓；尾部 ✎ 进编辑）。 */
@Composable
private fun SubjectRow(subject: Subject, selected: Boolean, onEdit: () -> Unit, onClick: () -> Unit) {
    SelectableSurface(selected, onClick) {
        SubjectIcon(Icons.Filled.Person)
        Column(Modifier.weight(1f)) {
            Text(subject.alias, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
            Text(subjectBioLine(subject), fontSize = 11.sp, color = BT.onSurfaceV)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.subject_edit_title), tint = BT.onSurfaceV, modifier = Modifier.size(20.dp))
        }
        CircleCheck(checked = selected, color = BT.tertiary)
    }
}

@Composable
private fun SelectableSurface(selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        color = if (selected) BT.tertiaryC else BT.surface,
        shape = RoundedCornerShape(BT.radius),
        border = if (selected) BorderStroke(1.5.dp, BT.tertiary) else null,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SubjectIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(BT.tertiaryC), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = BT.tertiary, modifier = Modifier.size(20.dp))
    }
}

/** 体征摘要分项（性别本地化 + 出生/身高/体重）。供 badge pills 与摘要行复用。 */
@Composable
fun subjectBioBadges(subject: Subject): List<String> = buildList {
    add(sexLabel(subject.sex))
    if (subject.birth.isNotBlank()) add(subject.birth)
    subject.heightCm?.let { add("${it}cm") }
    subject.weightKg?.let { add("${it}kg") }
}

/** 体征摘要行（性别本地化 + 其余字段）。i18n 拼接放 UI 层（不在 commonMain，§7.6）。 */
@Composable
fun subjectBioLine(subject: Subject): String = subjectBioBadges(subject).joinToString(" · ")

/** 用户C · 用户编辑（v6 重设计）：别名仅英数 + 出生 DatePicker + 身高/体重 Slider 刻度尺。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectEditScreen(
    subjectId: String?,
    onBack: () -> Unit,
    vm: SubjectViewModel = koinViewModel(),
) {
    var loadedId by rememberSaveable { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
    var birth by rememberSaveable { mutableStateOf("") } // "yyyy-M"，空=未设
    var heightCm by rememberSaveable { mutableStateOf(170) }
    var weightKg by rememberSaveable { mutableStateOf(70.0) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(subjectId) {
        if (subjectId != null) {
            vm.load(subjectId)?.let { s ->
                loadedId = s.id; alias = s.alias; sex = s.sex; birth = s.birth
                s.heightCm?.let { heightCm = it }
                s.weightKg?.let { weightKg = it }
            }
        }
    }
    val effectiveId = remember(loadedId) { loadedId ?: vm.newId() }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            // 原型 用户C app-bar 仅标题、无副标（红线#1：表单字段概述属注释区，不进屏）
            title = if (subjectId == null) stringResource(R.string.subject_edit_new_title) else stringResource(R.string.subject_edit_title),
            onBack = onBack,
            actions = {
                Text(
                    stringResource(R.string.action_save), fontSize = 14.sp, fontWeight = FontWeight.W700, color = if (alias.isNotBlank()) BT.primary else BT.outline,
                    modifier = Modifier.clickable(enabled = alias.isNotBlank()) { saveAndBack(vm, effectiveId, alias, sex, birth, heightCm, weightKg, onBack) },
                )
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(stringResource(R.string.subject_field_alias)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { v -> alias = v.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.subject_field_alias_hint), fontSize = 13.sp) },
                )
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
            // 出生年月：点开 Material DatePicker 选（不手输），存 "yyyy-M"
            Field(stringResource(R.string.subject_field_birth)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BT.outlineV),
                    color = BT.surface,
                    modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                ) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            birth.ifBlank { stringResource(R.string.subject_field_birth_hint) },
                            fontSize = 14.sp,
                            color = if (birth.isBlank()) BT.outline else BT.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = BT.onSurfaceV, modifier = Modifier.size(18.dp))
                    }
                }
            }
            // 身高：Slider 刻度尺（整数 cm），label 左 + 大值右
            SliderField(
                label = stringResource(R.string.subject_field_height),
                valueText = "$heightCm",
                unit = "cm",
                value = heightCm.toFloat(),
                range = 120f..220f,
                onChange = { heightCm = it.roundToInt() },
            )
            // 体重：Slider 刻度尺（步进 0.5 kg）
            SliderField(
                label = stringResource(R.string.subject_field_weight),
                valueText = "%.1f".format(weightKg),
                unit = "kg",
                value = weightKg.toFloat(),
                range = 30f..150f,
                onChange = { weightKg = (kotlin.math.round(it * 2) / 2.0) },
            )
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius)) {
                Text(stringResource(R.string.subject_privacy_note), fontSize = 11.5.sp, color = BT.onPrimaryC, modifier = Modifier.padding(12.dp))
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.subject_save), onClick = { saveAndBack(vm, effectiveId, alias, sex, birth, heightCm, weightKg, onBack) }, enabled = alias.isNotBlank())
            // 编辑已有用户时可删除（新建态无此项）；删除前确认——若删的是当前采集对象，
            // 采集主界面会回到"未选择"并拦截开始（不静默换人）
            if (subjectId != null) {
                OutlineBtn(stringResource(R.string.subject_delete), onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth(), color = BT.error)
            }
        }
    }

    if (showDeleteConfirm && subjectId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.subject_delete_confirm_title)) },
            text = { Text(stringResource(R.string.subject_delete_confirm_msg, alias)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; vm.delete(subjectId); onBack() }) {
                    Text(stringResource(R.string.action_delete), color = BT.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = parseBirthToMillis(birth))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { birth = millisToYearMonth(it) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) { DatePicker(state = state) }
    }
}

/** 身高/体重刻度尺：label 左 + 大号当前值右 + 下方 Slider（连续滑动，值在 onChange 取整/步进）。 */
@Composable
private fun SliderField(label: String, valueText: String, unit: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = BT.onSurfaceV)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(valueText, fontSize = 22.sp, fontWeight = FontWeight.W800, color = BT.primary)
                Text(unit, fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

private fun saveAndBack(
    vm: SubjectViewModel, id: String, alias: String, sex: Sex, birth: String, heightCm: Int, weightKg: Double, onBack: () -> Unit,
) {
    vm.save(
        Subject(
            id = id,
            alias = alias.trim(),
            sex = sex,
            birth = birth.trim(),
            heightCm = heightCm,
            weightKg = weightKg,
        ),
    )
    onBack()
}

/** millis → "yyyy-M"（只取年月，月不补零，与既有 birth 口径一致）。 */
private fun millisToYearMonth(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH) + 1}"
}

/** "yyyy-M" → millis（该月 1 号），解析失败返回 null（DatePicker 用当前日期）。 */
private fun parseBirthToMillis(birth: String): Long? {
    val parts = birth.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 2) return null
    return java.util.Calendar.getInstance().apply { clear(); set(parts[0], parts[1] - 1, 1) }.timeInMillis
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = BT.onSurfaceV)
        content()
    }
}
