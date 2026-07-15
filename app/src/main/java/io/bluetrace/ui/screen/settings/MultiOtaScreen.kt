package io.bluetrace.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.shared.s7.OtaPhase
import io.bluetrace.shared.util.formatMb
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.shared.s7.DeviceOtaItem
import io.bluetrace.shared.s7.DeviceOtaStatus
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import io.bluetrace.viewmodel.MultiOtaUiState
import io.bluetrace.viewmodel.MultiOtaViewModel
import io.bluetrace.viewmodel.ScanRow

/**
 * 多设备 OTA 屏体（顶栏「多设备」开关打开时渲染；开关关=单设备现状 [OtaTestScreen]）。
 * 顺序：单个共用烧录包 → 工作队列（扫描添加/删除/重试）→ 批量控制 → 执行日志。见 Docs/OTA/S7多设备OTA_设计.md。
 * @param modifier 由父 [OtaTestScreen] 传入 `Modifier.weight(1f)`（本屏体是外层 Column 的直接子项）。
 */
@Composable
fun MultiOtaBody(modifier: Modifier, vm: MultiOtaViewModel, ui: MultiOtaUiState) {
    val scan by vm.scan.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.setPackage(it) }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        MultiPackageSection(ui, enabled = !ui.running, onAdd = { picker.launch(arrayOf("*/*")) }, onClear = { vm.clearPackage() }, onDemo = { vm.loadDemoPackage() })
        QueueHeader(count = ui.queue.size, enabled = !ui.running, onScan = { vm.openScanSheet() })
        if (ui.queue.isEmpty()) {
            QueueEmptyHint()
        } else {
            ui.queue.forEach { item ->
                QueueRow(
                    item = item,
                    onRemove = { vm.removeFromQueue(item.device.id) },
                    onRetry = { vm.retry(item.device.id) },
                )
            }
        }
        BatchControl(ui, summary = vm.summaryLine(), onStart = { vm.startBatch() }, onStop = { vm.stopBatch() }, onRetryAll = { vm.retryAllFailed() })
        MultiLogTerminal(vm)
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
    if (scan.open) {
        ScanAddSheet(
            rows = scan.rows,
            onDismiss = { vm.closeScanSheet() },
            onToggle = { vm.toggleScanSelect(it) },
            onConfirm = { vm.confirmScanAdd() },
        )
    }
}

// ---- 烧录包（锁 1 个，全队列共用）----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiPackageSection(ui: MultiOtaUiState, enabled: Boolean, onAdd: () -> Unit, onClear: () -> Unit, onDemo: () -> Unit) {
    val pkg = ui.pkg
    if (pkg == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BT.radius))
                .dashedBorder(BT.outline, BT.radius)
                .combinedClickable(enabled = enabled, onClick = onAdd, onLongClick = onDemo)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = BT.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加烧录包（zip）· 全队列共用", fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.primary)
        }
    } else {
        Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BT.primaryC), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BT.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pkg.sourceName, fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pkg.fileCount} 文件 · ${formatMb(pkg.totalSize)} · 全队列共用", fontSize = 11.sp, color = BT.onSurfaceV)
                }
                if (enabled) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "移除烧录包", tint = BT.onSurfaceV,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onClear() }.padding(2.dp),
                    )
                }
            }
        }
    }
}

// ---- 队列标题 + 扫描添加 ----

@Composable
private fun QueueHeader(count: Int, enabled: Boolean, onScan: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("设备队列", fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
        Spacer(Modifier.width(8.dp))
        Surface(color = BT.primaryC, shape = RoundedCornerShape(999.dp)) {
            Text("$count 台", fontSize = 11.sp, fontWeight = FontWeight.W700, color = BT.onPrimaryC, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = enabled) { onScan() }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = if (enabled) BT.primary else BT.outline, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("扫描添加", fontSize = 12.sp, fontWeight = FontWeight.W700, color = if (enabled) BT.primary else BT.outline)
        }
    }
}

@Composable
private fun QueueEmptyHint() {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Text("队列为空 · 点「扫描添加」把 S7 手表加入工作队列", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.padding(16.dp))
    }
}

// ---- 队列行 ----

@Composable
private fun QueueRow(item: DeviceOtaItem, onRemove: () -> Unit, onRetry: () -> Unit) {
    val flashing = item.status == DeviceOtaStatus.FLASHING
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp)) {
            // 顶行: 状态点 + 设备名(占满) + 重试 + 状态药丸 + 删除
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatDot(item.status)
                Spacer(Modifier.width(10.dp))
                Text(
                    item.device.name, fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.retriable) {
                    Icon(
                        Icons.Filled.Refresh, contentDescription = "重试", tint = BT.primary,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable { onRetry() }.padding(2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                StatusChip(item.status)
                if (item.removable) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.DeleteOutline, contentDescription = "从队列移除", tint = BT.onSurfaceV,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onRemove() }.padding(2.dp),
                    )
                }
            }
            // 详情行: 整行铺满(左缩进 34dp 对齐设备名), 版本/电量前后值不再被右侧药丸挤占
            Spacer(Modifier.height(5.dp))
            Text(
                rowMeta(item), fontSize = 11.sp, color = BT.onSurfaceV, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 34.dp),
            )
            if (flashing) {
                val p = item.progress
                Spacer(Modifier.height(6.dp))
                Column(Modifier.padding(start = 34.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.phase?.label() ?: "刷写中", fontSize = 11.sp, fontWeight = FontWeight.W700, color = BT.primary, modifier = Modifier.weight(1f))
                        if (p != null && item.phase == OtaPhase.Downloading) {
                            Text(fmtSpeed(p.bytesPerSec), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (p != null) Text("${(frac(p) * 100).toInt()}%", fontSize = 11.sp, color = BT.onSurfaceV)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { p?.let { frac(it) } ?: 0f }, modifier = Modifier.fillMaxWidth(), trackColor = BT.surface2)
                    if (p != null) {
                        Text("${p.fileIdx + 1}/${p.fileCount}  ${p.fileName}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatDot(status: DeviceOtaStatus) {
    data class Look(val bg: Color, val fg: Color, val glyph: ImageVector?)
    val look = when (status) {
        DeviceOtaStatus.DONE -> Look(BT.successC, BT.onSuccessC, Icons.Filled.Check)
        DeviceOtaStatus.FAILED -> Look(BT.errorC, BT.error, Icons.Filled.Close)
        DeviceOtaStatus.SKIPPED_LOW_BATTERY -> Look(BT.warningC, BT.onWarningC, null)
        DeviceOtaStatus.CONNECTING, DeviceOtaStatus.READING, DeviceOtaStatus.FLASHING -> Look(BT.primaryC, BT.primary, null)
        DeviceOtaStatus.QUEUED -> Look(BT.surface2, BT.onSurfaceV, null)
    }
    Box(Modifier.size(24.dp).clip(CircleShape).background(look.bg), contentAlignment = Alignment.Center) {
        val g = look.glyph
        if (g != null) Icon(g, contentDescription = null, tint = look.fg, modifier = Modifier.size(14.dp))
        else Box(Modifier.size(7.dp).clip(CircleShape).background(look.fg))
    }
}

@Composable
private fun StatusChip(status: DeviceOtaStatus) = when (status) {
    DeviceOtaStatus.QUEUED -> StatusPill("待升级", BT.onSurfaceV, BT.surface2, showDot = false)
    DeviceOtaStatus.CONNECTING -> StatusPill("连接中", BT.onPrimaryC, BT.primaryC, showDot = false)
    DeviceOtaStatus.READING -> StatusPill("读取中", BT.onPrimaryC, BT.primaryC, showDot = false)
    DeviceOtaStatus.FLASHING -> StatusPill("刷写中", BT.onPrimaryC, BT.primaryC, showDot = true)
    DeviceOtaStatus.DONE -> StatusPill("完成", BT.onSuccessC, BT.successC)
    DeviceOtaStatus.FAILED -> StatusPill("失败", BT.error, BT.errorC, showDot = false)
    DeviceOtaStatus.SKIPPED_LOW_BATTERY -> StatusPill("跳过", BT.onWarningC, BT.warningC, showDot = false)
}

private fun rowMeta(item: DeviceOtaItem): String = when (item.status) {
    DeviceOtaStatus.QUEUED -> "${item.device.rssi} dBm · 待连接"
    DeviceOtaStatus.CONNECTING -> "连接中…"
    DeviceOtaStatus.READING -> "读取版本 / 电量…"
    DeviceOtaStatus.FLASHING -> buildString {
        append("版本 ${item.versionBefore ?: "—"}")
        item.batteryBefore?.let { append(" · 电量 $it%") }
    }
    DeviceOtaStatus.DONE -> "版本 ${item.versionBefore ?: "—"} → ${item.versionAfter ?: "未知"} · 电量 ${pct(item.batteryBefore)} → ${pct(item.batteryAfter)}"
    DeviceOtaStatus.FAILED -> item.failReason ?: "—" // describe() 的结构化原因（出错指令/文件+偏移）
    DeviceOtaStatus.SKIPPED_LOW_BATTERY -> "${item.failReason ?: "—"} · 已跳过"
}

private fun pct(v: Int?): String = v?.let { "$it%" } ?: "—"

// ---- 批量控制 ----

@Composable
private fun BatchControl(ui: MultiOtaUiState, summary: String, onStart: () -> Unit, onStop: () -> Unit, onRetryAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.running) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BT.error).clickable { onStop() }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("停止批量", fontSize = 15.sp, fontWeight = FontWeight.W800, color = Color.White)
            }
        } else {
            PrimaryButton("开始批量升级（${ui.queuedCount} 台）", onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = ui.canStart)
            if (ui.hasRetriable) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BT.surface).clickable { onRetryAll() }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = BT.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试全部失败（${ui.failCount + ui.skipCount}）", fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.primary)
                }
            }
        }
        if (ui.queue.isNotEmpty()) {
            Text(summary, fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

// ---- 执行日志 ----

@Composable
private fun MultiLogTerminal(vm: MultiOtaViewModel) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("执行日志", fontSize = 12.sp, fontWeight = FontWeight.W800, color = BT.onSurfaceV, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.DeleteOutline, contentDescription = "清空", tint = BT.onSurfaceV,
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable { vm.clearLog() }.padding(2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(BT.radiusSm)).background(TermBg)) {
                LazyColumn(Modifier.fillMaxWidth().padding(10.dp), reverseLayout = true) {
                    items(vm.logLines.asReversed()) { line ->
                        Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = lineColor(line), lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

// ---- 扫描添加表（底部弹窗；只入队、不连接）----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanAddSheet(rows: List<ScanRow>, onDismiss: () -> Unit, onToggle: (String) -> Unit, onConfirm: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true) // 直接全展开: 「加入队列」按钮不必上拉即见
    val selectedCount = rows.count { it.selected }
    // 列表消化不掉的滚动/惯性全部吃掉、不上抛给 sheet——在列表里下滑不会把抽屉拖关
    //（关抽屉 = 顶部横条下拉 / 点遮罩 / 返回键）。
    val keepSheetStill = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BT.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text("扫描添加到队列", fontSize = 16.sp, fontWeight = FontWeight.W800, color = BT.onSurface)
            Text("仅入队、不连接 · 只支持 S7(B2A) 手表", fontSize = 11.sp, color = BT.onSurfaceV)
            Spacer(Modifier.height(10.dp))
            // 行序稳定 + 无 key：滚动位置按页面距离（index/offset）记，不追踪设备——扫描重排时视口不跳；
            // 顶部锚点即"支持的 + 信号最强"（排序保证），打开就看到手边的表。
            LazyColumn(
                Modifier.fillMaxWidth().height(320.dp).nestedScroll(keepSheetStill),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(rows) { r -> ScanRowItem(r, onToggle) }
                if (rows.isEmpty()) {
                    item { Text("扫描中…", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.padding(vertical = 24.dp)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (selectedCount > 0) "加入队列（$selectedCount）" else "加入队列",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCount > 0,
            )
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun ScanRowItem(r: ScanRow, onToggle: (String) -> Unit) {
    val selectable = r.supported && !r.inQueue
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable { onToggle(r.device.id) } else Modifier),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            // 勾选框 / 禁选标
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (r.selected) BT.primary else BT.surface2),
                contentAlignment = Alignment.Center,
            ) {
                // 已选=勾; 不支持/未选=空态(标签已说明"不支持 OTA")
                if (r.selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(r.device.name, fontSize = 13.sp, fontWeight = FontWeight.W700, color = if (selectable) BT.onSurface else BT.onSurfaceV, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.device.address} · ${r.device.rssi} dBm", fontSize = 11.sp, color = BT.onSurfaceV)
            }
            val (tag, fg, bg) = when {
                r.inQueue -> Triple("已在队列", BT.onSurfaceV, BT.surface2)
                r.supported -> Triple("S7 · 可加", BT.onPrimaryC, BT.primaryC)
                else -> Triple("不支持 OTA", BT.onSurfaceV, BT.surface2)
            }
            Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
                Text(tag, fontSize = 10.5.sp, fontWeight = FontWeight.W600, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}

// ---- 基础件 ----

private val TermBg = Color(0xFF14171B)
private val TermInfo = Color(0xFF8AD98A)
private val TermErr = Color(0xFFF07A7A)
private val TermOk = Color(0xFF5FC9F0)
private val TermMuted = Color(0xFF9AA4AE)

private fun lineColor(line: String): Color = when {
    line.contains("✗") || line.contains("失败") || line.contains("错误") || line.contains("停止") -> TermErr
    line.contains("✓") || line.contains("完成") || line.contains("已连接") || line.contains("加入队列") -> TermOk
    line.contains("·") || line.contains("»") || line.contains("──") -> TermMuted
    else -> TermInfo
}

private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
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
    OtaPhase.Scanning -> "扫描回连"
    OtaPhase.Reconnecting -> "重连中"
    OtaPhase.ReadingVersion -> "读取版本"
    OtaPhase.Done -> "完成"
}

private fun frac(p: io.bluetrace.shared.s7.OtaProgress): Float =
    if (p.totalBytes > 0) (p.sentBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else 0f
