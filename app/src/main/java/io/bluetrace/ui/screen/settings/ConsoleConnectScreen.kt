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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.s7.B2aDetect
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.ConsoleConnectViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 控制台内置连接页：非参考设备**限连 1 台**（连新自动断旧）；参考心率带不在此页。
 * 工程风格；进入自动扫描、退出自停。
 */
@Composable
fun ConsoleConnectScreen(onBack: () -> Unit, vm: ConsoleConnectViewModel = koinViewModel()) {
    val ui by vm.uiState.collectAsState()

    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.console_connect_title),
            subtitle = stringResource(R.string.console_connect_sub),
            onBack = onBack,
        )
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            items(ui.rows, key = { it.device.id }) { row ->
                val d = row.device
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BT.surface)
                        .clickable(enabled = !row.disabled) { vm.toggleConnect(d) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace)
                            if (B2aDetect.matchesAdvertisement(d)) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "B2A",
                                    fontSize = 10.sp, fontWeight = FontWeight.W800, color = BT.primaryDeep,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(BT.primaryC)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(
                            "${d.address} · ${d.rssi} dBm",
                            fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
                        )
                    }
                    when {
                        ui.busyId == d.id -> StatusPill("…", fg = BT.onSurfaceV, bg = BT.surface2, showDot = false)
                        row.link == LinkState.CONNECTED -> StatusPill("CONNECTED")
                        row.link == LinkState.CONNECTING -> StatusPill("CONNECTING", fg = BT.onSurfaceV, bg = BT.surface2)
                        else -> StatusPill("—", fg = BT.onSurfaceV, bg = BT.surface2, showDot = false)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Row(Modifier.navigationBarsPadding().padding(14.dp)) {
            OutlineBtn(
                stringResource(if (ui.scanning) R.string.console_scan_stop else R.string.console_scan_start),
                onClick = { if (ui.scanning) vm.stopScan() else vm.startScan() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
