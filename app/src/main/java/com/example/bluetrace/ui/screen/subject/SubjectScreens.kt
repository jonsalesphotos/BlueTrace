package com.example.bluetrace.ui.screen.subject

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.Sex
import com.example.bluetrace.shared.domain.Subject
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.CircleCheck
import com.example.bluetrace.ui.components.EmptyState
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PillTag
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.SubjectViewModel
import org.koin.androidx.compose.koinViewModel

/** 用户A/B · 用户选择（点行即时选中并返回，§5.1）。 */
@Composable
fun SubjectSelectScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    vm: SubjectViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "选择用户", subtitle = "别名将写入文件名与 manifest", onBack = onBack)

        if (ui.subjects.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.PersonOff,
                title = "还没有用户",
                subtitle = "用户别名会写入文件名与 manifest，用于离线分类",
                actionText = "+ 新建用户",
                onAction = onNew,
                modifier = Modifier.padding(top = 40.dp),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                item { SectionHeader("用户档案") }
                items(ui.subjects, key = { it.id }) { subject ->
                    val current = subject.id == ui.currentId
                    Surface(
                        color = BT.surface,
                        shape = RoundedCornerShape(BT.radius),
                        border = if (current) androidx.compose.foundation.BorderStroke(1.5.dp, BT.tertiary) else null,
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.select(subject.id); onBack()
                        },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(subject.alias, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                                    if (current) PillTag("当前", BT.onTertiaryC, BT.tertiaryC)
                                }
                                Text(subject.bioLine(), fontSize = 11.sp, color = BT.onSurfaceV)
                            }
                            CircleCheck(checked = current, color = BT.tertiary)
                        }
                    }
                }
            }
            Column(Modifier.padding(16.dp)) {
                OutlineBtn("+ 新建用户", onNew, Modifier.fillMaxWidth())
            }
        }
    }
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
            title = if (subjectId == null) "新建用户" else "编辑用户",
            subtitle = "别名 · 体征档案",
            onBack = onBack,
            actions = {
                Text(
                    "保存", fontSize = 14.sp, fontWeight = FontWeight.W700, color = if (alias.isNotBlank()) BT.primary else BT.outline,
                    modifier = Modifier.clickable(enabled = alias.isNotBlank()) { saveAndBack(vm, effectiveId, alias, sex, birth, height, weight, onBack) },
                )
            },
        )
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("别名") {
                OutlinedTextField(value = alias, onValueChange = { alias = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("如 shb（建议别名，非真实姓名）", fontSize = 13.sp) })
            }
            Field("性别") {
                Row(Modifier.background(BT.surface2, RoundedCornerShape(999.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Sex.entries.forEach { s ->
                        val sel = s == sex
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) BT.surface else androidx.compose.ui.graphics.Color.Transparent).clickable { sex = s }.padding(horizontal = 16.dp, vertical = 7.dp),
                        ) { Text(s.label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.W700 else FontWeight.W500, color = if (sel) BT.onSurface else BT.onSurfaceV) }
                    }
                }
            }
            Field("出生年月") {
                OutlinedTextField(value = birth, onValueChange = { birth = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("如 1992-5", fontSize = 13.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Field("身高 cm") { OutlinedTextField(value = height, onValueChange = { height = it.filter(Char::isDigit) }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
                Box(Modifier.weight(1f)) { Field("体重 kg") { OutlinedTextField(value = weight, onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
            }
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius)) {
                Text("档案仅本地存储，一期不上传、不出设备，仅用于文件名与 manifest 离线分类。", fontSize = 11.5.sp, color = BT.onPrimaryC, modifier = Modifier.padding(12.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton("保存用户", onClick = { saveAndBack(vm, effectiveId, alias, sex, birth, height, weight, onBack) }, enabled = alias.isNotBlank())
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
