package io.bluetrace.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.uwtp.PullProgress
import io.bluetrace.shared.uwtp.PullState
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.UwtpFileRow
import io.bluetrace.viewmodel.UwtpTestUiState
import io.bluetrace.viewmodel.UwtpTestViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * DEBUG「UWTP 传输」屏: S7 离线文件上传(手表->App)联调工具。
 * 连接 -> INFO/STATE -> 列文件 -> 拉取(进度/实时速率) -> 保存; 断连后可续传。
 * 见 [UwtpTestViewModel]; 协议实现 shared/uwtp(UWTP v0.2-draft)。
 */
@Composable
fun UwtpTestScreen(
    onBack: () -> Unit,
    onOpenConnect: () -> Unit,
    vm: UwtpTestViewModel = koinViewModel(),
) {
    val ui by vm.state.collectAsState()
    // 传输中系统返回: 不拦截离开, 但先中止传输保住断点(调试工具从宽)
    BackHandler(enabled = ui.pulling) {
        vm.abortPull()
        onBack()
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = "UWTP 传输",
            subtitle = if (ui.pulling) "拉取中 · v0.2-draft" else "离线文件上传联调 · DEBUG",
            onBack = {
                if (ui.pulling) vm.abortPull()
                onBack()
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            DeviceCard(ui, onConnect = onOpenConnect, onDisconnect = { vm.disconnect() }, onReconnect = { vm.reconnect() })
            ActionsRow(ui, vm)
            InfoSummary(ui)
            FilesCard(ui, vm)
            ui.pull?.let { PullCard(it, pulling = ui.pulling, onAbort = { vm.abortPull() }) }
            ui.error?.let { ErrCard(it) }
            LogPanel(vm)
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun UwtpCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun DeviceCard(ui: UwtpTestUiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onReconnect: () -> Unit) {
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(BT.radius)).clickable(enabled = !ui.pulling) { onConnect() },
    ) {
        Row(Modifier.padding(14.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("目标设备", fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.device?.name ?: "未连接",
                    fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (ui.connected && ui.mtu > 0) {
                    Text("MTU ${ui.mtu}", fontSize = 10.sp, color = BT.onSurfaceV)
                }
            }
            when {
                ui.canDisconnect -> ChipButton("断开", BT.error, BT.errorC, onDisconnect)
                ui.canReconnect -> ChipButton("重连", BT.primary, BT.primaryC, onReconnect)
                else -> LinkPill(ui.link)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = "进入扫描/连接", tint = if (ui.pulling) BT.outline else BT.onSurfaceV, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ChipButton(text: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.W700, color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun LinkPill(link: LinkState) = when (link) {
    LinkState.CONNECTED -> StatusPill("已连接", BT.onSuccessC, BT.successC)
    LinkState.CONNECTING -> StatusPill("连接中", BT.onSurfaceV, BT.surface2, showDot = false)
    else -> StatusPill("未连接", BT.onSurfaceV, BT.surface2, showDot = false)
}

@Composable
private fun ActionsRow(ui: UwtpTestUiState, vm: UwtpTestViewModel) {
    val enabled = ui.connected && !ui.pulling && ui.busy == null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip("查能力 INFO", enabled, Modifier.weight(1f)) { vm.queryInfo() }
        ActionChip("查状态 STATE", enabled, Modifier.weight(1f)) { vm.queryState() }
        ActionChip("列文件 LIST", enabled, Modifier.weight(1f)) { vm.listFiles() }
    }
}

@Composable
private fun ActionChip(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (enabled) BT.primaryC else BT.surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = enabled) { onClick() },
    ) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.W700,
            color = if (enabled) BT.primary else BT.onSurfaceV,
            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun InfoSummary(ui: UwtpTestUiState) {
    val info = ui.info
    val rt = ui.runtime
    if (info == null && rt == null) return
    UwtpCard {
        if (info != null) {
            KvRow("固件版本", "0x" + info.fwVersion.toString(16))
            KvRow("registry_rev", info.registryRev.toString())
            KvRow("RAW Schema", info.rawSchemas.joinToString { "0x${it.schemaId.toString(16)}r${it.schemaRev}" }.ifEmpty { "无" })
        }
        if (rt != null) {
            KvRow("offline/live/transfer", "${rt.offlineState} / ${rt.liveState} / ${rt.transferState}")
        }
    }
}

@Composable
private fun KvRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.width(150.dp))
        Text(v, fontSize = 12.sp, fontWeight = FontWeight.W600, color = BT.onSurface, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FilesCard(ui: UwtpTestUiState, vm: UwtpTestViewModel) {
    if (ui.files.isEmpty()) return
    UwtpCard {
        Text("设备文件(${ui.files.size})", fontSize = 12.sp, fontWeight = FontWeight.W800, color = BT.onSurfaceV)
        Spacer(Modifier.height(6.dp))
        ui.files.forEach { row -> FileRow(row, ui, vm) }
    }
}

@Composable
private fun FileRow(row: UwtpFileRow, ui: UwtpTestUiState, vm: UwtpTestViewModel) {
    val e = row.entry
    val active = ui.pull?.fileId == e.fileId && ui.pulling
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "file_${e.fileId}  ${fmtBytes(e.size)}",
                fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace,
            )
            val tags = buildList {
                if (e.flags != 0) add("flags=0x" + e.flags.toString(16))
                row.resumeOffset?.let { add("断点 ${fmtBytes(it)}") }
                if (row.savedPath != null) add("已保存")
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), fontSize = 10.sp, color = BT.onSurfaceV)
            }
        }
        if (row.resumeOffset != null && !ui.pulling) {
            TextButton(onClick = { vm.clearResume(e.fileId) }) { Text("清断点", fontSize = 11.sp, color = BT.onSurfaceV) }
        }
        val enabled = ui.connected && !ui.pulling && ui.busy == null
        ChipButton(
            text = when {
                active -> "拉取中"
                row.resumeOffset != null -> "续传"
                else -> "拉取"
            },
            fg = if (enabled) BT.primary else BT.onSurfaceV,
            bg = if (enabled) BT.primaryC else BT.surface2,
            onClick = { if (enabled) vm.pull(e.fileId) },
        )
    }
}

@Composable
private fun PullCard(p: PullProgress, pulling: Boolean, onAbort: () -> Unit) {
    UwtpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "file_${p.fileId} · ${stateText(p.state)}",
                fontSize = 12.sp, fontWeight = FontWeight.W800,
                color = when (p.state) {
                    PullState.FAILED -> BT.error
                    PullState.DONE -> BT.primary
                    else -> BT.onSurfaceV
                },
                modifier = Modifier.weight(1f),
            )
            if (pulling) {
                ChipButton("中止", BT.error, BT.errorC, onAbort)
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (p.totalSize > 0) p.contiguous.toFloat() / p.totalSize else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${fmtBytes(p.contiguous)} / ${fmtBytes(p.totalSize)}  (${p.percent}%)",
            fontSize = 12.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace,
        )
        Text(
            "实时 ${fmt1(p.instantKibps)} KiB/s · 平均 ${fmt1(p.avgKibps)} KiB/s",
            fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
        )
        Text(
            "帧 ${p.frames} · 重复 ${p.dupFrames} · 旧id ${p.staleFrames} · CRC错 ${p.crcErrors}",
            fontSize = 10.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
        )
    }
}

private fun stateText(s: PullState): String = when (s) {
    PullState.BEGINNING -> "建立事务"
    PullState.RECEIVING -> "接收中"
    PullState.FINISHING -> "收口 FINISH"
    PullState.DONE -> "完成"
    PullState.FAILED -> "失败"
    PullState.ABORTED -> "已中止"
}

@Composable
private fun ErrCard(msg: String) {
    Surface(color = BT.errorC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Text(msg, fontSize = 12.sp, color = BT.error, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun LogPanel(vm: UwtpTestViewModel) {
    UwtpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("协议日志", fontSize = 12.sp, fontWeight = FontWeight.W800, color = BT.onSurfaceV, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.DeleteOutline, contentDescription = "清空", tint = BT.onSurfaceV,
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).clickable { vm.logLines.clear() }.padding(2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)).background(TermBg)) {
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

private fun lineColor(line: String): Color = when {
    line.contains("✗") || line.contains("失败") || line.contains("错误") -> TermErr
    line.contains("✓") || line.contains("完成") || line.contains("已连接") -> TermOk
    else -> TermInfo
}

private fun fmtBytes(v: Long): String = when {
    v >= 1_048_576 -> ((v * 10 / 1_048_576) / 10.0).toString() + " MiB"
    v >= 1_024 -> ((v * 10 / 1_024) / 10.0).toString() + " KiB"
    else -> "$v B"
}

private fun fmt1(v: Double): String = ((v * 10).toLong() / 10.0).toString()
