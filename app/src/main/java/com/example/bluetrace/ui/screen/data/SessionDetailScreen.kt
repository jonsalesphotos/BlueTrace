package com.example.bluetrace.ui.screen.data

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.R
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.util.formatDurationHms
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.CircleCheck
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.screen.summary.ExportOverlay
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.ExportViewModel
import com.example.bluetrace.viewmodel.SessionDetailViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 数据C · 会话详情（manifest 摘要 + 文件夹构成逐项可选导出）。 */
@Composable
fun SessionDetailScreen(
    folderName: String,
    onBack: () -> Unit,
    vm: SessionDetailViewModel = koinViewModel { parametersOf(folderName) },
    exportVm: ExportViewModel = koinViewModel(),
) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val selectedFiles by vm.selectedFiles.collectAsStateWithLifecycle()
    val exportState by exportVm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(title = stringResource(R.string.detail_title), subtitle = folderName, onBack = onBack)
            val s = summary
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_loading), color = BT.onSurfaceV) }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                    item { ManifestSummary(s) }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionHeader(stringResource(R.string.detail_sec_files))
                            Text(stringResource(R.string.action_select_all), fontSize = 12.sp, fontWeight = FontWeight.W600, color = BT.primary, modifier = Modifier.padding(top = 14.dp).clickable { vm.selectAllFiles() })
                        }
                    }
                    items(s.files, key = { it.relativePath }) { file ->
                        Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth().clickable { vm.toggleFile(file.relativePath) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.relativePath, fontSize = 12.sp, fontWeight = FontWeight.W600, color = BT.onSurface, fontFamily = FontFamily.Monospace)
                                    Text(
                                        stringResource(R.string.detail_file_meta, file.category.name, file.lineCount.toString(), file.sizeBytes.toInt()),
                                        fontSize = 10.5.sp, color = BT.onSurfaceV,
                                    )
                                }
                                CircleCheck(checked = file.relativePath in selectedFiles)
                            }
                        }
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    PrimaryButton(stringResource(R.string.detail_export_folder), { exportVm.export(folderName) })
                }
            }
        }
        ExportOverlay(exportState) { exportVm.reset() }
    }
}

@Composable
private fun ManifestSummary(s: SessionSummary) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricRow(stringResource(R.string.detail_metric_user_mode), "${s.subjectAlias} · ${s.mode.label}")
            MetricRow(stringResource(R.string.detail_metric_duration), formatDurationHms(s.durationMs))
            MetricRow(stringResource(R.string.detail_metric_lines_size), stringResource(R.string.detail_lines_size_value, s.totalLines.toString(), "%.2f".format(s.totalBytes / 1024.0 / 1024.0)))
            MetricRow(stringResource(R.string.detail_metric_device_paths), stringResource(R.string.detail_device_paths_value, s.deviceCount, s.sensorCount))
            MetricRow(stringResource(R.string.detail_metric_quality), stringResource(R.string.detail_metric_quality_value, s.quality.reconnectCount, s.quality.disconnectTotalMs.toInt(), s.quality.droppedPackets.toInt()))
            MetricRow(stringResource(R.string.detail_metric_stop_reason), s.stopReason.id)
        }
    }
}

@Composable
private fun MetricRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, fontSize = 12.sp, color = BT.onSurfaceV)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
    }
}
