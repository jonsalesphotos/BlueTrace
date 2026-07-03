package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.domain.DeviceLogStore
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** 设备日志列表：列出日志文件夹所有日志，手动选一条查看。 */
@Composable
fun ConsoleLogListScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    store: DeviceLogStore = koinInject(),
    zone: TimeZoneProvider = koinInject(),
) {
    // 进入时读一次列表（IO）
    val entries by produceState(initialValue = emptyList<DeviceLogStore.Entry>()) {
        value = withContext(Dispatchers.IO) { store.list() }
    }

    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.console_log_list_title),
            subtitle = store.dirPath(),
            onBack = onBack,
        )
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(stringResource(R.string.console_log_empty), fontSize = 13.sp, color = BT.onSurfaceV)
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp)) }
            items(entries, key = { it.name }) { e ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BT.surface)
                        .clickable { onOpen(e.name) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    androidx.compose.material3.Text(
                        e.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W700,
                        color = BT.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                    val date = remember(e.modifiedMs) {
                        val p = epochMsToLocalParts(e.modifiedMs, zone.offsetSeconds())
                        "${p.year}-${p2(p.month)}-${p2(p.day)} ${p2(p.hour)}:${p2(p.minute)}"
                    }
                    androidx.compose.material3.Text(
                        "$date · ${e.sizeBytes} B",
                        fontSize = 11.sp,
                        color = BT.onSurfaceV,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun p2(v: Int) = if (v < 10) "0$v" else "$v"
