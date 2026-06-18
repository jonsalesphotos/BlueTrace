package com.example.bluetrace.ui.screen.run

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetrace.shared.domain.CollectType
import com.example.bluetrace.shared.domain.DeviceKind
import com.example.bluetrace.shared.domain.LinkState
import com.example.bluetrace.shared.session.RunLogLine
import com.example.bluetrace.shared.session.RunDeviceState
import com.example.bluetrace.shared.util.formatDurationHms
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.StatusPill
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.ui.theme.sensorColor
import com.example.bluetrace.viewmodel.RunViewModel
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
    vm: RunViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    // 硬锁定：返回拦截，提示"长按结束退出"（§5.4），不退出。
    BackHandler(enabled = finished == null) { onHardLockHint() }

    // 结束（长按 / 存储满）→ finished 置非空 → 跳结束摘要。
    LaunchedEffect(finished) { if (finished != null) onFinished() }

    val config = vm.activeConfig
    val scope = rememberCoroutineScope()
    var showTypeSheet by remember { mutableStateOf(true) } // 进入自动弹（D-V4-12）
    var selectedTypes by remember { mutableStateOf(config?.enabledTypes ?: CollectType.defaults) }
    var labelText by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(BT.bg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(Modifier.fillMaxWidth().background(BT.surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("数据采集", fontSize = 19.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                    val subjectAlias = config?.subject?.alias ?: "—"
                    val modeLabel = config?.mode?.label ?: "—"
                    Text("$subjectAlias · $modeLabel · ${formatDurationHms(state.elapsedMs)}", fontSize = 11.sp, color = BT.onSurfaceV, fontFamily = FontFamily.Monospace)
                }
                StatusPill("采集中", BT.onSuccessC, BT.successC)
                IconButton(onClick = { showTypeSheet = true }) { Icon(Icons.Filled.Tune, contentDescription = "采集类型", tint = BT.onSurfaceV) }
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
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(
                    text = if (state.displayPaused) "继续" else "暂停",
                    onClick = { vm.setPaused(!state.displayPaused) },
                )
                LongPressEndButton(onEnd = { scope.launch { vm.stop() } }, modifier = Modifier.weight(1f))
            }
        }

        // 演示钩子（异常清单 §5.4）：注入断联 / 模拟存储满
        Row(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DemoChip("注入断联") { vm.injectDisconnect() }
            DemoChip("模拟存储满") { vm.simulateStorageFull() }
        }
    }

    if (showTypeSheet) {
        CollectTypeSheet(
            selected = selectedTypes,
            sheetState = rememberModalBottomSheetState(),
            onDismiss = { showTypeSheet = false },
            onConfirm = { sel -> selectedTypes = sel; vm.setEnabledTypes(sel); showTypeSheet = false },
        )
    }
}

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
                    Text("重连中…（断联期为数据空档，计时照走）", fontSize = 11.sp, color = BT.warning)
                } else {
                    val sensors = if (device.activeSensors.isEmpty()) "在线" else "在线 · " + device.activeSensors.joinToString(" · ")
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
                Text("Datas: $datas", fontSize = 12.sp, color = BT.primaryDeep, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600)
                Text("Time: ${formatDurationHms(elapsedMs)}", fontSize = 12.sp, color = BT.onSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600)
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
                placeholder = { Text("标签", fontSize = 13.sp) },
                singleLine = true,
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onTextChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "清空", modifier = Modifier.size(18.dp)) }
                    }
                },
                colors = TextFieldDefaults.colors(focusedContainerColor = BT.surface, unfocusedContainerColor = BT.surface),
                shape = RoundedCornerShape(BT.radius),
                modifier = Modifier.weight(1f).height(56.dp),
            )
            Surface(color = BT.primaryC, shape = RoundedCornerShape(BT.radius), modifier = Modifier.clickable { onPin() }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, tint = BT.primaryDeep, modifier = Modifier.size(15.dp))
                    Text("Pin", fontSize = 12.5.sp, fontWeight = FontWeight.W700, color = BT.primaryDeep)
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
                if (intervalOpen) "Stop Label" else "Start Label",
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
            Text(if (pressing) "松开取消…" else "结束（长按 2 秒）", fontSize = 14.sp, fontWeight = FontWeight.W700, color = Color.White)
        }
    }

    if (pressing) {
        Box(Modifier.fillMaxSize().background(Color(0x57F3F5F8)), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(120.dp), color = BT.primary, strokeWidth = 8.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${"%.1f".format(progress * 2)}s", fontSize = 26.sp, fontWeight = FontWeight.W800, color = BT.primaryDeep)
                    Text("松手取消 · 按满 2 秒停止", fontSize = 11.sp, color = BT.onSurfaceV)
                }
            }
        }
    }
}
