package com.example.bluetrace.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetrace.ui.theme.BT

/** 顶栏：标题 + 副标题 + 可选返回 + 右侧操作（原型 AppBar，§8.1）。 */
@Composable
fun BtTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BT.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = BT.onSurface)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = BT.onSurfaceV)
            }
        }
        if (actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.W700,
        color = BT.onSurfaceV,
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** 状态药丸（StatusPill，ok/warn/err/idle + 可选脉冲点，§8.1）。 */
@Composable
fun StatusPill(
    text: String,
    fg: Color = BT.onSuccessC,
    bg: Color = BT.successC,
    showDot: Boolean = true,
) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showDot) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
            }
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.W700, color = fg)
        }
    }
}

/** 小标签（传感器 tag / 计数 badge）。 */
@Composable
fun PillTag(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Text(
            text,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.W600,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 计数 badge（"已连 N"，蓝底，tabular）。 */
@Composable
fun CountBadge(text: String) {
    Surface(color = BT.primaryC, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            color = BT.onPrimaryC,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 圆形勾选（采集类型 / 用户选择 / 多选）。 */
@Composable
fun CircleCheck(checked: Boolean, color: Color = BT.primary) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) color else BT.surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
