package io.bluetrace.ui.screen.collect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.data.android.BlueTracePermissions
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.isDefault
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.EntryTile
import io.bluetrace.ui.components.PillTag
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.sceneLabelZh
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.CollectHomeViewModel
import io.bluetrace.viewmodel.StartOutcome
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** 采集A · 采集 Tab 主界面（设备 / 用户 / 采集场景入口 + 在线·离线采集）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectHomeScreen(
    onOpenDevice: () -> Unit,
    onOpenSubject: () -> Unit,
    onOpenScene: () -> Unit,
    onStart: () -> Unit,
    onBluetoothOff: () -> Unit,
    vm: CollectHomeViewModel = koinViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val catalog = koinInject<SceneCatalog>()
    var lowSpace by remember { mutableStateOf(false) }
    // rememberSaveable：转屏/重建后已处理过的弹层不再复活
    var showMissingSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var missingHandled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scanPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.refreshEnv()
        // 永久拒绝：系统弹窗被秒驳、用户无感知 →「去授权」不能是哑弹，标记 BLOCKED 并带去应用设置页
        val blocked = io.bluetrace.ui.screen.permission.markBlockedPermissions(context, result) { vm.markBlocked(it) }
        if (blocked) {
            android.widget.Toast.makeText(context, context.getString(R.string.perm_blocked_toast), android.widget.Toast.LENGTH_LONG).show()
            io.bluetrace.ui.screen.permission.openAppSettings(context)
        }
    }

    // 进入时取一次低空间提示（一次性，§5.2）
    LaunchedEffect(Unit) { lowSpace = vm.isLowSpace() }
    // 回前台复检蓝牙开关 + 权限（设备入口据此分流到 启动E）——含小米软关闭等广播丢失场景，
    // 按当前真实 adapter 状态校正，不只信任广播（ON_RESUME 进入时也会触发一次）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshEnv() }
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
                value = ui.currentSubject?.let { if (it.isDefault()) stringResource(R.string.subject_default) else it.alias }
                    ?: stringResource(R.string.collect_entry_user_empty),
                valueColor = if (ui.currentSubject != null) BT.onSurface else BT.onSurfaceV,
                showChevron = true,
                // Default 伪用户无体征 chips；真人用户展示性别/生日/身高/体重。
                belowContent = ui.currentSubject?.takeIf { !it.isDefault() }?.let { s ->
                    {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            io.bluetrace.ui.screen.subject.subjectBioBadges(s).forEach { b -> PillTag(b, BT.onSurfaceV, BT.surface2) }
                        }
                    }
                },
            )
            SceneTile(
                label = sceneLabelZh(catalog, ui.scene),
                onClick = onOpenScene,
            )
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
            // 离线采集：小入口（不占主位、不常按）；读 DUT flash 已存数据导入 APP，待协议 → 占位 Toast。
            TextButton(
                onClick = { android.widget.Toast.makeText(context, context.getString(R.string.collect_offline_todo), android.widget.Toast.LENGTH_SHORT).show() },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, tint = BT.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.collect_offline), fontSize = 12.5.sp, fontWeight = FontWeight.W700, color = BT.primary)
            }
            // 在线采集：沉底主按钮（最顺手）= DUT 实时 生成→打包→发送。
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

/**
 * 采集场景 tile：屏内只显示场景值（主·子场景中文，如「佩戴 · 佩戴中」，由 scenes.json 词表给出）。
 * token 恒英文用于文件名/json/manifest；整行点击进场景选择页（页 A）。
 */
@Composable
private fun SceneTile(label: String, onClick: () -> Unit) {
    EntryTile(
        icon = Icons.Filled.GraphicEq,
        iconColor = BT.success,
        iconBg = BT.successC,
        title = stringResource(R.string.collect_entry_scene_title),
        value = label,
        valueColor = BT.onSurface,
        showChevron = true,
        onClick = onClick,
    )
}
