package io.bluetrace.ui.screen.permission

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.data.android.BlueTracePermissions
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementSeverity
import io.bluetrace.shared.domain.RequirementStatus
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.EnvironmentViewModel
import io.bluetrace.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 启动B · 权限门控（首启请求 · 无返回 · 仅「全部授权」+「暂时跳过」，对齐 v4 原型）。
 * 启动屏由 Android12 SplashScreen 承载（见 MainActivity / AppStartup），不再有 Compose Splash。
 */
@Composable
fun PermissionGateScreen(
    onContinue: () -> Unit,
    onPowerSaveGuide: () -> Unit,
    vm: EnvironmentViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs = koinInject<AppPreferences>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    fun finish() {
        scope.launch { prefs.setFirstLaunchCompleted(true) }
        onContinue()
    }

    // 逐项「授权」：请求后检测永久拒绝 → 标 BLOCKED，复检
    val itemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        markBlockedFromResult(activity, result, vm); vm.refresh()
    }
    // 「全部授权」：逐项拉起系统弹窗 → 完成进采集 Tab（原型语义）
    val grantAllLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        markBlockedFromResult(activity, result, vm); vm.refresh(); finish()
    }

    fun requestFor(id: RequirementId) {
        // 永久拒绝（BLOCKED）→ 引导去应用设置页（系统不再弹，§5.2 启动D）
        if (state.status(id) == RequirementStatus.BLOCKED) {
            openAppSettings(context); return
        }
        when (id) {
            RequirementId.BLUETOOTH_ON -> context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            RequirementId.BLE_SCAN_CONNECT -> itemLauncher.launch(BlueTracePermissions.hardScanConnect)
            RequirementId.LOCATION -> itemLauncher.launch(BlueTracePermissions.location)
            RequirementId.NOTIFICATIONS -> if (BlueTracePermissions.notifications.isNotEmpty()) itemLauncher.launch(BlueTracePermissions.notifications)
            // 后台不省电 → 启动F 后台省电指南（in-app 引导，§5.2 / v2-D）
            RequirementId.BATTERY_UNRESTRICTED -> onPowerSaveGuide()
        }
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.gate_title), subtitle = stringResource(R.string.gate_subtitle))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(stringResource(R.string.gate_sec_hard))
            state.requirements.filter { it.severity == RequirementSeverity.HARD }.forEach { req ->
                PermRow(req.id, req.status) { requestFor(req.id) }
            }
            SectionHeader(stringResource(R.string.gate_sec_suggested))
            state.requirements.filter { it.severity == RequirementSeverity.SUGGESTED }.forEach { req ->
                PermRow(req.id, req.status) { requestFor(req.id) }
            }
        }
        // 底部：全部授权（主） + 暂时跳过（文本）—— 与原型一致，无第三个按钮
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton(stringResource(R.string.gate_grant_all), onClick = {
                grantAllLauncher.launch(BlueTracePermissions.hardScanConnect + BlueTracePermissions.location + BlueTracePermissions.notifications)
            })
            TextButton(onClick = { finish() }) {
                Text(stringResource(R.string.action_skip_now), color = BT.onSurfaceV, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PermRow(id: RequirementId, status: RequirementStatus, onRequest: () -> Unit) {
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(permTitle(id), fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                Text(permHint(id), fontSize = 11.sp, color = BT.onSurfaceV)
            }
            if (status == RequirementStatus.GRANTED) {
                StatusPill(stringResource(R.string.env_status_granted), BT.onSuccessC, BT.successC)
            } else {
                val actionLabel = when {
                    status == RequirementStatus.BLOCKED -> stringResource(R.string.perm_act_settings) // 永久拒绝 → 去设置
                    id == RequirementId.BLUETOOTH_ON -> stringResource(R.string.perm_act_enable)
                    id == RequirementId.BATTERY_UNRESTRICTED -> stringResource(R.string.perm_act_settings)
                    else -> stringResource(R.string.perm_act_grant)
                }
                Text(
                    actionLabel,
                    fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.primary,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(BT.primaryC).clickable(onClick = onRequest).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun permTitle(id: RequirementId): String = stringResource(
    when (id) {
        RequirementId.BLUETOOTH_ON -> R.string.perm_bluetooth_title
        RequirementId.BLE_SCAN_CONNECT -> R.string.perm_scan_title
        RequirementId.LOCATION -> R.string.perm_location_title
        RequirementId.NOTIFICATIONS -> R.string.perm_notification_title
        RequirementId.BATTERY_UNRESTRICTED -> R.string.perm_battery_title
    },
)

@Composable
private fun permHint(id: RequirementId): String = stringResource(
    when (id) {
        RequirementId.BLUETOOTH_ON -> R.string.perm_bluetooth_sub
        RequirementId.BLE_SCAN_CONNECT -> R.string.perm_scan_sub
        RequirementId.LOCATION -> R.string.perm_location_sub
        RequirementId.NOTIFICATIONS -> R.string.perm_notification_sub
        RequirementId.BATTERY_UNRESTRICTED -> R.string.perm_battery_sub
    },
)

/** 启动E · 蓝牙已关闭（≠ 权限）。 */
@Composable
fun BluetoothOffScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.device_title), subtitle = stringResource(R.string.bt_off_subtitle), onBack = onBack)
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.errorC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bt_off_banner), fontSize = 12.sp, color = BT.error, modifier = Modifier.padding(12.dp))
            }
            Text(stringResource(R.string.bt_off_empty_title), fontSize = 15.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
            Text(stringResource(R.string.bt_off_empty_sub), fontSize = 12.sp, color = BT.onSurfaceV)
        }
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            PrimaryButton(stringResource(R.string.bt_off_enable), onClick = { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
        }
    }
}

/** 启动F · 后台省电设置指南（保活 · 建议不阻断）。 */
@Composable
fun PowerSaveGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.power_title), subtitle = stringResource(R.string.power_subtitle), onBack = onBack)
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.power_banner), fontSize = 12.sp, color = BT.onPrimaryC, modifier = Modifier.padding(12.dp))
            }
            PrimaryButton(stringResource(R.string.power_ignore_battery), onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            OutlineBtn(stringResource(R.string.power_app_details), {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.power_rom_hint), fontSize = 11.5.sp, color = BT.onSurfaceV)
        }
    }
}

// ===== 权限永久拒绝检测 + 应用设置（§5.2 启动D）=====

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 请求结果里被拒且系统不再弹（!shouldShowRationale）→ 标记该权限 BLOCKED。 */
private fun markBlockedFromResult(activity: Activity?, result: Map<String, Boolean>, vm: EnvironmentViewModel) {
    if (activity == null) return
    result.forEach { (perm, granted) ->
        if (!granted && !activity.shouldShowRequestPermissionRationale(perm)) {
            reqIdForPermission(perm)?.let { vm.markBlocked(it) }
        }
    }
}

private fun reqIdForPermission(perm: String): RequirementId? = when (perm) {
    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT -> RequirementId.BLE_SCAN_CONNECT
    Manifest.permission.ACCESS_FINE_LOCATION -> RequirementId.LOCATION
    Manifest.permission.POST_NOTIFICATIONS -> RequirementId.NOTIFICATIONS
    else -> null
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 启动C · 后续启动缺权限弹出（ModalSheet over 采集 Tab，§5.1）。可「去授权」/「跳过」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingPermsSheet(
    sheetState: SheetState,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.gatec_title), fontSize = 17.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
            Text(stringResource(R.string.gatec_body), fontSize = 12.sp, color = BT.onSurfaceV)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(stringResource(R.string.gatec_skip), onSkip, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.gatec_grant), onGrant, Modifier.weight(1f))
            }
        }
    }
}
