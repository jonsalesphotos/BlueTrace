package io.bluetrace.ui.screen.collect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.bluetrace.shared.domain.CollectMode
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.EntryTile
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.CollectHomeViewModel
import org.koin.androidx.compose.koinViewModel

/** 采集A · 采集 Tab 主界面（设备/用户/模式入口 + 开始采集）。 */
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

    // 回前台/进入时复检蓝牙开关 + 权限（设备入口据此分流到 启动E）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshEnv() }

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
                trailing = {
                    if (ui.connectedCount > 0) {
                        PillTag(pluralStringResource(R.plurals.connected_count, ui.connectedCount, ui.connectedCount), BT.onSuccessC, BT.successC)
                    } else {
                        PillTag(stringResource(R.string.collect_entry_device_goto), BT.onSurfaceV, BT.surface2)
                    }
                },
            )
            EntryTile(
                icon = Icons.Filled.Person,
                iconColor = BT.tertiary,
                iconBg = BT.tertiaryC,
                title = stringResource(R.string.collect_entry_user_title),
                subtitle = ui.currentSubject?.let { "${it.alias} · ${io.bluetrace.ui.screen.subject.subjectBioLine(it)}" } ?: stringResource(R.string.collect_entry_user_empty),
                onClick = onOpenSubject,
            )
            ModeTile(mode = ui.mode, onSelect = vm::setMode)

            if (ui.gnssEnabled) {
                Text(stringResource(R.string.collect_gnss_note), fontSize = 11.sp, color = BT.tertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
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
                onClick = {
                    if (vm.startSession()) {
                        io.bluetrace.service.CollectionService.start(context)
                        onStart()
                    }
                },
                enabled = ui.canStart,
            )
        }
    }
}

@Composable
private fun ModeTile(mode: CollectMode, onSelect: (CollectMode) -> Unit) {
    EntryTile(
        icon = Icons.Filled.GraphicEq,
        iconColor = BT.success,
        iconBg = BT.successC,
        title = stringResource(R.string.collect_entry_mode_title),
        subtitle = stringResource(R.string.collect_entry_mode_sub),
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
