package io.bluetrace.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.ThemeMode
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 外观 · 主题模式（亮 / 暗 / 跟随系统，§8）。点选即时生效（写 DataStore → BlueTraceTheme 重组全局）。 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val prefs = koinInject<AppPreferences>()
    val mode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(
            title = stringResource(R.string.appearance_title),
            subtitle = stringResource(R.string.appearance_subtitle),
            onBack = onBack,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption(R.string.appearance_system, mode == ThemeMode.SYSTEM) {
                scope.launch { prefs.setThemeMode(ThemeMode.SYSTEM) }
            }
            ThemeOption(R.string.appearance_light, mode == ThemeMode.LIGHT) {
                scope.launch { prefs.setThemeMode(ThemeMode.LIGHT) }
            }
            ThemeOption(R.string.appearance_dark, mode == ThemeMode.DARK) {
                scope.launch { prefs.setThemeMode(ThemeMode.DARK) }
            }
        }
    }
}

@Composable
private fun ThemeOption(titleRes: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) BT.primaryC else BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(titleRes), fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = BT.primary)
        }
    }
}
