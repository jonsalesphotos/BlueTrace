package com.example.bluetrace.ui.screen.collect

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.CollectMode
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.EntryTile
import com.example.bluetrace.ui.components.PillTag
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.StatusPill
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.CollectHomeViewModel
import org.koin.androidx.compose.koinViewModel

/** 采集A · 采集 Tab 主界面（设备/用户/模式入口 + 开始采集）。 */
@Composable
fun CollectHomeScreen(
    onOpenDevice: () -> Unit,
    onOpenSubject: () -> Unit,
    onStart: () -> Unit,
    vm: CollectHomeViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = "采集",
            subtitle = "多传感器 BLE 数据采集 · 准备会话",
            actions = { StatusPill(if (ui.canStart) "就绪" else "未就绪", fg = if (ui.canStart) BT.onSuccessC else BT.onWarningC, bg = if (ui.canStart) BT.successC else BT.warningC) },
        )
        Column(
            Modifier.padding(16.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryTile(
                icon = Icons.Filled.Bluetooth,
                iconColor = BT.primary,
                iconBg = BT.primaryC,
                title = "设备 · DUT 多传感器",
                subtitle = "扁平列表 · DUT≤3 + 参考心率带≤1",
                onClick = onOpenDevice,
                trailing = {
                    if (ui.connectedCount > 0) {
                        PillTag("${ui.connectedCount} 已连接", BT.onSuccessC, BT.successC)
                    } else {
                        PillTag("去连接", BT.onSurfaceV, BT.surface2)
                    }
                },
            )
            EntryTile(
                icon = Icons.Filled.Person,
                iconColor = BT.tertiary,
                iconBg = BT.tertiaryC,
                title = "用户 · 写入文件名与 manifest",
                subtitle = ui.currentSubject?.let { "${it.alias} · ${it.bioLine()}" } ?: "未选择用户",
                onClick = onOpenSubject,
            )
            ModeTile(mode = ui.mode, onSelect = vm::setMode)

            if (ui.gnssEnabled) {
                Text("· 本次会话含本机 GNSS（gps.csv）", fontSize = 11.sp, color = BT.tertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
            if (!ui.canStart) {
                Text(
                    when {
                        ui.currentSubject == null -> "请先选择用户"
                        ui.connectedCount == 0 -> "请先连接至少一台设备"
                        else -> ""
                    },
                    fontSize = 12.sp, color = BT.warning,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PrimaryButton(
                "开始采集",
                onClick = {
                    if (vm.startSession()) {
                        com.example.bluetrace.service.CollectionService.start(context)
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
    com.example.bluetrace.ui.components.EntryTile(
        icon = Icons.Filled.GraphicEq,
        iconColor = BT.success,
        iconBg = BT.successC,
        title = "采集模式 · 自动命名会话文件夹",
        subtitle = "Wear / Unwear（写文件名前缀与 manifest）",
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
