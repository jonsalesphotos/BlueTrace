package com.example.bluetrace.ui.screen.permission

import android.bluetooth.BluetoothAdapter
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.data.android.BlueTracePermissions
import com.example.bluetrace.shared.domain.AppPreferences
import com.example.bluetrace.shared.domain.EnvironmentRepository
import com.example.bluetrace.shared.domain.RequirementId
import com.example.bluetrace.shared.domain.RequirementSeverity
import com.example.bluetrace.shared.domain.RequirementStatus
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.components.StatusPill
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.EnvironmentViewModel
import com.example.bluetrace.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** 启动A · 启动屏（一闪而过 + 静默检查）。 */
@Composable
fun SplashScreen(onGate: () -> Unit, onMain: () -> Unit) {
    val prefs = koinInject<AppPreferences>()
    val env = koinInject<EnvironmentRepository>()
    LaunchedEffect(Unit) {
        env.refresh()
        delay(700)
        if (prefs.firstLaunchCompleted.first()) onMain() else onGate()
    }
    Box(Modifier.fillMaxSize().background(BT.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(86.dp).clip(RoundedCornerShape(24.dp)).background(BT.primary), contentAlignment = Alignment.Center) {
                Text("BT", fontSize = 28.sp, fontWeight = FontWeight.W800, color = Color.White)
            }
            Text("BlueTrace", fontSize = 25.sp, fontWeight = FontWeight.W800, color = BT.onSurface)
            Text("多传感器 BLE 数据采集", fontSize = 12.sp, color = BT.onSurfaceV)
        }
    }
}

/** 启动B · 权限门控（首启请求 · 无返回 · 可暂时跳过）。 */
@Composable
fun PermissionGateScreen(
    onContinue: () -> Unit,
    vm: EnvironmentViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs = koinInject<AppPreferences>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    fun finish() {
        scope.launch { prefs.setFirstLaunchCompleted(true) }
        onContinue()
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "需要一些权限", subtitle = "首次启动 · 一次性授权更顺")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("硬性 · 采集必需")
            state.requirements.filter { it.severity == RequirementSeverity.HARD }.forEach { req ->
                PermRow(req.id, req.status) {
                    when (req.id) {
                        RequirementId.BLUETOOTH_ON -> context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        RequirementId.BLE_SCAN_CONNECT -> permLauncher.launch(BlueTracePermissions.hardScanConnect)
                        else -> {}
                    }
                }
            }
            SectionHeader("建议 · 可跳过")
            state.requirements.filter { it.severity == RequirementSeverity.SUGGESTED }.forEach { req ->
                PermRow(req.id, req.status) {
                    when (req.id) {
                        RequirementId.LOCATION -> permLauncher.launch(BlueTracePermissions.location)
                        RequirementId.NOTIFICATIONS -> if (BlueTracePermissions.notifications.isNotEmpty()) permLauncher.launch(BlueTracePermissions.notifications)
                        RequirementId.BATTERY_UNRESTRICTED -> context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        else -> {}
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("全部授权", onClick = {
                permLauncher.launch(BlueTracePermissions.hardScanConnect + BlueTracePermissions.location + BlueTracePermissions.notifications)
            })
            OutlineBtn("暂时跳过", { finish() }, Modifier.fillMaxWidth())
            // 继续（授权后）
            PrimaryButton("进入采集", { finish() })
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
                StatusPill("已满足", BT.onSuccessC, BT.successC)
            } else {
                Text(
                    when (id) { RequirementId.BLUETOOTH_ON -> "去开启"; RequirementId.BATTERY_UNRESTRICTED -> "去设置"; else -> "授权" },
                    fontSize = 13.sp, fontWeight = FontWeight.W700, color = BT.primary,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(BT.primaryC).clickable(onClick = onRequest).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun permTitle(id: RequirementId) = when (id) {
    RequirementId.BLUETOOTH_ON -> "蓝牙开关"
    RequirementId.BLE_SCAN_CONNECT -> "附近设备（扫描 / 连接）"
    RequirementId.LOCATION -> "定位（GNSS 一路）"
    RequirementId.NOTIFICATIONS -> "通知"
    RequirementId.BATTERY_UNRESTRICTED -> "后台不省电（保活）"
}

private fun permHint(id: RequirementId) = when (id) {
    RequirementId.BLUETOOTH_ON -> "系统开关 ≠ 权限"
    RequirementId.BLE_SCAN_CONNECT -> "BLUETOOTH_SCAN + CONNECT"
    RequirementId.LOCATION -> "while-in-use 即可，不申请后台定位"
    RequirementId.NOTIFICATIONS -> "前台服务常驻通知"
    RequirementId.BATTERY_UNRESTRICTED -> "系统设置 ≠ 权限"
}

/** 启动E · 蓝牙已关闭（≠ 权限）。 */
@Composable
fun BluetoothOffScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "设备连接", subtitle = "蓝牙未开启", onBack = onBack)
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.errorC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text("蓝牙已关闭。无法扫描 / 连接设备。", fontSize = 12.sp, color = BT.error, modifier = Modifier.padding(12.dp))
            }
            Text("蓝牙是系统开关（不是权限）。开启后自动回到设备列表。", fontSize = 12.sp, color = BT.onSurfaceV)
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton("开启蓝牙", onClick = { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
        }
    }
}

/** 启动F · 后台省电设置指南（保活 · 建议不阻断）。 */
@Composable
fun PowerSaveGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "后台运行设置", subtitle = "保活 · 防采集被系统杀", onBack = onBack)
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text("采集需长时间后台运行。系统省电可能杀进程致中断；请把本应用切到「不省电 / 不受限」。", fontSize = 12.sp, color = BT.onPrimaryC, modifier = Modifier.padding(12.dp))
            }
            PrimaryButton("忽略电池优化", onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
            OutlineBtn("应用详情设置", {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }, Modifier.fillMaxWidth())
            Text("各家 ROM 路径不同（小米 / 华为 / OPPO / vivo）：自启动 / 关联启动 / 最近任务锁定，需各自在系统设置开启。", fontSize = 11.5.sp, color = BT.onSurfaceV)
        }
    }
}

/** GNSS A/B · 本机 GNSS（可选一路 · while-in-use）。 */
@Composable
fun GnssScreen(
    onBack: () -> Unit,
    envVm: EnvironmentViewModel = koinViewModel(),
    settingsVm: SettingsViewModel = koinViewModel(),
) {
    val env by envVm.state.collectAsStateWithLifecycle()
    val gnssEnabled by settingsVm.gnssEnabled.collectAsStateWithLifecycle()
    val locationGranted = env.status(RequirementId.LOCATION) == RequirementStatus.GRANTED
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { envVm.refresh() }

    LaunchedEffect(Unit) { envVm.refresh() }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "本机 GNSS", subtitle = "采集会话含 gps.csv", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GNSS 数据源", fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                        Text(if (locationGranted) "已授定位（while-in-use）" else "缺定位权限", fontSize = 11.sp, color = BT.onSurfaceV)
                    }
                    Switch(checked = gnssEnabled && locationGranted, enabled = locationGranted, onCheckedChange = { settingsVm.setGnss(it) })
                }
            }
            if (!locationGranted) {
                PrimaryButton("去授权", onClick = { permLauncher.launch(BlueTracePermissions.location) })
            }
            Text("开启后本机 GNSS 作一路写入会话：落地 gps.csv + manifest，共用同一时间轴。要 GNSS 则在开始前授权，否则本次会话不含 GPS 一路。", fontSize = 12.sp, color = BT.onSurfaceV)
        }
    }
}
