package com.example.bluetrace.ui.screen.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.domain.StopReason
import com.example.bluetrace.shared.util.formatDurationHms
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PillTag
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.ExportUiState
import com.example.bluetrace.viewmodel.ExportViewModel
import com.example.bluetrace.viewmodel.RunViewModel
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
                title = "采集已结束",
                subtitle = summary?.folderName ?: "",
                onBack = { runVm.reset(); exportVm.reset(); onDone() },
            )
            if (summary == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("无会话数据", color = BT.onSurfaceV) }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(summary)
                    SectionHeader("会话文件夹构成")
                    FileTile(Icons.Filled.Storage, BT.warning, BT.warningC, "原始 HEX 行日志", "append-only · 可重放 · 1 份/来源 · source of truth", "OK")
                    FileTile(Icons.Filled.CheckCircle, BT.success, BT.successC, "解码 CSV（每模块）", "PPG_G / PPG_IR / ACC / HR …", null)
                    FileTile(Icons.Filled.Inventory2, BT.primary, BT.primaryC, "组合包兼容 CSV", "汇顶 PPG+ACC 合并", null)
                    if (summary.gnssEnabled) FileTile(Icons.Filled.DataObject, BT.tertiary, BT.tertiaryC, "gps.csv", "本机 GNSS 一路", null)
                    FileTile(Icons.Filled.Description, BT.tertiary, BT.tertiaryC, "session_manifest.json", "unix 起点 · 时区 · 用户 · 设备 · 质量小结", null)
                    if (summary.stopReason != StopReason.NORMAL) {
                        Surface(color = BT.warningC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                when (summary.stopReason) {
                                    StopReason.STORAGE_FULL -> "存储满，已停止并保存"
                                    StopReason.INTERRUPTED -> "上次采集异常中断，已保存"
                                    else -> ""
                                },
                                fontSize = 12.sp, color = BT.onWarningC, modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineBtn("查看详情", { onViewDetail(summary.folderName) }, Modifier.weight(1f))
                    PrimaryButton("导出", { exportVm.export(summary.folderName) }, Modifier.weight(1f))
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
                "${summary.subjectAlias} · ${summary.mode.label} · ${summary.deviceCount} 设备 · ${summary.sensorCount} 路",
                fontSize = 12.sp, color = BT.onPrimaryC,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillTag("${summary.totalLines} 行", BT.onPrimaryC, BT.surface)
                PillTag("${"%.2f".format(summary.totalBytes / 1024.0 / 1024.0)} MB", BT.onPrimaryC, BT.surface)
                PillTag("1 会话", BT.onPrimaryC, BT.surface)
            }
        }
    }
}

@Composable
private fun FileTile(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, title: String, subtitle: String, badge: String?) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.height(36.dp).padding(0.dp), contentAlignment = Alignment.Center) {
                Surface(color = bg, shape = RoundedCornerShape(9.dp)) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(8.dp).height(20.dp))
                }
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
                        Text("导出中…", fontSize = 15.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(state.current, fontSize = 11.sp, color = BT.onSurfaceV)
                    }
                }
            }
        }
        is ExportUiState.Done -> Toast("✓ 已导出到 ${state.displayPath}", onDismiss)
        is ExportUiState.Failed -> Toast("导出失败：${state.message}", onDismiss, error = true)
        ExportUiState.Idle -> Unit
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
