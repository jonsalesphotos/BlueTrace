package io.bluetrace.ui.screen.settings

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.s7.S7
import io.bluetrace.shared.s7.S7DateTime
import io.bluetrace.shared.s7.S7Person
import io.bluetrace.shared.util.epochMsToLocalParts
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.ConsoleToast
import io.bluetrace.viewmodel.ConsoleUiState
import io.bluetrace.viewmodel.DangerState
import io.bluetrace.viewmodel.DeviceConsoleViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 设置F · 设备维护（DUT）—— S7 手表控制台（工程风格）。
 * 协议：Docs/architecture-v2/s7/protocol-spec.md；取舍与缺口边界：同目录 plan.md / completeness-audit.md。
 */
@Composable
fun DeviceConsoleScreen(
    onBack: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenLogs: () -> Unit,
    vm: DeviceConsoleViewModel = koinViewModel(),
) {
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (ctrlKey, labelRes)
    var editPerson by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf(false) }

    // 一次性土 toast：指令成功/失败/导出结果
    LaunchedEffect(Unit) {
        vm.toasts.collect { t ->
            val msg = when (t) {
                ConsoleToast.TimeSynced -> ctx.getString(R.string.toast_time_synced)
                ConsoleToast.PersonWritten -> ctx.getString(R.string.toast_person_written)
                is ConsoleToast.FindToggled -> ctx.getString(if (t.on) R.string.toast_find_on else R.string.toast_find_off)
                ConsoleToast.Refreshed -> ctx.getString(R.string.toast_refreshed)
                is ConsoleToast.Failed -> ctx.getString(R.string.toast_failed, t.code)
                is ConsoleToast.Exported -> ctx.getString(R.string.toast_exported, t.displayPath)
                is ConsoleToast.ExportFailed -> ctx.getString(R.string.toast_export_failed, t.reason)
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.maint_title), subtitle = stringResource(R.string.maint_subtitle), onBack = onBack)

        if (ui.device == null) {
            if (ui.candidates.size > 1) {
                DevicePicker(ui, onPick = { vm.selectDevice(it) }, onOpenConnect = onOpenConnect)
            } else {
                NotConnected(onOpenConnect, onOpenLogs)
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            DeviceHeader(ui, onOpenConnect = onOpenConnect, onReconnect = { vm.reconnect() }, onDisconnect = { vm.disconnect() })
            if (ui.candidates.size > 1) {
                CandidateChips(ui, onPick = { vm.selectDevice(it) })
            }
            ui.error?.let { ErrorBar(it) { vm.clearError() } }
            IdentitySection(ui, onRefresh = { vm.refreshAll() })
            TimeSection(ui, onSync = { vm.syncTime() }, onCustom = { editTime = true })
            PersonSection(ui, onEdit = { editPerson = true }, onWriteSubject = { vm.writeCurrentSubject() })
            LogSection(ui, onPull = { vm.pullLog() }, onView = onOpenLogs)
            DangerSection(
                ui,
                onFind = { vm.toggleFind() },
                onDanger = { key, label -> confirm = key to label },
            )
            OpLogSection(vm, onExport = { vm.exportOpLog() })
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }

    if (editPerson) {
        PersonEditDialog(
            initial = ui.person,
            onDismiss = { editPerson = false },
            onConfirm = { p ->
                editPerson = false
                vm.writePerson(p)
            },
        )
    }

    if (editTime) {
        TimeEditDialog(
            initial = ui.deviceTime ?: vm.phoneNowDateTime(),
            phoneNow = { vm.phoneNowDateTime() },
            onDismiss = { editTime = false },
            onConfirm = { dt ->
                editTime = false
                vm.setCustomTime(dt)
            },
        )
    }

    // 危险命令确认
    confirm?.let { (key, labelRes) ->
        val label = stringResource(labelRes)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.console_danger_title, label)) },
            text = { Text(stringResource(R.string.console_danger_body), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.sendPower(key, labelRes)
                }) { Text(stringResource(R.string.console_confirm), color = BT.error, fontWeight = FontWeight.W700) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.console_cancel)) } },
        )
    }

    // 危险命令进行/结果
    when (val d = ui.danger) {
        is DangerState.Waiting -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(d.labelKey)) },
            text = { Text(stringResource(R.string.console_danger_wait), fontSize = 13.sp) },
            confirmButton = {},
        )
        is DangerState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissDanger() },
            title = { Text(stringResource(if (d.ok) R.string.console_danger_ok else R.string.console_danger_timeout)) },
            confirmButton = { TextButton(onClick = { vm.dismissDanger() }) { Text("OK") } },
        )
        null -> Unit
    }
}

@Composable
private fun NotConnected(onOpenConnect: () -> Unit, onOpenLogs: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.console_not_connected), fontSize = 16.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.console_not_connected_hint), fontSize = 13.sp, color = BT.onSurfaceV)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.console_go_connect), onClick = onOpenConnect, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        // 查看历史日志不需连接
        OutlineBtn(stringResource(R.string.console_log_view), onClick = onOpenLogs, modifier = Modifier.fillMaxWidth())
    }
}

/** 进入前已连多台（非参考）：先选择要控制的设备（用户要求）。 */
@Composable
private fun DevicePicker(ui: ConsoleUiState, onPick: (String) -> Unit, onOpenConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.console_pick_device), fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
        ui.candidates.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BT.surface)
                    .clickable { onPick(d.id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface, fontFamily = FontFamily.Monospace)
                    Text(d.address, fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlineBtn(stringResource(R.string.console_go_connect), onClick = onOpenConnect, modifier = Modifier.fillMaxWidth())
    }
}

/** 多候选切换 chips（当前受控高亮）。 */
@Composable
private fun CandidateChips(ui: ConsoleUiState, onPick: (String) -> Unit) {
    Section(stringResource(R.string.console_current_device)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.candidates.forEach { d ->
                val selected = d.id == ui.device?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) BT.primaryC else BT.surface2)
                        .clickable(enabled = !selected) { onPick(d.id) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.name,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.W800 else FontWeight.W500,
                        color = BT.onSurface,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) Text("✓", fontSize = 12.sp, color = BT.primaryDeep, fontWeight = FontWeight.W800)
                }
            }
        }
    }
}

/** 设备头卡：整卡点击即进「连接手表」页（连接/切换设备）。右侧 › 提示可点。 */
@Composable
private fun DeviceHeader(ui: ConsoleUiState, onOpenConnect: () -> Unit, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    Section(onClick = onOpenConnect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.device?.name ?: "", fontSize = 15.sp, fontWeight = FontWeight.W800, color = BT.onSurface, fontFamily = FontFamily.Monospace)
                Text(ui.device?.address ?: "", fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace)
                ui.heartbeat?.let {
                    Text(
                        stringResource(R.string.console_heartbeat_fmt, it.seq, it.batteryPercent),
                        fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace,
                    )
                }
            }
            // 连接态 → 「断开」按钮；断开态 → 「重连」按钮（均就地操作当前设备，不必回连接页）；中间态 → 状态标
            when (ui.link) {
                LinkState.CONNECTED -> HeaderActionChip(stringResource(R.string.console_disconnect), Icons.Filled.LinkOff, fg = BT.error, bg = BT.errorC, onClick = onDisconnect)
                LinkState.DISCONNECTED -> HeaderActionChip(stringResource(R.string.console_reconnect), Icons.Filled.Refresh, fg = BT.onPrimaryC, bg = BT.primary, onClick = onReconnect)
                LinkState.CONNECTING -> StatusPill("CONNECTING", fg = BT.onSurfaceV, bg = BT.surface2)
                LinkState.RECONNECTING -> StatusPill("RECONNECTING", fg = BT.onSurfaceV, bg = BT.surface2)
            }
            Spacer(Modifier.width(4.dp))
            Text("›", fontSize = 20.sp, color = BT.onSurfaceV)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.console_tap_to_switch), fontSize = 11.sp, color = BT.onSurfaceV)
    }
}

/** 头卡链路操作按钮（重连 / 断开）：其 clickable 消费点击，不会触发整卡进连接页。 */
@Composable
private fun HeaderActionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fg: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.W800, color = fg)
    }
}

@Composable
private fun ErrorBar(code: String, onDismiss: () -> Unit) {
    // NO_SUBJECT 不是命令失败（没发命令）：显示引导文案而非错误码
    val text = if (code == "NO_SUBJECT") stringResource(R.string.console_person_none)
    else stringResource(R.string.console_err_fmt, code)
    Text(
        text,
        fontSize = 12.sp,
        color = BT.error,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BT.surface2)
            .clickable { onDismiss() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun IdentitySection(ui: ConsoleUiState, onRefresh: () -> Unit) {
    Section(
        title = stringResource(R.string.console_sec_identity),
        action = {
            SectionAction(
                stringResource(R.string.console_refresh_info),
                enabled = ui.busy == null && ui.link == LinkState.CONNECTED,
                onClick = onRefresh,
            )
        },
    ) {
        Kv("Model", ui.sn?.devType)
        Kv("SN", ui.sn?.sn)
        Kv("MAC", ui.sn?.macHex)
        Kv("IMEI", ui.sn?.imei)
        Kv("ICCID", ui.sn?.iccid)
        Kv("FW", ui.info?.swVer)
        Kv("Modem", ui.info?.modemVer)
        Kv("SecBL", ui.info?.secBlVer)
        Kv("BP", ui.info?.bpVer)
        Kv("Func", ui.devFunc?.let { "0x" + it.toString(16).uppercase().padStart(8, '0') })
        Kv("Bond", ui.bondState?.toString())
        ui.battery?.let {
            Kv("Battery", stringResource(R.string.console_batt_fmt, it.percent, it.voltageMv, it.capacityMah))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.console_byteorder_note), fontSize = 10.sp, color = BT.onSurfaceV)
    }
}

@Composable
private fun TimeSection(ui: ConsoleUiState, onSync: () -> Unit, onCustom: () -> Unit) {
    Section(stringResource(R.string.console_sec_time)) {
        Kv(stringResource(R.string.console_time_device), ui.deviceTime?.display())
        val drift = ui.driftSec
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.console_time_drift), fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.weight(1f))
            Text(
                drift?.let { stringResource(R.string.console_drift_fmt, it) } ?: "—",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.W700,
                color = when {
                    drift == null -> BT.onSurfaceV
                    drift in -2..2 -> BT.onSurface
                    else -> BT.warning
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        val enabled = ui.busy == null && ui.link == LinkState.CONNECTED
        PrimaryButton(
            stringResource(R.string.console_sync_time),
            onClick = onSync,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        Spacer(Modifier.height(6.dp))
        // 自定义对时：可填任意时间 + 时区，测跨时区 / 过零点
        OutlineBtn(
            stringResource(R.string.console_custom_time),
            onClick = onCustom,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}

@Composable
private fun PersonSection(ui: ConsoleUiState, onEdit: () -> Unit, onWriteSubject: () -> Unit) {
    Section(stringResource(R.string.console_sec_person)) {
        val p = ui.person
        Kv("H/W", p?.let { "${it.heightCm} cm / ${it.weightKg} kg" })
        Kv("Gender", p?.gender?.toString())
        Kv("Birth", p?.let { "${it.birthYear}-${it.birthMonth}-${it.birthDay}" })
        Spacer(Modifier.height(8.dp))
        val enabled = ui.busy == null && ui.link == LinkState.CONNECTED
        // 主：编辑并写入（可修改）；次：快捷写入当前采集用户
        PrimaryButton(
            stringResource(R.string.console_write_person),
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        Spacer(Modifier.height(6.dp))
        OutlineBtn(
            stringResource(R.string.console_write_subject),
            onClick = onWriteSubject,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}

@Composable
private fun LogSection(ui: ConsoleUiState, onPull: () -> Unit, onView: () -> Unit) {
    Section(stringResource(R.string.console_sec_log)) {
        // 说明拉的是哪个日志 + 保存位置（用户要求）
        Text(stringResource(R.string.console_log_desc), fontSize = 11.sp, color = BT.onSurfaceV)
        if (ui.logRunning || ui.logChunks > 0) {
            Spacer(Modifier.height(4.dp))
            Kv("Progress", stringResource(R.string.console_log_progress, ui.logChunks, ui.logBytes))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.console_log_note), fontSize = 10.sp, color = BT.onSurfaceV)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            stringResource(R.string.console_log_pull),
            onClick = onPull,
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.busy == null && !ui.logRunning && ui.link == LinkState.CONNECTED,
        )
        // 查看日志列表（离线也可看历史日志，不需连接）
        Spacer(Modifier.height(6.dp))
        OutlineBtn(
            stringResource(R.string.console_log_view),
            onClick = onView,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DangerSection(ui: ConsoleUiState, onFind: () -> Unit, onDanger: (Int, Int) -> Unit) {
    Section(stringResource(R.string.console_sec_danger)) {
        val enabled = ui.busy == null && ui.link == LinkState.CONNECTED
        OutlineBtn(
            stringResource(if (ui.finding) R.string.console_find_stop else R.string.console_find_start),
            onClick = onFind,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            color = if (ui.finding) BT.primaryDeep else BT.onSurface,
        )
        if (ui.finding) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.console_find_hint), fontSize = 10.sp, color = BT.onSurfaceV)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineBtn(
                stringResource(R.string.console_reboot),
                onClick = { onDanger(S7.CTRL_RESET, R.string.console_reboot) },
                modifier = Modifier.weight(1f), enabled = enabled, color = BT.error,
            )
            OutlineBtn(
                stringResource(R.string.console_power_off),
                onClick = { onDanger(S7.CTRL_POWER_OFF, R.string.console_power_off) },
                modifier = Modifier.weight(1f), enabled = enabled, color = BT.error,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlineBtn(
            stringResource(R.string.console_restore),
            onClick = { onDanger(S7.CTRL_RESTORE, R.string.console_restore) },
            modifier = Modifier.fillMaxWidth(), enabled = enabled, color = BT.error,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.console_ota), fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.console_ota_deferred), fontSize = 11.sp, color = BT.onSurfaceV)
        }
    }
}

@Composable
private fun OpLogSection(vm: DeviceConsoleViewModel, onExport: () -> Unit) {
    Section(
        title = stringResource(R.string.console_sec_oplog),
        action = {
            SectionAction(
                stringResource(R.string.console_oplog_export),
                enabled = vm.opLines.isNotEmpty(),
                onClick = onExport,
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BT.surface2),
        ) {
            val zoneOffset = vm.zoneOffsetSeconds()
            LazyColumn(Modifier.fillMaxSize().padding(8.dp), reverseLayout = true) {
                items(vm.opLines.asReversed()) { line ->
                    // 本地时区渲染（对时排障要与「设备时间」栏可比对，勿用 UTC）
                    val t = epochMsToLocalParts(line.timeMs, zoneOffset).timeCompact()
                    Text(
                        "[$t] ${line.text}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = BT.onSurfaceV,
                    )
                }
            }
        }
    }
}

// ---- 基础件（工程风格）----

@Composable
private fun Section(
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BT.surface)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (title != null || action != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W800,
                    color = BT.onSurfaceV,
                    modifier = Modifier.weight(1f),
                )
                action?.invoke()
            }
            Spacer(Modifier.height(6.dp))
        }
        content()
    }
}

/** Section 标题栏里的小文字动作（刷新/导出），禁用态灰显。 */
@Composable
private fun SectionAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.W700,
        color = if (enabled) BT.primaryDeep else BT.onSurfaceV,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Kv(label: String, value: String?) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = BT.onSurfaceV, modifier = Modifier.weight(0.32f))
        Text(
            value ?: "—",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = BT.onSurface,
            modifier = Modifier.weight(0.68f),
        )
    }
}

/** 用户信息编辑对话框（可修改后写入设备）。预填设备读回值或缺省。 */
@Composable
private fun PersonEditDialog(
    initial: S7Person?,
    onDismiss: () -> Unit,
    onConfirm: (S7Person) -> Unit,
) {
    val base = initial ?: S7Person(heightCm = 170, weightKg = 65, gender = 1, birthYear = 1995, birthMonth = 1, birthDay = 1)
    var height by remember { mutableStateOf(base.heightCm.toString()) }
    var weight by remember { mutableStateOf(base.weightKg.toString()) }
    var gender by remember { mutableStateOf(base.gender) }
    var year by remember { mutableStateOf(base.birthYear.toString()) }
    var month by remember { mutableStateOf(base.birthMonth.toString()) }
    var day by remember { mutableStateOf(base.birthDay.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_person_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(height, { height = it }, stringResource(R.string.console_field_height))
                NumField(weight, { weight = it }, stringResource(R.string.console_field_weight))
                Text(stringResource(R.string.console_field_gender), fontSize = 12.sp, color = BT.onSurfaceV)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenderChip(stringResource(R.string.console_gender_male), gender == 1) { gender = 1 }
                    GenderChip(stringResource(R.string.console_gender_female), gender == 0) { gender = 0 }
                    GenderChip(stringResource(R.string.console_gender_other), gender == 2) { gender = 2 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1.4f)) { NumField(year, { year = it }, stringResource(R.string.console_field_birth_year)) }
                    Box(Modifier.weight(1f)) { NumField(month, { month = it }, stringResource(R.string.console_field_birth_month)) }
                    Box(Modifier.weight(1f)) { NumField(day, { day = it }, stringResource(R.string.console_field_birth_day)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    S7Person(
                        heightCm = height.toIntOrNull()?.coerceIn(0, 255) ?: base.heightCm,
                        weightKg = weight.toIntOrNull()?.coerceIn(0, 255) ?: base.weightKg,
                        gender = gender,
                        birthYear = year.toIntOrNull()?.coerceIn(1900, 2100) ?: base.birthYear,
                        birthMonth = month.toIntOrNull()?.coerceIn(1, 12) ?: base.birthMonth,
                        birthDay = day.toIntOrNull()?.coerceIn(1, 31) ?: base.birthDay,
                    ),
                )
            }) { Text(stringResource(R.string.console_save), color = BT.primaryDeep, fontWeight = FontWeight.W700) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_cancel)) } },
    )
}

/** 自定义对时对话框：年月日时分秒 + 时区，写入设备（测跨时区/过零点）。 */
@Composable
private fun TimeEditDialog(
    initial: S7DateTime,
    phoneNow: () -> S7DateTime,
    onDismiss: () -> Unit,
    onConfirm: (S7DateTime) -> Unit,
) {
    var year by remember { mutableStateOf(initial.year.toString()) }
    var month by remember { mutableStateOf(initial.month.toString()) }
    var day by remember { mutableStateOf(initial.day.toString()) }
    var hour by remember { mutableStateOf(initial.hour.toString()) }
    var minute by remember { mutableStateOf(initial.minute.toString()) }
    var second by remember { mutableStateOf(initial.second.toString()) }
    var tz by remember { mutableStateOf(initial.timezone.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_time_edit_title), fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1.4f)) { NumField(year, { year = it }, stringResource(R.string.console_tf_year)) }
                    Box(Modifier.weight(1f)) { NumField(month, { month = it }, stringResource(R.string.console_tf_month)) }
                    Box(Modifier.weight(1f)) { NumField(day, { day = it }, stringResource(R.string.console_tf_day)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) { NumField(hour, { hour = it }, stringResource(R.string.console_tf_hour)) }
                    Box(Modifier.weight(1f)) { NumField(minute, { minute = it }, stringResource(R.string.console_tf_min)) }
                    Box(Modifier.weight(1f)) { NumField(second, { second = it }, stringResource(R.string.console_tf_sec)) }
                }
                SignedField(tz, { tz = it }, stringResource(R.string.console_tf_tz))
                Text(stringResource(R.string.console_tz_note), fontSize = 10.sp, color = BT.onSurfaceV)
                OutlineBtn(
                    stringResource(R.string.console_fill_phone),
                    onClick = {
                        val n = phoneNow()
                        year = n.year.toString(); month = n.month.toString(); day = n.day.toString()
                        hour = n.hour.toString(); minute = n.minute.toString(); second = n.second.toString()
                        tz = n.timezone.toString()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    S7DateTime(
                        year = year.toIntOrNull()?.coerceIn(2000, 2099) ?: initial.year,
                        month = month.toIntOrNull()?.coerceIn(1, 12) ?: initial.month,
                        day = day.toIntOrNull()?.coerceIn(1, 31) ?: initial.day,
                        hour = hour.toIntOrNull()?.coerceIn(0, 23) ?: initial.hour,
                        minute = minute.toIntOrNull()?.coerceIn(0, 59) ?: initial.minute,
                        second = second.toIntOrNull()?.coerceIn(0, 59) ?: initial.second,
                        week = 1, // 由 shared 层按 y/m/d 自算覆盖
                        timezone = tz.toIntOrNull()?.coerceIn(-12, 14) ?: 0,
                    ),
                )
            }) { Text(stringResource(R.string.console_write_time), color = BT.primaryDeep, fontWeight = FontWeight.W700) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_cancel)) } },
    )
}

@Composable
private fun SignedField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '-' }.take(3)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() }.take(4)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GenderChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.W800 else FontWeight.W500,
        color = BT.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BT.primaryC else BT.surface2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
