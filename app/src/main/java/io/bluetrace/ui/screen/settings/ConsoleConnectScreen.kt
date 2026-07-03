package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.ScanFilterBar
import io.bluetrace.ui.components.ScanPermissionBanner
import io.bluetrace.ui.components.rememberScanPermission
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.ConsoleConnectViewModel
import io.bluetrace.viewmodel.ConsoleDeviceRow
import org.koin.androidx.compose.koinViewModel

/**
 * 控制台内置连接页：只连受支持的 B2A 手表；无名设备不显示；已连置顶；顺序稳定；名称/信号过滤。
 * 单点即连/断该设备，不自动断开其它设备。
 */
@Composable
fun ConsoleConnectScreen(onBack: () -> Unit, vm: ConsoleConnectViewModel = koinViewModel()) {
    val ui by vm.uiState.collectAsState()

    // 扫描前置权限门（含定位）：进页面即请求；授权到位才开扫，撤权即停并提示
    val perm = rememberScanPermission()
    LaunchedEffect(Unit) { if (!perm.granted) perm.request() }
    LaunchedEffect(perm.granted) { if (perm.granted) vm.startScan() else vm.stopScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.console_connect_title),
            subtitle = stringResource(R.string.console_connect_sub),
            onBack = onBack,
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!perm.granted) ScanPermissionBanner(perm)
            ScanFilterBar(
                query = ui.query,
                onQueryChange = vm::setQuery,
                rssiThreshold = ui.rssiThreshold,
                onRssiChange = vm::setRssiThreshold,
            )
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) } // 与过滤条留出间距，不紧贴
            items(ui.rows, key = { it.device.id }) { row -> DeviceRow(row, onToggle = { vm.toggleConnect(row.device) }) }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(Modifier.navigationBarsPadding().padding(14.dp)) {
            // 扫描中 = 灰描边「停止扫描」(Stop)；已停 = 蓝实心「重新扫描」(Refresh)——两态明显区分
            if (ui.scanning) {
                OutlineBtn(
                    text = stringResource(R.string.console_scan_stop),
                    onClick = { vm.stopScan() },
                    leadingIcon = Icons.Filled.Stop,
                    color = BT.onSurfaceV,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.console_scan_start),
                    onClick = { if (!perm.granted) perm.request() else vm.startScan() },
                    leadingIcon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(row: ConsoleDeviceRow, onToggle: () -> Unit) {
    val d = row.device
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BT.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 不支持设备名灰显、且不带任何标签/按钮（用户要求：不要有文本、不要有按钮）
                // 名称过长 → 截断省略，给 B2A 标签留位
                Text(
                    d.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700,
                    color = if (row.supported) BT.onSurface else BT.onSurfaceV,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.supported) {
                    Spacer(Modifier.width(6.dp))
                    Tag("B2A", BT.primaryDeep, BT.primaryC)
                }
            }
            Text(
                "${d.address} · ${d.rssi} dBm",
                fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // 右侧连接/断开按钮：仅受支持设备有；不支持的无按钮无文本
        when {
            !row.supported && row.link != LinkState.CONNECTED -> Unit // 不支持：右侧空
            row.busy -> ActionChip(stringResource(R.string.console_row_connecting), BT.onSurfaceV, BT.surface2, enabled = false) {}
            row.link == LinkState.CONNECTED ->
                ActionChip(stringResource(R.string.console_row_disconnect), BT.error, BT.errorC, enabled = true, onClick = onToggle)
            row.link == LinkState.CONNECTING ->
                ActionChip(stringResource(R.string.console_row_connecting), BT.onSurfaceV, BT.surface2, enabled = false) {}
            else ->
                ActionChip(stringResource(R.string.console_row_connect), BT.onPrimaryC, BT.primary, enabled = true, onClick = onToggle)
        }
    }
}

@Composable
private fun ActionChip(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.W800,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

@Composable
private fun Tag(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.W800,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
