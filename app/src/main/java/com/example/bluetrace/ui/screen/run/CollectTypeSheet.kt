package com.example.bluetrace.ui.screen.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.example.bluetrace.R
import com.example.bluetrace.shared.domain.CollectType
import com.example.bluetrace.ui.components.CircleCheck
import com.example.bluetrace.ui.components.OutlineBtn
import com.example.bluetrace.ui.components.PrimaryButton
import com.example.bluetrace.ui.theme.BT
import com.example.bluetrace.ui.theme.sensorColor

/** 运行C · 采集类型选择（下抽屉；开关 = 该路是否上传/落盘，D-V4-12）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectTypeSheet(
    selected: Set<CollectType>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (Set<CollectType>) -> Unit,
) {
    var working by remember { mutableStateOf(selected) }

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

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.action_confirm), { onConfirm(working) }, Modifier.weight(1f))
            }
        }
    }
}
