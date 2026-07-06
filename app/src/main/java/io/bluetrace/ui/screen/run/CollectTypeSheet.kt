package io.bluetrace.ui.screen.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bluetrace.R
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.ui.components.CircleCheck
import io.bluetrace.ui.components.OutlineBtn
import io.bluetrace.ui.components.PrimaryButton
import io.bluetrace.ui.theme.BT
import io.bluetrace.ui.theme.sensorColor

/** 运行C · 采集类型选择（下抽屉；开关 = 该路是否上传/落盘，D-V4-12）。末行 = 本机 GNSS 一路（§6.5）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectTypeSheet(
    selected: Set<CollectType>,
    gnssOn: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (Set<CollectType>, Boolean) -> Unit,
) {
    var working by remember { mutableStateOf(selected) }
    var workingGnss by remember { mutableStateOf(gnssOn) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.run_collect_type_title), fontSize = 17.sp, fontWeight = FontWeight.W700, color = BT.onSurface, modifier = Modifier.padding(bottom = 4.dp))
            Text(stringResource(R.string.run_collect_type_sub), fontSize = 11.sp, color = BT.onSurfaceV, modifier = Modifier.padding(bottom = 10.dp))

            CollectType.entries.forEach { type ->
                val checked = type in working
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { working = if (checked) working - type else working + type }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(type.label, fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface, modifier = Modifier.weight(1f))
                    CircleCheck(checked = checked, color = sensorColor(type.id))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = BT.outlineV)

            // 本机 GNSS：独立一路 → gps.csv（§6.5）；勾选后开始前按需授权定位（拒绝则本次无 GPS 一路，不阻断）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { workingGnss = !workingGnss }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.run_type_gnss), fontSize = 14.sp, fontWeight = FontWeight.W600, color = BT.onSurface)
                    Text(stringResource(R.string.run_type_gnss_sub), fontSize = 11.sp, color = BT.onSurfaceV)
                }
                CircleCheck(checked = workingGnss, color = BT.primary) // 勾选统一主蓝
            }

            // 防呆：全部取消勾选会让所有解码 CSV 静默停写（用户可能长时间空采）→ 空选禁用确认并警示
            if (working.isEmpty()) {
                Text(stringResource(R.string.run_type_none_hint), fontSize = 11.sp, color = BT.warning, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.action_confirm), { onConfirm(working, workingGnss) }, Modifier.weight(1f), enabled = working.isNotEmpty())
            }
        }
    }
}
