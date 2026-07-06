package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.data.android.DeviceLogStore
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/**
 * 设备日志查看页：按文件名读取，逐行显示。
 * 行号是**界面 gutter**（非文本内容）；正文按行**原样呈现**；长日志带**滚动条**（可拖动跳转）。
 */
@Composable
fun ConsoleLogViewScreen(fileName: String, onBack: () -> Unit, store: DeviceLogStore = koinInject()) {
    // IO 读文件 → 按行切分（保留原始每一行，不改动内容）
    val content by produceState<String?>(initialValue = null, fileName) {
        value = store.read(fileName) ?: "" // Store 自守 IO（A3）
    }
    val lines = remember(content) { content?.split("\n") ?: emptyList() }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val gutterW = remember(lines.size) { (lines.size.toString().length * 8 + 12).dp }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.console_log_view_title), subtitle = fileName, onBack = onBack)
        when {
            content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("…", fontSize = 20.sp, color = BT.onSurfaceV)
            }
            lines.isEmpty() || (lines.size == 1 && lines[0].isEmpty()) ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.console_log_empty), fontSize = 13.sp, color = BT.onSurfaceV)
                }
            else -> {
                Text(
                    stringResource(R.string.console_log_lines, lines.size, content!!.length),
                    fontSize = 11.sp, color = BT.onSurfaceV,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp),
                        state = listState,
                    ) {
                        itemsIndexedLines(lines, gutterW, hScroll)
                    }
                    // 滚动条（右侧，可拖动）
                    VerticalScrollbar(listState, lines.size, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedLines(
    lines: List<String>,
    gutterW: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
) {
    items(lines.size) { i ->
        Row {
            // 行号 gutter（界面元素，不属正文）
            Text(
                "${i + 1}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = BT.onSurfaceV,
                modifier = Modifier.width(gutterW).padding(end = 6.dp),
            )
            // 正文原样呈现；长行横向滚动（gutter 不动）
            Text(
                lines[i],
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = BT.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.horizontalScroll(hScroll),
            )
        }
    }
}

/** LazyColumn 竖向滚动条：thumb 高∝可见比例、位置∝滚动进度，可拖动跳转。 */
@Composable
private fun VerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 0) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight().width(14.dp)) {
        val trackH = constraints.maxHeight.toFloat()
        val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val maxFirst = (itemCount - visible).coerceAtLeast(1)
        val thumbFrac = (visible.toFloat() / itemCount).coerceIn(0.04f, 1f)
        val posFrac = (state.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
        val thumbH = trackH * thumbFrac
        val thumbY = (trackH - thumbH) * posFrac
        // 轨道
        Box(Modifier.fillMaxHeight().width(4.dp).align(Alignment.CenterEnd).clip(RoundedCornerShape(2.dp)).background(BT.surface2))
        // thumb
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY.roundToInt()) }
                .width(5.dp)
                .height(with(density) { thumbH.toDp() })
                .clip(RoundedCornerShape(3.dp))
                .background(BT.outline)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val range = (trackH - thumbH).coerceAtLeast(1f)
                        val target = ((posFrac + delta / range) * maxFirst).roundToInt().coerceIn(0, itemCount - 1)
                        scope.launch { state.scrollToItem(target) }
                    },
                ),
        )
    }
}
