package io.bluetrace.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.s7.OtaPhase
import io.bluetrace.shared.s7.OtaResult
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.MultiOtaViewModel
import io.bluetrace.viewmodel.OtaIterationResult
import io.bluetrace.viewmodel.OtaPkgItem
import io.bluetrace.viewmodel.OtaTestUiState
import io.bluetrace.viewmodel.OtaTestViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * DEBUG「OTA 固件」屏。顶栏右侧「多设备」开关(默认关)切两态:
 * - **关(单设备)**: 连接 S7 → 「+」添加 1~2 个烧录包 → 刷入(1 包=单次; 2 包=A→B 循环)。见 [OtaTestViewModel]。
 * - **开(多设备)**: 工作队列串行逐台刷, 一个包全队列共用 → [MultiOtaBody] / [MultiOtaViewModel]。
 *   设计见 Docs/OTA/S7多设备OTA_设计.md。
 */
@Composable
fun OtaTestScreen(
    onBack: () -> Unit,
    onOpenConnect: () -> Unit,
    vm: OtaTestViewModel = koinViewModel(),
    multiVm: MultiOtaViewModel = koinViewModel(),
) {
    var multiMode by rememberSaveable { mutableStateOf(false) }
    val ui by vm.state.collectAsState()
    val mui by multiVm.state.collectAsState()
    val running = if (multiMode) mui.running else ui.running
    var confirmExit by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addPackage(it) }
    }
    // 运行中(单/多任一): 系统返回键 -> 弹确认框(不静默拦截), 确认后才中止并离开
    BackHandler(enabled = running) { confirmExit = true }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = "OTA 固件",
            subtitle = if (multiMode) {
                if (mui.running) "多设备批量 · 刷写中" else "多设备批量 · 队列 ${mui.queue.size} 台"
            } else {
                if (ui.running) "刷写中 · 请勿退出" else "刷入烧录包 · DEBUG"
            },
            onBack = { if (running) confirmExit = true else onBack() }, // 运行中点返回 -> 弹确认框
            actions = {
                Text("多设备", fontSize = 12.sp, fontWeight = FontWeight.W600, color = BT.onSurfaceV)
                Spacer(Modifier.width(6.dp))
                Switch(checked = multiMode, enabled = !running, onCheckedChange = { multiMode = it })
            },
        )
        if (multiMode) {
            MultiOtaBody(Modifier.weight(1f), multiVm, mui)
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                InfoCard(ui, onConnect = onOpenConnect, onRefreshVersion = { vm.refreshVersion() })
                PackageSection(ui, enabled = !ui.running, onAdd = { picker.launch(arrayOf("*/*")) }, onRemove = { vm.removePackage(it) })
                RunButton(ui, onStart = { vm.start() }, onStop = { vm.stop() })
                if (ui.running || ui.currentIteration > 0) ProgressCard(ui)
                if (ui.results.isNotEmpty()) ResultsCard(ui.results)
                LogTerminal(vm, ui)
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }

    // 运行中尝试离开 -> 确认框: 继续刷写(留下) / 中止并离开(停止后返回)
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("OTA 刷写进行中") },
            text = {
                Text(
                    "现在离开会中止本次刷写。设备不会变砖，但升级会失败、需重新刷写。确定要离开吗？",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    if (multiMode) multiVm.stopBatch() else vm.stop()
                    onBack()
                }) { Text("中止并离开", color = BT.error, fontWeight = FontWeight.W700) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续刷写") } },
        )
    }
}

// ---- 固定信息框：目标设备 + 当前版本；整卡可点进扫描/连接页(仅版本值 chip 例外=刷新版本) ----

@Composable
private fun InfoCard(ui: OtaTestUiState, onConnect: () -> Unit, onRefreshVersion: () -> Unit) {
    // 整卡可点 -> onConnect(扫描/连接页); 仅"当前版本"值 chip 嵌套点击=刷新版本。
    // 固定标签列宽(80dp)+行高(28dp)+状态恒为 StatusPill+chevron 常驻 => 运行态变化时卡片不跳动。
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(BT.radius)).clickable(enabled = !ui.running) { onConnect() },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // 行1: 目标设备 + 连接状态(只读指示)
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("目标设备", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.width(80.dp))
                    Text(
                        ui.device?.name ?: "未连接",
                        fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface,
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    LinkStatusPill(ui.link)
                }
                Spacer(Modifier.height(8.dp))
                // 行2: 当前版本(值 chip 可点刷新, 仅连接态)
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前版本", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.width(80.dp))
                    val canRefresh = ui.connected && !ui.versionReading && !ui.running
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .then(if (canRefresh) Modifier.background(BT.primaryC).clickable { onRefreshVersion() } else Modifier)
                            .padding(horizontal = if (canRefresh) 10.dp else 0.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (ui.versionReading) "读取中…" else (ui.version ?: "—"),
                            fontSize = 14.sp, fontWeight = FontWeight.W700, fontFamily = FontFamily.Monospace,
                            color = if (canRefresh) BT.primary else BT.onSurfaceV,
                        )
                        if (canRefresh) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新版本", tint = BT.primary, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = "进入扫描/连接", tint = if (ui.running) BT.outline else BT.onSurfaceV, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LinkStatusPill(link: LinkState) = when (link) {
    LinkState.CONNECTED -> StatusPill("已连接", BT.onSuccessC, BT.successC)
    LinkState.CONNECTING -> StatusPill("连接中", BT.onSurfaceV, BT.surface2, showDot = false)
    else -> StatusPill("未连接", BT.onSurfaceV, BT.surface2, showDot = false)
}

// ---- 烧录包：动态列表 + "+" 添加 ----

@Composable
private fun PackageSection(ui: OtaTestUiState, enabled: Boolean, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.packages.forEachIndexed { i, p ->
            PackageCard(index = i, item = p, enabled = enabled, onRemove = { onRemove(i) })
        }
        if (ui.canAdd) AddBox(onAdd)
        if (ui.packages.size == 2) {
            Text("两个包 → 循环升级（包1→包2→包1…，手动中断停止）", fontSize = 11.sp, color = BT.onSurfaceV)
        }
    }
}

@Composable
private fun PackageCard(index: Int, item: OtaPkgItem, enabled: Boolean, onRemove: () -> Unit) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BT.primaryC), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BT.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("烧录包 ${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                Text(item.sourceName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV)
            }
            if (enabled) {
                Icon(
                    Icons.Filled.Close, contentDescription = "移除", tint = BT.onSurfaceV,
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).clickable { onRemove() }.padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun AddBox(onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BT.radius))
            .dashedBorder(BT.outline, BT.radius)
            .clickable { onAdd() }
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = BT.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("添加烧录包（zip）", fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.primary)
    }
}

// ---- 运行控制 ----

@Composable
private fun RunButton(ui: OtaTestUiState, onStart: () -> Unit, onStop: () -> Unit) {
    if (ui.running) {
        // 单次=停止；循环=中断重复测试
        PrimaryButtonDanger(if (ui.loopMode) "中断重复测试" else "停止", onStop)
    } else {
        PrimaryButton("开始 OTA", onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = ui.canStart)
    }
}

@Composable
private fun PrimaryButtonDanger(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BT.error).clickable { onClick() }.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.W800, color = Color.White)
    }
}

@Composable
private fun ProgressCard(ui: OtaTestUiState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (ui.loopMode) "第 ${ui.currentIteration} 轮 · 包${ui.currentPkgIdx + 1}" else "刷入中"),
                fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface, modifier = Modifier.weight(1f),
            )
            ui.phase?.let { StatusPill(it.label(), BT.onPrimaryC, BT.primaryC, showDot = ui.running) }
        }
        val p = ui.progress
        if (p != null) {
            Spacer(Modifier.height(8.dp))
            Text("${p.fileIdx + 1}/${p.fileCount}  ${p.fileName}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV)
            val frac = if (p.totalBytes > 0) (p.sentBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), trackColor = BT.surface2)
            Text("${fmtBytes(p.sentBytes)} / ${fmtBytes(p.totalBytes)}  (${(frac * 100).toInt()}%)", fontSize = 11.sp, color = BT.onSurfaceV)
        }
    }
}

@Composable
private fun ResultsCard(results: List<OtaIterationResult>) {
    Card {
        Text("各轮结果", fontSize = 12.sp, fontWeight = FontWeight.W800, color = BT.onSurfaceV)
        Spacer(Modifier.height(6.dp))
        results.takeLast(20).forEach { r ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${r.iteration} 轮 · ${r.pkgLabel}", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.width(120.dp))
                val ok = r.result is OtaResult.Reconnected
                Text(r.result.label(), fontSize = 12.sp, fontWeight = FontWeight.W700, color = if (ok) BT.success else BT.error)
            }
        }
    }
}

// ---- 执行日志（终端风） ----

@Composable
private fun LogTerminal(vm: OtaTestViewModel, ui: OtaTestUiState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("执行日志", fontSize = 12.sp, fontWeight = FontWeight.W800, color = BT.onSurfaceV, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.DeleteOutline, contentDescription = "清空", tint = BT.onSurfaceV,
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).clickable { vm.clearLog() }.padding(2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(BT.radiusSm)).background(TermBg)) {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), reverseLayout = true) {
                items(vm.logLines.asReversed()) { line ->
                    Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = lineColor(line), lineHeight = 14.sp)
                }
            }
        }
    }
}

private val TermBg = Color(0xFF14171B)
private val TermInfo = Color(0xFF8AD98A)
private val TermErr = Color(0xFFF07A7A)
private val TermOk = Color(0xFF5FC9F0)
private val TermMuted = Color(0xFF9AA4AE)

private fun lineColor(line: String): Color = when {
    line.contains("✗") || line.contains("失败") || line.contains("错误") || line.contains("中断") -> TermErr
    line.contains("✓") || line.contains("完成") || line.contains("已连接") || line.contains("已添加") -> TermOk
    line.contains("·") || line.contains("»") -> TermMuted
    else -> TermInfo
}

// ---- 基础件 ----

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))),
        cornerRadius = CornerRadius(r, r),
    )
}

private fun OtaPhase.label(): String = when (this) {
    OtaPhase.Downloading -> "下载中"
    OtaPhase.WaitingReboot -> "等待复位"
    OtaPhase.Reconnecting -> "重连中"
    OtaPhase.ReadingVersion -> "读取版本"
    OtaPhase.Done -> "完成"
}

private fun OtaResult.label(): String = when (this) {
    is OtaResult.Reconnected -> "成功（版本 ${currentVersion ?: "未知"}）"
    is OtaResult.Failed -> "失败：$reason"
    OtaResult.DoneDownload -> "下载完成"
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
