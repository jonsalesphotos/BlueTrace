package com.example.bluetrace.ui.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.DeviceKind
import com.example.bluetrace.shared.domain.LinkState
import com.example.bluetrace.shared.domain.PROFILE_HRS
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.CountBadge
import com.example.bluetrace.ui.components.EmptyState
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PillTag
import com.example.bluetrace.ui.components.StatusPill
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.DeviceRowUi
import com.example.bluetrace.viewmodel.DeviceScanViewModel
import org.koin.androidx.compose.koinViewModel

/** 设备A/B/C · 设备连接（扁平列表 + 限额 + RSSI 过滤 + 60s 自停，§5.3）。 */
@Composable
fun DeviceConnectScreen(
    onBack: () -> Unit,
    vm: DeviceScanViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = "设备连接",
            subtitle = "单击连接 · 再点断开",
            onBack = onBack,
            actions = { CountBadge("已连 ${ui.connectedCount}") },
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("按名称 / MAC 过滤", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(BT.radius),
            )
            Text("RSSI 过滤 · 仅显示强于 ${ui.rssiThreshold} dBm", fontSize = 11.sp, color = BT.onSurfaceV)
            Slider(
                value = ui.rssiThreshold.toFloat(),
                onValueChange = { vm.setRssiThreshold(it.toInt()) },
                valueRange = -99f..-30f,
            )
            if (ui.atDutLimit) {
                Surface(color = BT.warningC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "DUT 已连 3 台（上限）· 链路可能不稳定。DUT 最多 3 + 参考心率带 1。",
                        fontSize = 11.5.sp, color = BT.onWarningC, modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        if (ui.showEmpty) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = "未发现设备",
                subtitle = "确认 DUT 已上电并在范围内，再重新扫描。",
                actionText = "重新扫描",
                onAction = { vm.startScan() },
                modifier = Modifier.padding(top = 40.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(ui.rows, key = { it.device.id }) { row ->
                    DeviceRow(row, onClick = { if (!row.disabled) vm.toggleConnect(row.device) })
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            OutlineBtn(
                text = if (ui.scanning) "停止扫描" else "重新扫描",
                onClick = { if (ui.scanning) vm.stopScan() else vm.startScan() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DeviceRow(row: DeviceRowUi, onClick: () -> Unit) {
    val device = row.device
    val isRef = device.kind == DeviceKind.REFERENCE
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (row.disabled) 0.5f else 1f)
            .clickable(enabled = !row.disabled, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (isRef) BT.tertiaryC else BT.primaryC),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isRef) Icons.Filled.Favorite else Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = if (isRef) BT.tertiary else BT.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                    if (isRef) PillTag("参考", BT.onTertiaryC, BT.tertiaryC)
                }
                Text(
                    buildString {
                        append(device.address)
                        append(" · ")
                        append("${device.rssi} dBm")
                        if (isRef && device.profileId == PROFILE_HRS) append(" · 0x180D")
                    },
                    fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
                )
            }
            LinkBadge(row.link, row.disabled)
        }
    }
}

@Composable
private fun LinkBadge(link: LinkState, disabled: Boolean) {
    if (disabled) {
        PillTag("已达上限", BT.onSurfaceV, BT.surface2)
        return
    }
    when (link) {
        LinkState.CONNECTED -> StatusPill("已连接", BT.onSuccessC, BT.successC)
        LinkState.CONNECTING -> StatusPill("连接中", BT.onPrimaryC, BT.primaryC)
        LinkState.RECONNECTING -> StatusPill("重连中", BT.onWarningC, BT.warningC)
        LinkState.DISCONNECTED -> StatusPill("未连接", BT.onSurfaceV, BT.surface2, showDot = false)
    }
}
