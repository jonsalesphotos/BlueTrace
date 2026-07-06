package io.bluetrace.ui.screen.run

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bluetrace.R
import io.bluetrace.data.android.BlueTracePermissions
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.session.RunLogLine
import io.bluetrace.shared.session.RunDeviceState
import io.bluetrace.shared.session.RunStatus
import io.bluetrace.shared.util.formatDurationHms
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.StatusPill
import io.bluetrace.ui.theme.BT
import io.bluetrace.ui.theme.sensorColor
import io.bluetrace.viewmodel.RunViewModel
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.ui.sceneLabelZh
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel

/** 运行A/B/C/D · 采集运行（硬锁定 + 实时流 + 标签 + 暂停 + 长按 2 秒结束）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionRunScreen(
    onFinished: () -> Unit,
    onHardLockHint: () -> Unit,
    onExitGhost: () -> Unit = {},
    vm: RunViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    // 幽灵运行页防护：进程被杀后导航栈被 rememberSaveable 原样恢复到运行页，但 controller 是全新 READY
    // （AppStartup 只收尾数据、不清导航栈）。此时返回被硬锁、长按结束也无会话可停 → 用户被困。
    // 正常进入时 start() 已同步置 COLLECTING，不会误判。
    val ghost = state.status == RunStatus.READY && finished == null
    LaunchedEffect(ghost) { if (ghost) onExitGhost() }
    if (ghost) return

    // 硬锁定 + 预测返回（§5.4 / D-V4-17）：拦截返回手势，跟随预测进度但绝不退出，
    // 手势提交时仅提示"长按结束退出"；取消则无操作。
    PredictiveBackHandler(enabled = finished == null) { progress ->
        try {
            progress.collect { /* 跟随预测返回手势进度（硬锁定，不实际退出） */ }
            onHardLockHint() // 手势提交 → 提示，不退出
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // 取消返回手势 → 无操作
        }
    }

    // 结束（长按 / 存储满）→ finished 置非空 → 跳结束摘要。
    LaunchedEffect(finished) { if (finished != null) onFinished() }

    val config = vm.activeConfig
    val scope = rememberCoroutineScope()
    // rememberSaveable：转屏/进程重建后不再重复自动弹类型抽屉、不丢未提交的标签文本
    var showTypeSheet by rememberSaveable { mutableStateOf(true) } // 进入自动弹（D-V4-12）
    var selectedTypes by remember { mutableStateOf(config?.enabledTypes ?: CollectType.defaults) }
    var labelText by rememberSaveable { mutableStateOf("") }
    var gnssOn by remember { mutableStateOf(config?.gnssEnabled ?: false) }
    val context = LocalContext.current
    // 运行C 勾选 GNSS 但缺定位权限 → 按需请求；授予则起 GNSS 一路，拒绝则本次不含 GPS（不阻断，§5.2）
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it } || locationGranted(context)) {
            vm.setGnss(true); gnssOn = true
        } else {
            Toast.makeText(context, context.getString(R.string.gnss_denied_toast), Toast.LENGTH_LONG).show()
            gnssOn = false
        }
    }

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏（edge-to-edge：让出状态栏安全区）
            Row(Modifier.fillMaxWidth().background(BT.surface).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.run_title), fontSize = 19.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                    val catalog = koinInject<SceneCatalog>()
                    val subjectAlias = config?.subject?.alias ?: "—"
                    val sceneLabel = config?.scene?.let { sceneLabelZh(catalog, it) } ?: "—"
                    Text("$subjectAlias · $sceneLabel · ${formatDurationHms(state.elapsedMs)}", fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace)
                }
                StatusPill(stringResource(R.string.status_collecting), BT.onSuccessC, BT.successC)
                IconButton(onClick = { showTypeSheet = true }) { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.run_collect_type_title), tint = BT.onSurfaceV) }
            }

            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 设备卡（含 HR / 内联重连中）
                state.devices.forEach { DeviceCard(it) }

                // 采集类型 chips（采集中不隐藏，可重开）
                Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth().clickable { showTypeSheet = true }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = BT.onSurfaceV, modifier = Modifier.size(18.dp))
                        selectedTypes.sortedBy { it.ordinal }.forEach { t ->
                            Surface(color = sensorColor(t.id).copy(alpha = 0.16f), shape = RoundedCornerShape(999.dp)) {
                                Text(t.id, fontSize = 11.sp, fontWeight = FontWeight.W600, color = sensorColor(t.id), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                        if (gnssOn) {
                            Surface(color = BT.tertiary.copy(alpha = 0.16f), shape = RoundedCornerShape(999.dp)) {
                                Text("gps", fontSize = 11.sp, fontWeight = FontWeight.W600, color = BT.tertiary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }

                // 实时数据流（锚底无滚动条，§5.6）
                DataStreamWindow(state.datasCount, state.elapsedMs, vm.logLines, state.displayPaused, Modifier.weight(1f))

                // 标签区（Pin + Start/Stop，§5.5）
                LabelRow(
                    text = labelText,
                    onTextChange = { labelText = it },
                    intervalOpen = state.labelIntervalOpen,
                    onPin = { vm.pin(labelText.ifBlank { "pin" }); labelText = "" },
                    onToggleInterval = { vm.toggleIntervalLabel(labelText.ifBlank { "interval" }) },
                )
            }

            // 底栏：暂停 + 长按结束
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(
                    text = if (state.displayPaused) stringResource(R.string.run_resume) else stringResource(R.string.run_pause),
                    onClick = { vm.setPaused(!state.displayPaused) },
                )
                LongPressEndButton(onEnd = { scope.launch { vm.stop() } }, modifier = Modifier.weight(1f))
            }
        }

        // 演示钩子仅 DEBUG 构建可见（v2 清理项）：注入断联 / 模拟存储满。
        // 正式构建走真实异常：蓝牙关广播(§5.4) + 存储写满预检(§5.2)。
        if (io.bluetrace.BuildConfig.DEBUG) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DemoChip(stringResource(R.string.run_demo_disconnect)) { vm.injectDisconnect() }
                DemoChip(stringResource(R.string.run_demo_storage_full)) { vm.simulateStorageFull() }
            }
        }
    }

    if (showTypeSheet) {
        CollectTypeSheet(
            selected = selectedTypes,
            gnssOn = gnssOn,
            sheetState = rememberModalBottomSheetState(),
            onDismiss = { showTypeSheet = false },
            onConfirm = { sel, gnss ->
                selectedTypes = sel
                vm.setEnabledTypes(sel)
                if (gnss && !gnssOn) {
                    if (locationGranted(context)) { vm.setGnss(true); gnssOn = true }
                    else locationLauncher.launch(BlueTracePermissions.location)
                } else if (!gnss && gnssOn) {
                    vm.setGnss(false); gnssOn = false
                }
                showTypeSheet = false
            },
        )
    }
}

/** 当前是否已授予 FINE 定位（运行C 勾选 GNSS 时按需请求的前置判断）。 */
private fun locationGranted(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun DemoChip(text: String, onClick: () -> Unit) {
    Surface(color = BT.surface, shape = RoundedCornerShape(999.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BT.outlineV), modifier = Modifier.clickable { onClick() }) {
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.W600, color = BT.onSurfaceV, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun DeviceCard(device: RunDeviceState) {
    val reconnecting = device.link == LinkState.RECONNECTING
    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val isRef = device.kind == DeviceKind.REFERENCE
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(if (isRef) BT.tertiaryC else BT.successC), contentAlignment = Alignment.Center) {
                Icon(if (isRef) Icons.Filled.Favorite else Icons.Filled.GraphicEq, contentDescription = null, tint = if (isRef) BT.tertiary else BT.success, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                if (reconnecting) {
                    Text(stringResource(R.string.run_device_reconnecting), fontSize = 11.sp, color = BT.warning)
                } else {
                    val sensors = if (device.activeSensors.isEmpty()) {
                        stringResource(R.string.run_device_online)
                    } else {
                        stringResource(R.string.run_device_online_with, device.activeSensors.joinToString(" · "))
                    }
                    Text(sensors, fontSize = 11.sp, color = BT.success)
                }
            }
            device.hr?.let { hr ->
                Text("♥ $hr", fontSize = 15.sp, fontWeight = FontWeight.W700, color = BT.error, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DataStreamWindow(datas: Long, elapsedMs: Long, lines: List<RunLogLine>, paused: Boolean, modifier: Modifier) {
    val frozen = remember { mutableStateListOf<RunLogLine>() }
    LaunchedEffect(paused) {
        if (paused) { frozen.clear(); frozen.addAll(lines) }
    }
    val display = if (paused) frozen else lines

    Surface(color = BT.surface, shape = RoundedCornerShape(BT.radius), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${stringResource(R.string.stream_label_datas)}: $datas", fontSize = 12.sp, color = BT.primaryDeep, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600)
                Text("${stringResource(R.string.stream_label_time)}: ${formatDurationHms(elapsedMs)}", fontSize = 12.sp, color = BT.onSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600)
            }
            Spacer(Modifier.height(6.dp))
            // 锚底：reverseLayout=true + 倒序，最新在底，旧行自顶裁切，无滚动条
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                val reversed = display.asReversed()
                items(reversed.size) { idx ->
                    val line = reversed[idx]
                    Row {
                        Text(line.timeLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = if (line.kind == RunLogLine.Kind.LABEL) BT.success else BT.primaryDeep)
                        Spacer(Modifier.size(6.dp))
                        Text(line.text, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = if (line.kind == RunLogLine.Kind.LABEL) BT.success else BT.onSurfaceV)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelRow(
    text: String,
    onTextChange: (String) -> Unit,
    intervalOpen: Boolean,
    onPin: () -> Unit,
    onToggleInterval: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.run_label_hint), fontSize = 13.sp) },
                singleLine = true,
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onTextChange("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear), modifier = Modifier.size(18.dp)) }
                    }
                },
                colors = TextFieldDefaults.colors(focusedContainerColor = BT.surface, unfocusedContainerColor = BT.surface),
                shape = RoundedCornerShape(BT.radius),
                modifier = Modifier.weight(1f).height(56.dp),
            )
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.clickable { onPin() }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, tint = BT.primaryDeep, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.run_label_pin), fontSize = 12.5.sp, fontWeight = FontWeight.W700, color = BT.primaryDeep)
                }
            }
        }
        Surface(
            color = if (intervalOpen) BT.primary else Color.Transparent,
            shape = RoundedCornerShape(BT.radius),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BT.primary),
            modifier = Modifier.fillMaxWidth().clickable { onToggleInterval() },
        ) {
            Text(
                if (intervalOpen) stringResource(R.string.run_label_stop) else stringResource(R.string.run_label_start),
                fontSize = 13.sp, fontWeight = FontWeight.W700,
                color = if (intervalOpen) Color.White else BT.primaryDeep,
                modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 长按 2 秒结束（半透明遮罩 + 环形进度，松手取消，§5.8）。 */
@Composable
private fun LongPressEndButton(onEnd: () -> Unit, modifier: Modifier = Modifier) {
    var pressing by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (pressing) 1f else 0f,
        animationSpec = tween(durationMillis = if (pressing) 2000 else 0, easing = LinearEasing),
        label = "longpress",
    )

    Surface(
        color = BT.error, shape = RoundedCornerShape(BT.radius),
        modifier = modifier.height(48.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressing = true
                val released = withTimeoutOrNull(2000) { tryAwaitRelease() }
                pressing = false
                if (released == null) onEnd() // 按满 2 秒 → 结束
            })
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (pressing) stringResource(R.string.run_end_releasing) else stringResource(R.string.run_end_hint), fontSize = 14.sp, fontWeight = FontWeight.W700, color = Color.White)
        }
    }

    if (pressing) {
        Box(Modifier.fillMaxSize().background(Color(0x57F3F5F8)), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(120.dp), color = BT.primary, strokeWidth = 8.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${"%.1f".format(progress * 2)}s", fontSize = 26.sp, fontWeight = FontWeight.W800, color = BT.primaryDeep)
                    Text(stringResource(R.string.run_end_overlay_hint), fontSize = 11.sp, color = BT.onSurfaceV)
                }
            }
        }
    }
}
