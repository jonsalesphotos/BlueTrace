package io.bluetrace.ui.screen.data

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.screen.edit.EditSubjectSceneSheet
import io.bluetrace.ui.screen.summary.ExportOverlay
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.ExportViewModel
import io.bluetrace.viewmodel.SessionDetailViewModel
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.ui.sceneLabelZh
import org.koin.compose.koinInject
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
    var showEdit by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            BtTopBar(title = stringResource(R.string.detail_title), subtitle = folderName, onBack = onBack)
            val s = summary
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_loading), color = BT.onSurfaceV) }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                    item { ManifestSummary(s, onEdit = { showEdit = true }) }
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
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    // 勾选导出做真（2026-07-06 裁决）：部分勾选 → 只打包所选（zip 名带 _partial）；
                    // 全选/默认 → 整夹；零勾选 → 禁用。
                    val total = s.files.size
                    val sel = selectedFiles.size
                    val partial = sel in 1 until total
                    PrimaryButton(
                        if (partial) stringResource(R.string.detail_export_selected, sel) else stringResource(R.string.detail_export_folder),
                        { exportVm.export(folderName, if (partial) selectedFiles else null) },
                        enabled = sel > 0,
                    )
                }
            }
        }
        ExportOverlay(exportState, onDismiss = { exportVm.reset() }, onCancel = { exportVm.cancel() })

        summary?.let { s ->
            if (showEdit) {
                EditSubjectSceneSheet(
                    initialAlias = s.subjectAlias,
                    initialScene = s.scene,
                    onDismiss = { showEdit = false },
                    onConfirm = { subj, scene ->
                        vm.editTo(subj, scene) { res ->
                            if (res == null) {
                                Toast.makeText(context, context.getString(R.string.edit_conflict), Toast.LENGTH_SHORT).show()
                            } else {
                                // 重命名后本屏 folderName 已失效 → 回数据列表（已显示新名）
                                Toast.makeText(context, context.getString(R.string.edit_done), Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ManifestSummary(s: SessionSummary, onEdit: () -> Unit) {
    val catalog = koinInject<SceneCatalog>()
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricRow(stringResource(R.string.detail_metric_user_mode), "${s.subjectAlias} · ${sceneLabelZh(catalog, s.scene)}")
            MetricRow(stringResource(R.string.detail_metric_duration), formatDurationHms(s.durationMs))
            MetricRow(stringResource(R.string.detail_metric_lines_size), stringResource(R.string.detail_lines_size_value, s.totalLines.toString(), "%.2f".format(s.totalBytes / 1024.0 / 1024.0)))
            MetricRow(stringResource(R.string.detail_metric_device_paths), stringResource(R.string.detail_device_paths_value, s.deviceCount, s.sensorCount))
            MetricRow(stringResource(R.string.detail_metric_quality), stringResource(R.string.detail_metric_quality_value, s.quality.reconnectCount, s.quality.disconnectTotalMs.toInt(), s.quality.droppedPackets.toInt()))
            MetricRow(stringResource(R.string.detail_metric_stop_reason), s.stopReason.id)
            // ✎ 修改采集人/场景：整行浅蓝按钮（事后补改 → 回写 manifest + 重命名文件夹）
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onEdit() }) {
                Row(Modifier.padding(11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = BT.onPrimaryC, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.edit_subject_scene_action), fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onPrimaryC, modifier = Modifier.padding(start = 7.dp))
                }
            }
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
