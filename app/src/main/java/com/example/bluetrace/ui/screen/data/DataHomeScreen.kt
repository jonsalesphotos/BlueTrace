package com.example.bluetrace.ui.screen.data

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.util.formatDurationHms
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.CircleCheck
import com.example.bluetrace.ui.components.EmptyState
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PillTag
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.screen.summary.ExportOverlay
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.ui.theme.sensorColor
import com.example.bluetrace.viewmodel.DataViewModel
import com.example.bluetrace.viewmodel.ExportViewModel
import com.example.bluetrace.viewmodel.ModeFilter
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 数据A/B/D · 数据 Tab：会话列表 + 搜索 + 模式筛选 + 多选/批量。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataHomeScreen(
    onOpenDetail: (String) -> Unit,
    vm: DataViewModel = koinViewModel(),
    exportVm: ExportViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val exportState by exportVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (ui.selectionMode) {
                BtTopBar(
                    title = "已选 ${ui.selected.size}",
                    onBack = { vm.exitSelection() },
                    actions = { Text("全选", fontSize = 13.sp, fontWeight = FontWeight.W600, color = BT.primary, modifier = Modifier.clickable { vm.selectAll() }) },
                )
            } else {
                BtTopBar(
                    title = "数据",
                    subtitle = "采集会话 · ${ui.totalCount} 个 · ${"%.1f".format(ui.totalBytes / 1024.0 / 1024.0)} MB",
                    actions = {
                        if (ui.sessions.isNotEmpty()) {
                            Text("选择", fontSize = 13.sp, fontWeight = FontWeight.W600, color = BT.primary, modifier = Modifier.clickable { ui.sessions.firstOrNull()?.let { vm.enterSelection(it.folderName) } })
                        }
                    },
                )
            }

            // 搜索 + 模式筛选
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::setQuery,
                    placeholder = { Text("搜索会话 / 用户 / 设备", fontSize = 13.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(BT.radius),
                )
                Row(Modifier.background(BT.surface2, RoundedCornerShape(999.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(ModeFilter.ALL to "全部", ModeFilter.WEAR to "Wear", ModeFilter.UNWEAR to "Unwear").forEach { (f, label) ->
                        val sel = ui.modeFilter == f
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (sel) BT.surface else Color.Transparent).clickable { vm.setFilter(f) }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.W700 else FontWeight.W500, color = if (sel) BT.onSurface else BT.onSurfaceV)
                        }
                    }
                }
            }

            if (ui.sessions.isEmpty()) {
                val searching = ui.query.isNotBlank()
                EmptyState(
                    icon = if (searching) Icons.Filled.SearchOff else Icons.Filled.FolderOff,
                    title = if (searching) "未搜到结果" else "还没有采集数据",
                    subtitle = if (searching) "换个关键词试试" else "采集结束后会在这里按会话文件夹归档",
                    modifier = Modifier.padding(top = 40.dp),
                )
            } else {
                val grouped = ui.sessions.groupBy { dayLabel(it.startEpochMs) }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                    grouped.forEach { (day, sessions) ->
                        item(key = "h_$day") { SectionHeader("$day · 会话文件夹") }
                        items(sessions, key = { it.folderName }) { session ->
                            SessionCard(
                                session = session,
                                selectionMode = ui.selectionMode,
                                selected = session.folderName in ui.selected,
                                onClick = { if (ui.selectionMode) vm.toggleSelected(session.folderName) else onOpenDetail(session.folderName) },
                                onLongPress = { vm.enterSelection(session.folderName) },
                            )
                        }
                    }
                }
                if (ui.selectionMode) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineBtn("删除", { vm.deleteSelected() }, Modifier.weight(1f), color = BT.error)
                        PrimaryButton("导出所选 (${ui.selected.size})", { exportVm.exportMany(ui.selected.toList()) }, Modifier.weight(1f))
                    }
                }
            }
        }
        ExportOverlay(exportState) { exportVm.reset() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionSummary,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, BT.primary) else null,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectionMode) CircleCheck(checked = selected)
            Column(Modifier.weight(1f)) {
                Text(session.folderName, fontSize = 12.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.enabledTypes.sortedBy { it.ordinal }.forEach { t ->
                        PillTag(t.id, sensorColor(t.id), sensorColor(t.id).copy(alpha = 0.14f))
                    }
                }
                Text(
                    "${"%.2f".format(session.totalBytes / 1024.0 / 1024.0)} MB · ${formatDurationHms(session.durationMs)}",
                    fontSize = 11.sp, color = BT.onSurfaceV, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun dayLabel(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        LocalDate.now() -> "今天 · $date"
        LocalDate.now().minusDays(1) -> "昨天 · $date"
        else -> date.toString()
    }
}
