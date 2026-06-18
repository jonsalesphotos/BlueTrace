package com.example.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.RequirementId
import com.example.bluetrace.shared.domain.RequirementSeverity
import com.example.bluetrace.shared.domain.RequirementStatus
import com.example.bluetrace.shared.session.LogLevel
import com.example.bluetrace.ui.components.BtTopBar
import com.example.bluetrace.ui.components.ListTileRow
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.components.SectionHeader
import com.example.bluetrace.ui.components.StatusPill
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.viewmodel.EnvironmentViewModel
import com.example.bluetrace.viewmodel.SettingsViewModel
import com.example.bluetrace.viewmodel.StorageBreakdown
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsHomeScreen(
    onEnv: () -> Unit, onGnss: () -> Unit, onExportLoc: () -> Unit, onStorage: () -> Unit,
    onLog: () -> Unit, onDeviceMaint: () -> Unit, onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "设置", subtitle = "BlueTrace · v1.0.0")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            item { SectionHeader("环境与权限") }
            item { SettingsNavRow(Icons.Filled.Shield, BT.primary, BT.primaryC, "环境与权限检查", "蓝牙开关 / 权限 / 后台保活", onEnv) }
            item { SettingsNavRow(Icons.Filled.LocationOn, BT.tertiary, BT.tertiaryC, "本机 GNSS", "可选一路 · while-in-use 定位", onGnss) }
            item { SectionHeader("数据") }
            item { SettingsNavRow(Icons.Filled.FolderOpen, BT.success, BT.successC, "导出位置", "Download/BlueTrace/", onExportLoc) }
            item { SettingsNavRow(Icons.Filled.Storage, BT.warning, BT.warningC, "存储占用", "原始 HEX + 解码 CSV", onStorage) }
            item { SectionHeader("诊断与维护") }
            item { SettingsNavRow(Icons.Filled.Article, BT.primary, BT.primaryC, "应用日志", "运行错误/事件 · 可导出/清空", onLog) }
            item { SettingsNavRow(Icons.Filled.Memory, BT.onSurfaceV, BT.surface2, "设备维护（DUT）", "对时/用户信息/固件日志/OTA · 后期", onDeviceMaint) }
            item { SectionHeader("关于") }
            item { SettingsNavRow(Icons.Filled.Info, BT.tertiary, BT.tertiaryC, "关于 BlueTrace", "v1.0.0 · 多传感器 BLE 数据采集", onAbout) }
            item {
                // 二期项灰显禁用（服务器 / 上传 / 远程下发）
                ListTileRow(Icons.Filled.Info, BT.onSurfaceV, BT.surface2, "服务器同步 / 远程下发", "二期", enabled = false)
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
        BtTopBar(title = "环境与权限检查", subtitle = "一站式权限与环境状态", onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            items(state.requirements, key = { it.id.name }) { req ->
                Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reqTitle(req.id), fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                            Text(if (req.severity == RequirementSeverity.HARD) "硬性 · 采集必需" else "建议 · 可跳过", fontSize = 11.sp, color = BT.onSurfaceV)
                        }
                        StatusBadge(req.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RequirementStatus) {
    when (status) {
        RequirementStatus.GRANTED -> StatusPill("已满足", BT.onSuccessC, BT.successC)
        RequirementStatus.MISSING -> StatusPill("缺失", BT.onWarningC, BT.warningC)
        RequirementStatus.BLOCKED -> StatusPill("被拒", BT.error, BT.errorC)
        RequirementStatus.OFF -> StatusPill("已关闭", BT.onWarningC, BT.warningC)
    }
}

private fun reqTitle(id: RequirementId): String = when (id) {
    RequirementId.BLUETOOTH_ON -> "蓝牙开关（系统开关 ≠ 权限）"
    RequirementId.BLE_SCAN_CONNECT -> "附近设备（扫描 / 连接）"
    RequirementId.LOCATION -> "定位（GNSS 一路 · while-in-use）"
    RequirementId.NOTIFICATIONS -> "通知（前台采集常驻）"
    RequirementId.BATTERY_UNRESTRICTED -> "后台不省电（保活）"
}

/** 设置B · 导出位置。 */
@Composable
fun ExportLocationScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "导出位置", subtitle = "会话文件夹导出路径", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = BT.surface2, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
                Text("Download/BlueTrace/", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = BT.onSurface, modifier = Modifier.padding(14.dp))
            }
            Text("所有会话文件夹经 MediaStore 导出到公共下载目录（无需存储权限）。应用日志导出到 Download/BlueTrace/logs/。", fontSize = 12.sp, color = BT.onSurfaceV)
        }
    }
}

/** 设置C · 存储占用（StorageBreakdown）。 */
@Composable
fun StorageScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val storage by vm.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "存储占用", subtitle = "${"%.2f".format(storage.totalBytes / 1024.0 / 1024.0)} MB · ${storage.sessionCount} 会话 · ${storage.fileCount} 文件", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageBar(storage)
            Legend("原始 HEX 日志", BT.warning, storage.rawBytes)
            Legend("解码 CSV", BT.success, storage.csvBytes)
            Legend("组合包 CSV", BT.primary, storage.comboBytes)
            Legend("GNSS CSV", BT.tertiary, storage.gnssBytes)
            Legend("manifest", BT.outline, storage.manifestBytes)
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
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "应用日志", subtitle = "运行错误/事件 · 非开发调试日志", onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            items(entries.asReversed()) { e ->
                Text(
                    "${e.epochMs} [${e.tag}] ${e.message}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = when (e.level) { LogLevel.ERROR -> BT.error; LogLevel.WARN -> BT.warning; LogLevel.INFO -> BT.onSurfaceV },
                )
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineBtn("清除日志", { vm.clearLog() }, Modifier.weight(1f))
            PrimaryButton("导出日志", { vm.exportLog() }, Modifier.weight(1f))
        }
    }
}

/** 设置F · 设备维护（DUT · 后期占位）。 */
@Composable
fun DeviceMaintenanceScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "设备维护（DUT）", subtitle = "对时 / 用户信息 / 固件日志 / OTA", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("设备对时（同步 unix 时间到 DUT）", "写设备用户信息", "读取固件日志", "OTA 固件升级").forEach {
                ListTileRow(Icons.Filled.Memory, BT.onSurfaceV, BT.surface2, it, "后期功能 · 需连接 DUT", enabled = false)
            }
            Text("入口预留，具体内容后期（走协议 Command·Ack；OTA 属二期）。", fontSize = 12.sp, color = BT.onSurfaceV)
        }
    }
}

/** 设置D · 关于。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = "关于 BlueTrace", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(86.dp).clip(RoundedCornerShape(24.dp)).background(BT.primary), contentAlignment = Alignment.Center) {
                Text("BT", fontSize = 28.sp, fontWeight = FontWeight.W800, color = Color.White)
            }
            Text("BlueTrace", fontSize = 25.sp, fontWeight = FontWeight.W800, color = BT.onSurface)
            Text("v1.0.0", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = BT.onSurfaceV)
            Spacer(Modifier.height(6.dp))
            Text("多传感器 BLE 数据采集系统（KMP · Android 优先）。原始 HEX 行日志为唯一权威源，每会话一文件夹（D-6）。", fontSize = 12.sp, color = BT.onSurfaceV)
            Text("Powered by Jetpack Compose · Material 3 · Koin · okio", fontSize = 11.sp, color = BT.onSurfaceV)
            Text("© 2026 BlueTrace", fontSize = 11.sp, color = BT.onSurfaceV)
        }
    }
}
