package io.bluetrace.ui.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.CountBadge
import io.bluetrace.ui.components.EmptyState
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.ScanFilterBar
import io.bluetrace.ui.components.ScanPermissionBanner
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.components.rememberScanPermission
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.DeviceRowUi
import io.bluetrace.viewmodel.DeviceScanViewModel
import org.koin.androidx.compose.koinViewModel

/** 设备A/B/C · 设备连接（扁平列表 + 限额 + RSSI 过滤 + 60s 自停，§5.3）。 */
@Composable
fun DeviceConnectScreen(
    onBack: () -> Unit,
    onBluetoothOff: () -> Unit,
    vm: DeviceScanViewModel = koinViewModel(),
    envVm: io.bluetrace.viewmodel.EnvironmentViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val env by envVm.state.collectAsStateWithLifecycle()

    // 扫描前置权限门（含定位）：进页面即请求；授权到位才开扫，撤权即停并提示（§5.2 / D-3）
    val perm = rememberScanPermission()
    LaunchedEffect(Unit) { if (!perm.granted) perm.request() }
    LaunchedEffect(perm.granted) { if (perm.granted) vm.startScan() else vm.stopScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.device_title),
            subtitle = stringResource(R.string.device_subtitle),
            onBack = onBack,
            actions = { CountBadge(pluralStringResource(R.plurals.connected_count, ui.connectedCount, ui.connectedCount)) },
        )

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!perm.granted) ScanPermissionBanner(perm)
            ScanFilterBar(
                query = ui.query,
                onQueryChange = vm::setQuery,
                rssiThreshold = ui.rssiThreshold,
                onRssiChange = vm::setRssiThreshold,
            )
            if (ui.atDutLimit) {
                Surface(color = BT.warningC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_limit_banner), fontSize = 11.5.sp, color = BT.onWarningC, modifier = Modifier.padding(10.dp))
                }
            }
        }

        if (ui.showEmpty) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = stringResource(R.string.device_empty_title),
                subtitle = stringResource(R.string.device_empty_sub),
                actionText = stringResource(R.string.device_rescan),
                onAction = { vm.startScan() },
                modifier = Modifier.padding(top = 40.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 10.dp),
            ) {
                items(ui.rows, key = { it.device.id }) { row ->
                    DeviceRow(row, onClick = { if (!row.disabled) vm.toggleConnect(row.device) })
                }
            }
        }

        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            OutlineBtn(
                text = if (ui.scanning) stringResource(R.string.device_stop_scan) else stringResource(R.string.device_rescan),
                onClick = {
                    when {
                        ui.scanning -> vm.stopScan()
                        env.status(io.bluetrace.shared.domain.RequirementId.BLUETOOTH_ON) != io.bluetrace.shared.domain.RequirementStatus.GRANTED -> onBluetoothOff() // 蓝牙关 → 启动E
                        !perm.granted -> perm.request() // 权限不足 → 弹授权（含定位）
                        else -> vm.startScan()
                    }
                },
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
        val connected = row.link == LinkState.CONNECTED
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (connected) BT.successC else if (isRef) BT.tertiaryC else BT.primaryC),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isRef) Icons.Filled.Favorite else Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = if (connected) BT.success else if (isRef) BT.tertiary else BT.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 名称过长 → 截断省略，给标签留位（避免标签被挤到换行竖排）
                    Text(
                        device.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W700,
                        color = BT.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        isRef -> PillTag(stringResource(R.string.device_tag_reference), BT.onTertiaryC, BT.tertiaryC)
                        row.b2a -> PillTag("B2A", BT.primaryDeep, BT.primaryC) // 有 B2A 服务的手表（与控制台页一致）
                    }
                }
                Text(
                    buildString {
                        append(device.address)
                        append(" · ")
                        append("${device.rssi} dBm")
                        if (isRef && device.profileId == PROFILE_HRS) append(" · 0x180D")
                    },
                    fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            LinkBadge(row.link, row.disabled)
        }
    }
}

@Composable
private fun LinkBadge(link: LinkState, disabled: Boolean) {
    if (disabled) {
        PillTag(stringResource(R.string.link_at_limit), BT.onSurfaceV, BT.surface2)
        return
    }
    when (link) {
        LinkState.CONNECTED -> StatusPill(stringResource(R.string.link_connected), BT.onSuccessC, BT.successC)
        LinkState.CONNECTING -> StatusPill(stringResource(R.string.link_connecting), BT.onPrimaryC, BT.primaryC)
        LinkState.RECONNECTING -> StatusPill(stringResource(R.string.link_reconnecting), BT.onWarningC, BT.warningC)
        LinkState.DISCONNECTED -> StatusPill(stringResource(R.string.link_disconnected), BT.onSurfaceV, BT.surface2, showDot = false)
    }
}
