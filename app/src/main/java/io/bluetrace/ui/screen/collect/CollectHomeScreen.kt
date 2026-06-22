package io.bluetrace.ui.screen.collect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.data.android.BlueTracePermissions
import io.bluetrace.shared.domain.CollectMode
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.EntryTile
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.CollectHomeViewModel
import io.bluetrace.viewmodel.StartOutcome
import org.koin.androidx.compose.koinViewModel

/** 采集A · 采集 Tab 主界面（设备/用户/模式入口 + 开始采集）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectHomeScreen(
    onOpenDevice: () -> Unit,
    onOpenSubject: () -> Unit,
    onStart: () -> Unit,
    onBluetoothOff: () -> Unit,
    vm: CollectHomeViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lowSpace by remember { mutableStateOf(false) }
    var showMissingSheet by remember { mutableStateOf(false) }
    var missingHandled by remember { mutableStateOf(false) }
    val scanPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshEnv() }

    // 回前台/进入时复检蓝牙开关 + 权限（设备入口据此分流到 启动E）+ 低空间提示（§5.2）
    LaunchedEffect(Unit) {
        vm.refreshEnv()
        lowSpace = vm.isLowSpace()
    }
    // 启动C：后续启动静默检查发现缺「附近设备」→ 弹一次 ModalSheet（§5.1）
    LaunchedEffect(ui.scanConnectMissing) {
        if (ui.scanConnectMissing && !missingHandled) showMissingSheet = true
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.tab_collect),
            subtitle = stringResource(R.string.collect_subtitle),
            actions = {
                StatusPill(
                    if (ui.canStart) stringResource(R.string.status_ready) else stringResource(R.string.status_not_ready),
                    fg = if (ui.canStart) BT.onSuccessC else BT.onWarningC,
                    bg = if (ui.canStart) BT.successC else BT.warningC,
                )
            },
        )
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryTile(
                icon = Icons.Filled.Bluetooth,
                iconColor = BT.primary,
                iconBg = BT.primaryC,
                title = stringResource(R.string.collect_entry_device_title),
                subtitle = stringResource(R.string.collect_entry_device_sub),
                onClick = { if (ui.bluetoothOn) onOpenDevice() else onBluetoothOff() }, // 蓝牙关 → 启动E
                value = if (ui.connectedCount > 0)
                    pluralStringResource(R.plurals.connected_count, ui.connectedCount, ui.connectedCount)
                else stringResource(R.string.collect_entry_device_goto),
                valueColor = if (ui.connectedCount > 0) BT.success else BT.onSurfaceV,
                showChevron = true,
                belowContent = if (ui.connectedDevices.isNotEmpty()) {
                    {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ui.connectedDevices.forEach { d -> PillTag("● ${d.name}", BT.primary, BT.primaryC) }
                        }
                    }
                } else null,
            )
            EntryTile(
                icon = Icons.Filled.Person,
                iconColor = BT.tertiary,
                iconBg = BT.tertiaryC,
                title = stringResource(R.string.collect_entry_user_title),
                subtitle = stringResource(R.string.collect_entry_user_sub),
                onClick = onOpenSubject,
                value = ui.currentSubject?.alias ?: stringResource(R.string.collect_entry_user_empty),
                valueColor = if (ui.currentSubject != null) BT.onSurface else BT.onSurfaceV,
                showChevron = true,
                belowContent = ui.currentSubject?.let { s ->
                    {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            io.bluetrace.ui.screen.subject.subjectBioBadges(s).forEach { b -> PillTag(b, BT.onSurfaceV, BT.surface2) }
                        }
                    }
                },
            )
            ModeTile(mode = ui.mode, onSelect = vm::setMode)

            if (ui.gnssEnabled) {
                Text(stringResource(R.string.collect_gnss_note), fontSize = 11.sp, color = BT.tertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
            if (lowSpace) {
                Text(stringResource(R.string.storage_low_hint), fontSize = 12.sp, color = BT.warning, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (!ui.canStart) {
                val hint = when {
                    ui.currentSubject == null -> stringResource(R.string.collect_hint_need_user)
                    ui.connectedCount == 0 -> stringResource(R.string.collect_hint_need_device)
                    else -> ""
                }
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 12.sp, color = BT.warning, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            PrimaryButton(
                stringResource(R.string.collect_start),
                leadingIcon = Icons.Filled.PlayArrow,
                onClick = {
                    when (vm.startSession()) {
                        StartOutcome.STARTED -> {
                            io.bluetrace.service.CollectionService.start(context)
                            onStart()
                        }
                        StartOutcome.STORAGE_FULL ->
                            android.widget.Toast.makeText(context, context.getString(R.string.storage_insufficient_start), android.widget.Toast.LENGTH_LONG).show()
                        StartOutcome.NOT_READY -> Unit
                    }
                },
                enabled = ui.canStart,
            )
        }
    }

    // 启动C · 缺权限弹层
    if (showMissingSheet) {
        io.bluetrace.ui.screen.permission.MissingPermsSheet(
            sheetState = rememberModalBottomSheetState(),
            onGrant = {
                scanPermLauncher.launch(BlueTracePermissions.hardScanConnect)
                showMissingSheet = false; missingHandled = true
            },
            onSkip = { showMissingSheet = false; missingHandled = true },
            onDismiss = { showMissingSheet = false; missingHandled = true },
        )
    }
}

@Composable
private fun ModeTile(mode: CollectMode, onSelect: (CollectMode) -> Unit) {
    EntryTile(
        icon = Icons.Filled.GraphicEq,
        iconColor = BT.success,
        iconBg = BT.successC,
        title = stringResource(R.string.collect_entry_mode_title),
        subtitle = stringResource(R.string.collect_entry_mode_sub, mode.label),
        trailing = {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(BT.surface2).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CollectMode.entries.forEach { m ->
                    val selected = m == mode
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) BT.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onSelect(m) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            m.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
                            color = if (selected) BT.onSurface else BT.onSurfaceV,
                        )
                    }
                }
            }
        },
    )
    Spacer(Modifier.height(0.dp))
}
