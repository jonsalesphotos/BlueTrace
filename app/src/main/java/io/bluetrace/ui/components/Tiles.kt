package io.bluetrace.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.ui.theme.BT

/**
 * 入口磁贴（EntryTile：设备/用户/模式，§8.1）。对齐原型采集A：圆形图标 + 短粗标题 + 副标 +
 * 右侧值/chevron（可导航），下方可挂 badge/chip 行。
 */
@Composable
fun EntryTile(
    icon: ImageVector,
    iconColor: Color,
    iconBg: Color,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    valueColor: Color = BT.onSurfaceV,
    showChevron: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    belowContent: @Composable (() -> Unit)? = null,
) {
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        border = BorderStroke(1.dp, BT.outlineV),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), // 原型 .ico=圆角方 r10（圆形为实现漂移）
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
                    if (subtitle != null) {
                        Text(
                            subtitle, fontSize = 11.sp, color = BT.onSurfaceV,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                if (value != null) {
                    Text(value, fontSize = 12.sp, fontWeight = FontWeight.W600, color = valueColor)
                }
                if (trailing != null) trailing()
                if (showChevron) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null, tint = BT.outline, modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (belowContent != null) {
                Spacer(Modifier.height(10.dp))
                belowContent()
            }
        }
    }
}

/** 通用列表磁贴（设置行 / 文件构成行）。 */
@Composable
fun ListTileRow(
    icon: ImageVector,
    iconColor: Color,
    iconBg: Color,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        color = BT.surface,
        shape = RoundedCornerShape(BT.radius),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), // 同上：圆角方对齐原型
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = BT.onSurfaceV)
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(999.dp), // 原型 .btn=胶囊（999px），14dp 为实现漂移
        colors = ButtonDefaults.buttonColors(containerColor = BT.primary, contentColor = Color.White),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.W700)
    }
}

@Composable
fun OutlineBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = BT.onSurface,
    leadingIcon: ImageVector? = null,
    borderColor: Color = BT.outlineV,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(999.dp), // 胶囊，同 PrimaryButton
        border = BorderStroke(1.dp, borderColor),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.W600, color = color)
    }
}

/** 空态（EmptyState：列表/扫描/搜索无内容，§8.1）。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(BT.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BT.onSurfaceV, modifier = Modifier.size(30.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.W700, color = BT.onSurface)
        Text(subtitle, fontSize = 12.sp, color = BT.onSurfaceV)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(actionText, onAction, Modifier.padding(horizontal = 40.dp))
        }
    }
}
