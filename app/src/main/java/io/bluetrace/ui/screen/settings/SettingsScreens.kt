package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.shared.domain.AppLanguage
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementSeverity
import io.bluetrace.shared.domain.RequirementStatus
import io.bluetrace.shared.domain.ThemeMode
import io.bluetrace.shared.session.LogLevel
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.components.ListTileRow
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.components.SectionHeader
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.screen.environmentStatusLabelRes
import io.bluetrace.ui.theme.BT
import io.bluetrace.viewmodel.EnvironmentViewModel
import io.bluetrace.viewmodel.SettingsViewModel
import io.bluetrace.viewmodel.StorageBreakdown
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingsHomeScreen(
    onEnv: () -> Unit, onExportLoc: () -> Unit, onStorage: () -> Unit,
    onLog: () -> Unit, onDeviceMaint: () -> Unit, onAbout: () -> Unit,
    onAppearance: () -> Unit, onLanguage: () -> Unit,
    onOtaTest: () -> Unit = {},
) {
    // 通用分区两行的副标 = 当前值（主题模式 / 语言名），随偏好实时更新；非说明性副标（red-line #1）。
    val prefs = koinInject<AppPreferences>()
    val theme by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val language by prefs.language.collectAsState(initial = AppLanguage.ZH)
    val themeLabel = stringResource(
        when (theme) {
            ThemeMode.SYSTEM -> R.string.appearance_system
            ThemeMode.LIGHT -> R.string.appearance_light
            ThemeMode.DARK -> R.string.appearance_dark
        },
    )
    val languageLabel = stringResource(if (language == AppLanguage.EN) R.string.lang_en else R.string.lang_zh)

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.tab_settings), subtitle = stringResource(R.string.settings_subtitle))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            item { SectionHeader(stringResource(R.string.settings_sec_env)) }
            item { SettingsNavRow(Icons.Filled.Shield, BT.primary, BT.primaryC, stringResource(R.string.settings_env_check), stringResource(R.string.settings_env_check_sub), onEnv) }
            item { SectionHeader(stringResource(R.string.settings_sec_data)) }
            item { SettingsNavRow(Icons.Filled.FolderOpen, BT.success, BT.successC, stringResource(R.string.settings_export_loc), stringResource(R.string.settings_export_loc_sub), onExportLoc) }
            item { SettingsNavRow(Icons.Filled.Storage, BT.warning, BT.warningC, stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_sub), onStorage) }
            item { SectionHeader(stringResource(R.string.settings_sec_general)) }
            item { SettingsNavRow(Icons.Filled.DarkMode, BT.primary, BT.primaryC, stringResource(R.string.settings_appearance), themeLabel, onAppearance) }
            item { SettingsNavRow(Icons.Filled.Language, BT.tertiary, BT.tertiaryC, stringResource(R.string.settings_language), languageLabel, onLanguage) }
            item { SectionHeader(stringResource(R.string.settings_sec_diag)) }
            item { SettingsNavRow(Icons.Filled.Article, BT.primary, BT.primaryC, stringResource(R.string.settings_log), stringResource(R.string.settings_log_sub), onLog) }
            item { SettingsNavRow(Icons.Filled.Memory, BT.onSurfaceV, BT.surface2, stringResource(R.string.settings_device_maint), stringResource(R.string.settings_device_maint_sub), onDeviceMaint) }
            if (io.bluetrace.BuildConfig.DEBUG) {
                // 仅 DEBUG：OTA 固件（选烧录包→连接设备→刷入/循环），与设备维护同级
                item { SettingsNavRow(Icons.Filled.Memory, BT.primary, BT.primaryC, "OTA 固件", "刷入烧录包 · DEBUG", onOtaTest) }
            }
            if (io.bluetrace.BuildConfig.DEBUG) {
                item {
                    // 仅 DEBUG：Mock/真实 BLE 后端切换（重启生效）。无设备演示/UI 回归用 Mock；默认真实 GATT。
                    val ctx = LocalContext.current
                    var useMock by remember { mutableStateOf(io.bluetrace.data.android.BleBackendSwitch.useMock(ctx)) }
                    fun toggle(on: Boolean) {
                        useMock = on
                        io.bluetrace.data.android.BleBackendSwitch.setUseMock(ctx, on)
                    }
                    ListTileRow(
                        Icons.Filled.Memory, BT.warning, BT.warningC,
                        stringResource(R.string.settings_mock_ble),
                        stringResource(R.string.settings_mock_ble_sub),
                        onClick = { toggle(!useMock) },
                        trailing = { Switch(checked = useMock, onCheckedChange = ::toggle) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_sec_about)) }
            item { SettingsNavRow(Icons.Filled.Info, BT.tertiary, BT.tertiaryC, stringResource(R.string.settings_about), stringResource(R.string.settings_about_sub), onAbout) }
            item {
                // 二期项灰显禁用（服务器 / 上传 / 远程下发）。原型未画此行，保留为既有占位（提交说明列明）。
                ListTileRow(Icons.Filled.Info, BT.onSurfaceV, BT.surface2, stringResource(R.string.settings_phase2), stringResource(R.string.settings_phase2_sub), enabled = false)
            }
        }
    }
}

@Composable
private fun SettingsNavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, bg: Color, title: String, subtitle: String, onClick: () -> Unit) {
    ListTileRow(icon, color, bg, title, subtitle, onClick = onClick, trailing = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = BT.outline)
    })
}

/** 设置A · 环境与权限检查详情。 */
@Composable
fun EnvCheckScreen(onBack: () -> Unit, vm: EnvironmentViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.env_title), subtitle = stringResource(R.string.env_subtitle), onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            items(state.requirements, key = { it.id.name }) { req ->
                Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reqTitle(req.id), fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                            Text(if (req.severity == RequirementSeverity.HARD) stringResource(R.string.env_hard) else stringResource(R.string.env_suggested), fontSize = 11.sp, color = BT.onSurfaceV)
                        }
                        StatusBadge(req.id, req.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(id: RequirementId, status: RequirementStatus) {
    when (status) {
        RequirementStatus.GRANTED -> StatusPill(stringResource(environmentStatusLabelRes(id, status)), BT.onSuccessC, BT.successC)
        RequirementStatus.MISSING -> StatusPill(stringResource(R.string.env_status_missing), BT.onWarningC, BT.warningC)
        RequirementStatus.BLOCKED -> StatusPill(stringResource(R.string.env_status_blocked), BT.error, BT.errorC)
        RequirementStatus.OFF -> StatusPill(stringResource(R.string.env_status_off), BT.onWarningC, BT.warningC)
    }
}

@Composable
private fun reqTitle(id: RequirementId): String = stringResource(
    when (id) {
        RequirementId.BLUETOOTH_ON -> R.string.env_req_bluetooth
        RequirementId.BLE_SCAN_CONNECT -> R.string.env_req_scan
        RequirementId.LOCATION -> R.string.env_req_location
        RequirementId.NOTIFICATIONS -> R.string.env_req_notification
        RequirementId.BATTERY_UNRESTRICTED -> R.string.env_req_battery
    },
)

/** 设置B · 导出位置。 */
@Composable
fun ExportLocationScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.export_loc_title), subtitle = stringResource(R.string.export_loc_subtitle), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.surface2, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_loc_path), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = BT.onSurface, modifier = Modifier.padding(14.dp))
            }
            Text(stringResource(R.string.export_loc_note), fontSize = 12.sp, color = BT.onSurfaceV)
        }
    }
}

/** 设置C · 存储占用（StorageBreakdown）。 */
@Composable
fun StorageScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val storage by vm.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.storage_title), subtitle = stringResource(R.string.storage_subtitle, "%.2f".format(storage.totalBytes / 1024.0 / 1024.0), storage.sessionCount, storage.fileCount), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageBar(storage)
            Legend(stringResource(R.string.storage_raw), BT.warning, storage.rawBytes)
            Legend(stringResource(R.string.storage_csv), BT.success, storage.csvBytes)
            Legend(stringResource(R.string.storage_combo), BT.primary, storage.comboBytes)
            Legend(stringResource(R.string.storage_gnss), BT.tertiary, storage.gnssBytes)
            Legend(stringResource(R.string.storage_manifest), BT.outline, storage.manifestBytes)
        }
    }
}

@Composable
private fun UsageBar(s: StorageBreakdown) {
    val total = s.totalBytes.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(999.dp)).background(BT.surface2)) {
        Seg(s.rawBytes, total, BT.warning)
        Seg(s.csvBytes, total, BT.success)
        Seg(s.comboBytes, total, BT.primary)
        Seg(s.gnssBytes, total, BT.tertiary)
        Seg(s.manifestBytes, total, BT.outline)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Seg(bytes: Long, total: Long, color: Color) {
    if (bytes <= 0) return
    Box(Modifier.weight(bytes.toFloat() / total).height(12.dp).background(color))
}

@Composable
private fun Legend(label: String, color: Color, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, fontSize = 12.sp, color = BT.onSurface, modifier = Modifier.weight(1f))
        Text("${"%.2f".format(bytes / 1024.0 / 1024.0)} MB", fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace)
    }
}

/** 设置E · 应用日志（运行错误/事件，可导出/清空）。 */
@Composable
fun AppLogScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val entries by vm.logEntries.collectAsStateWithLifecycle()
    // 导出/清空结果反馈：VM 的 toast 流此前没有任何消费方——点了没反应
    val toastMsg by vm.toast.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeToast()
        }
    }
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.log_title), subtitle = stringResource(R.string.log_subtitle), onBack = onBack)
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                io.bluetrace.ui.components.EmptyState(
                    icon = Icons.Filled.Article,
                    title = stringResource(R.string.log_empty_title),
                    subtitle = stringResource(R.string.log_empty_sub),
                    modifier = Modifier.padding(top = 40.dp).align(Alignment.TopCenter),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
                items(entries.asReversed()) { e ->
                    Text(
                        "${logClock(e.epochMs)} [${e.tag}] ${e.message}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = when (e.level) { LogLevel.ERROR -> BT.error; LogLevel.WARN -> BT.warning; LogLevel.INFO -> BT.onSurfaceV },
                    )
                }
            }
        }
        Row(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineBtn(stringResource(R.string.log_clear), { vm.clearLog() }, Modifier.weight(1f), enabled = entries.isNotEmpty())
            PrimaryButton(stringResource(R.string.log_export), { vm.exportLog() }, Modifier.weight(1f), enabled = entries.isNotEmpty())
        }
    }
}

/** 日志行时间：裸 epochMs（13 位数字）→ 本地时刻 HH:mm:ss.SSS。 */
private fun logClock(epochMs: Long): String {
    val t = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d:%02d.%03d".format(t.hour, t.minute, t.second, epochMs % 1000)
}

// 设置F · 设备维护（DUT）已升级为 S7 控制台 → DeviceConsoleScreen.kt（原灰显占位删除）。

/** 设置D · 关于。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.about_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(86.dp).clip(RoundedCornerShape(24.dp)).background(BT.primary), contentAlignment = Alignment.Center) {
                Text("BT", fontSize = 28.sp, fontWeight = FontWeight.W800, color = Color.White)
            }
            Text(stringResource(R.string.app_name), fontSize = 25.sp, fontWeight = FontWeight.W800, color = BT.onSurface)
            Text(stringResource(R.string.about_version), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.about_desc), fontSize = 12.sp, color = BT.onSurfaceV)
            Text(stringResource(R.string.about_credit), fontSize = 11.sp, color = BT.onSurfaceV)
            Text(stringResource(R.string.about_copyright), fontSize = 11.sp, color = BT.onSurfaceV)
        }
    }
}
