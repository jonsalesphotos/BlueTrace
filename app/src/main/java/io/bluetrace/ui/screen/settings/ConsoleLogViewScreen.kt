package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.domain.S7LogHolder
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import org.koin.compose.koinInject

/** 设备日志查看页：显示最近一次拉取的设备固件日志（等宽、逐行、可横向滚动）。 */
@Composable
fun ConsoleLogViewScreen(onBack: () -> Unit, holder: S7LogHolder = koinInject()) {
    val text = holder.text
    val name = holder.name
    val lines = remember(text) { text?.split("\n") ?: emptyList() }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.console_log_view_title),
            subtitle = name,
            onBack = onBack,
        )
        if (text.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.console_log_empty), fontSize = 13.sp, color = BT.onSurfaceV)
            }
            return@Column
        }
        Text(
            stringResource(R.string.console_log_lines, lines.size, text.length),
            fontSize = 11.sp,
            color = BT.onSurfaceV,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        val hScroll = rememberScrollState()
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                Text(
                    "${i + 1}  $line",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = BT.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(hScroll),
                )
            }
        }
    }
}
