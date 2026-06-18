package io.bluetrace.ui.screen.data

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.util.formatDurationHms
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.CircleCheck
import io.bluetrace.ui.components.EmptyState
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.screen.summary.ExportOverlay
import io.bluetrace.ui.theme.BT
import io.bluetrace.ui.theme.sensorColor
import io.bluetrace.viewmodel.DataViewModel
import io.bluetrace.viewmodel.ExportViewModel
import io.bluetrace.viewmodel.ModeFilter
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
                    title = stringResource(R.string.data_selected_title, ui.selected.size),
                    onBack = { vm.exitSelection() },
                    actions = { Text(stringResource(R.string.action_select_all), fontSize = 13.sp, fontWeight = FontWeight.W600, color = BT.primary, modifier = Modifier.clickable { vm.selectAll() }) },
                )
            } else {
                BtTopBar(
                    title = stringResource(R.string.data_title),
                    subtitle = pluralStringResource(R.plurals.session_count, ui.totalCount, ui.totalCount) + " · ${"%.1f".format(ui.totalBytes / 1024.0 / 1024.0)} MB",
                    actions = {
                        if (ui.sessions.isNotEmpty()) {
                            Text(stringResource(R.string.action_select), fontSize = 13.sp, fontWeight = FontWeight.W600, color = BT.primary, modifier = Modifier.clickable { ui.sessions.firstOrNull()?.let { vm.enterSelection(it.folderName) } })
                        }
                    },
                )
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::setQuery,
                    placeholder = { Text(stringResource(R.string.data_search_hint), fontSize = 13.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(BT.radius),
                )
                Row(Modifier.background(BT.surface2, RoundedCornerShape(999.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        ModeFilter.ALL to stringResource(R.string.data_filter_all),
                        ModeFilter.WEAR to stringResource(R.string.data_filter_wear),
                        ModeFilter.UNWEAR to stringResource(R.string.data_filter_unwear),
                    ).forEach { (f, label) ->
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
                    title = if (searching) stringResource(R.string.data_search_empty_title) else stringResource(R.string.data_empty_title),
                    subtitle = if (searching) stringResource(R.string.data_search_empty_sub) else stringResource(R.string.data_empty_sub),
                    modifier = Modifier.padding(top = 40.dp),
                )
            } else {
                val grouped = ui.sessions.groupBy { dayKey(it.startEpochMs) }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                    grouped.forEach { (day, sessions) ->
                        item(key = "h_$day") { SectionHeader(dayHeader(day)) }
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
                        OutlineBtn(stringResource(R.string.action_delete), { vm.deleteSelected() }, Modifier.weight(1f), color = BT.error)
                        PrimaryButton(pluralStringResource(R.plurals.export_selected, ui.selected.size, ui.selected.size), { exportVm.exportMany(ui.selected.toList()) }, Modifier.weight(1f))
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
                    stringResource(R.string.data_meta, "%.2f".format(session.totalBytes / 1024.0 / 1024.0), formatDurationHms(session.durationMs)),
                    fontSize = 11.sp, color = BT.onSurfaceV, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 稳定的日期分组键（yyyy-MM-dd）。 */
private fun dayKey(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** 分组头本地化："今天/昨天 · date · 会话文件夹"。 */
@Composable
private fun dayHeader(dayKey: String): String {
    val date = LocalDate.parse(dayKey)
    val rel = when (date) {
        LocalDate.now() -> stringResource(R.string.data_today) + " · "
        LocalDate.now().minusDays(1) -> stringResource(R.string.data_yesterday) + " · "
        else -> ""
    }
    return "$rel$dayKey · ${stringResource(R.string.data_group_suffix)}"
}
