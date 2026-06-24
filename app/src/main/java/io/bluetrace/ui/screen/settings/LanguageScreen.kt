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
import io.bluetrace.shared.domain.AppLanguage
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.ui.components.BtTopBar
import io.bluetrace.ui.theme.BT
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 设置H · 语言：仅 中文（简体）/ English，无"跟随系统"（red-line：language-zh-en-only）。
 * 点选即写偏好（持久化 + 选中态）。仅切 UI 显示；会话文件名 / 场景 token / manifest 字段恒英文，不随此变。
 * 运行时 locale 即时切换属后续轮（本轮持久化 + 重组）。
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val prefs = koinInject<AppPreferences>()
    val language by prefs.language.collectAsState(initial = AppLanguage.ZH)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(BT.bg)) {
        BtTopBar(title = stringResource(R.string.language_title), onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 语言名用 autonym（中文（简体）/ English），与当前界面语言无关。
            LanguageOption(R.string.lang_zh, language == AppLanguage.ZH) {
                scope.launch { prefs.setLanguage(AppLanguage.ZH) }
            }
            LanguageOption(R.string.lang_en, language == AppLanguage.EN) {
                scope.launch { prefs.setLanguage(AppLanguage.EN) }
            }
        }
    }
}

@Composable
private fun LanguageOption(titleRes: Int, selected: Boolean, onClick: () -> Unit) {
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
