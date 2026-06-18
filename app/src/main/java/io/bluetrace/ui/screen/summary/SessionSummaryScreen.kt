package io.bluetrace.ui.screen.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.domain.StopReason
import io.bluetrace.shared.util.formatDurationHms
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.ExportUiState
import io.bluetrace.viewmodel.ExportViewModel
import io.bluetrace.viewmodel.RunViewModel
import org.koin.androidx.compose.koinViewModel

/** 结束A · 采集结束摘要 + 导出（A 进度 / B 完成 Toast / C 失败）。 */
@Composable
fun SessionSummaryScreen(
    onViewDetail: (String) -> Unit,
    onDone: () -> Unit,
    runVm: RunViewModel = koinViewModel(),
    exportVm: ExportViewModel = koinViewModel(),
) {
    val finished by runVm.finished.collectAsStateWithLifecycle()
    val exportState by exportVm.state.collectAsStateWithLifecycle()
    val summary = finished

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(
                title = stringResource(R.string.summary_title),
                subtitle = summary?.folderName ?: "",
                onBack = { runVm.reset(); exportVm.reset(); onDone() },
            )
            if (summary == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.summary_no_data), color = BT.onSurfaceV) }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(summary)
                    SectionHeader(stringResource(R.string.summary_sec_files))
                    FileTile(Icons.Filled.Storage, BT.warning, BT.warningC, stringResource(R.string.summary_file_raw), stringResource(R.string.summary_file_raw_sub), stringResource(R.string.badge_ok))
                    FileTile(Icons.Filled.CheckCircle, BT.success, BT.successC, stringResource(R.string.summary_file_csv), stringResource(R.string.summary_file_csv_sub), null)
                    FileTile(Icons.Filled.Inventory2, BT.primary, BT.primaryC, stringResource(R.string.summary_file_combo), stringResource(R.string.summary_file_combo_sub), null)
                    if (summary.gnssEnabled) FileTile(Icons.Filled.DataObject, BT.tertiary, BT.tertiaryC, stringResource(R.string.summary_file_gps), stringResource(R.string.summary_file_gps_sub), null)
                    FileTile(Icons.Filled.Description, BT.tertiary, BT.tertiaryC, stringResource(R.string.summary_file_manifest), stringResource(R.string.summary_file_manifest_sub), null)
                    if (summary.stopReason != StopReason.NORMAL) {
                        val reasonText = when (summary.stopReason) {
                            StopReason.STORAGE_FULL -> stringResource(R.string.summary_stop_storage_full)
                            StopReason.INTERRUPTED -> stringResource(R.string.summary_stop_interrupted)
                            else -> ""
                        }
                        Surface(color = BT.warningC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                            Text(reasonText, fontSize = 12.sp, color = BT.onWarningC, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                Row(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineBtn(stringResource(R.string.summary_view_detail), { onViewDetail(summary.folderName) }, Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.action_export), { exportVm.export(summary.folderName) }, Modifier.weight(1f))
                }
            }
        }

        ExportOverlay(exportState, onDismiss = { exportVm.reset() })
    }
}

@Composable
private fun SummaryCard(summary: SessionSummary) {
    Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatDurationHms(summary.durationMs), fontSize = 32.sp, fontWeight = FontWeight.W800, color = BT.onPrimaryC)
            Text(
                stringResource(R.string.summary_meta, summary.subjectAlias, summary.mode.label, summary.deviceCount, summary.sensorCount),
                fontSize = 12.sp, color = BT.onPrimaryC,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillTag(stringResource(R.string.pill_lines, summary.totalLines.toInt()), BT.onPrimaryC, BT.surface)
                PillTag("${"%.2f".format(summary.totalBytes / 1024.0 / 1024.0)} MB", BT.onPrimaryC, BT.surface)
                PillTag(stringResource(R.string.pill_one_session), BT.onPrimaryC, BT.surface)
            }
        }
    }
}

@Composable
private fun FileTile(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, title: String, subtitle: String, badge: String?) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = bg, shape = RoundedCornerShape(9.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(8.dp).height(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                Text(subtitle, fontSize = 11.sp, color = BT.onSurfaceV)
            }
            if (badge != null) PillTag(badge, BT.onSuccessC, BT.successC)
        }
    }
}

/** 导出态覆盖：进度 / 完成 Toast / 失败。 */
@Composable
fun ExportOverlay(state: ExportUiState, onDismiss: () -> Unit) {
    when (state) {
        is ExportUiState.InProgress -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius)) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(0.8f)) {
                        Text(stringResource(R.string.export_in_progress), fontSize = 15.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(state.current, fontSize = 11.sp, color = BT.onSurfaceV)
                    }
                }
            }
        }
        is ExportUiState.Done -> Toast(stringResource(R.string.export_done, state.displayPath), onDismiss)
        is ExportUiState.Failed -> Toast(stringResource(R.string.export_failed, state.message), onDismiss, error = true)
        is ExportUiState.InsufficientSpace -> InsufficientSpaceDialog(state.requiredBytes, state.availableBytes, onDismiss)
        ExportUiState.Idle -> Unit
    }
}

/** 导出D · 存储不足（导出前预检阻断，§5.8）。 */
@Composable
private fun InsufficientSpaceDialog(requiredBytes: Long, availableBytes: Long, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000)), contentAlignment = Alignment.Center) {
        Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.padding(32.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.export_need_space_title), fontSize = 16.sp, fontWeight = FontWeight.W700, color = BT.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.export_need_space_body,
                        "%.1f".format(requiredBytes / 1024.0 / 1024.0),
                        "%.1f".format(availableBytes / 1024.0 / 1024.0),
                    ),
                    fontSize = 12.sp, color = BT.onSurfaceV, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(stringResource(R.string.export_go_clean), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun Toast(text: String, onDismiss: () -> Unit, error: Boolean = false) {
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(3000); onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(color = if (error) BT.error else androidx.compose.ui.graphics.Color(0xEA141820), shape = RoundedCornerShape(BT.radius)) {
            Text(text, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.W600, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
