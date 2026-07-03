package io.bluetrace.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.ui.theme.BT

/**
 * 扫描过滤条（连接页共用）：名称/MAC 搜索框（放大镜 + 一键清除 ×）+ RSSI 阈值滑条。
 * 样式对齐 V4 设计稿（`.search` + RSSI 卡片）。两个连接页（采集「设备连接」/ 控制台「连接手表」）复用。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScanFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    rssiThreshold: Int,
    onRssiChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rssiRange: ClosedFloatingPointRange<Float> = -99f..-30f,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 搜索框（设计稿 .search）：放大镜 + 输入 + 一键清除 ×
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BT.surface)
                .border(1.dp, BT.outlineV, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = BT.outline, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.device_filter_hint), fontSize = 13.sp, color = BT.onSurfaceV)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = BT.onSurface),
                    cursorBrush = SolidColor(BT.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = BT.outline,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                )
            }
        }

        // RSSI 过滤卡片（设计稿）：标签左、阈值右（加粗·primaryDeep·等宽）+ 滑条
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BT.surface)
                .border(1.dp, BT.outlineV, RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.device_rssi_label), fontSize = 11.sp, color = BT.onSurfaceV)
                Text(
                    "$rssiThreshold dBm",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    color = BT.primaryDeep,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val interaction = remember { MutableInteractionSource() }
            Slider(
                value = rssiThreshold.toFloat(),
                onValueChange = { onRssiChange(it.toInt()) },
                valueRange = rssiRange,
                interactionSource = interaction,
                colors = SliderDefaults.colors(
                    activeTrackColor = BT.primary,
                    inactiveTrackColor = BT.surface2,
                ),
                // 16dp 圆环：透明背景（不填白，融入轨道不突兀）+ 2dp primary 描边
                thumb = {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .border(2.dp, BT.primary, CircleShape),
                    )
                },
            )
        }
    }
}
