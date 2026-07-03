package io.bluetrace.ui.components

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.bluetrace.R
import io.bluetrace.data.android.BlueTracePermissions
import io.bluetrace.ui.theme.BT

/** 扫描权限状态（含定位）：连接页扫描前统一门控。 */
class ScanPermission(
    val granted: Boolean,
    /** 已请求过且系统不再弹（永久拒绝）→ 只能去设置。 */
    val permanentlyDenied: Boolean,
    /** 弹系统授权（缺失时用）。 */
    val request: () -> Unit,
    /** 跳应用设置页（永久拒绝时用）。 */
    val openSettings: () -> Unit,
)

/**
 * 扫描前置权限（[BlueTracePermissions.scan]，含 FINE_LOCATION）的 Compose 门控。
 * - `ON_RESUME` 重新核查（用户可能在系统设置里改了权限）。
 * - 供连接页：进页面/开扫描前调用 [ScanPermission.request]；缺失时用 [ScanPermissionBanner] 提示。
 */
@Composable
fun rememberScanPermission(): ScanPermission {
    val context = LocalContext.current
    fun check(): Boolean =
        BlueTracePermissions.scan.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    var granted by remember { mutableStateOf(check()) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = check()
        asked = true
    }

    // 从系统设置返回时重新核查（MIUI 撤权后回来能自恢复）
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) granted = check()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val activity = context as? Activity
    val permanentlyDenied = !granted && asked && activity != null &&
        BlueTracePermissions.scan.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    return ScanPermission(
        granted = granted,
        permanentlyDenied = permanentlyDenied,
        request = { launcher.launch(BlueTracePermissions.scan) },
        openSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

/** 权限不足时的提示条：一键授权 / 去设置。 */
@Composable
fun ScanPermissionBanner(perm: ScanPermission, modifier: Modifier = Modifier) {
    Surface(color = BT.warningC, shape = androidx.compose.foundation.shape.RoundedCornerShape(BT.radius), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_perm_title), fontSize = 12.5.sp, fontWeight = FontWeight.W700, color = BT.onWarningC)
            Text(stringResource(R.string.scan_perm_body), fontSize = 11.sp, color = BT.onWarningC)
            OutlineBtn(
                text = stringResource(if (perm.permanentlyDenied) R.string.scan_perm_settings else R.string.scan_perm_grant),
                onClick = { if (perm.permanentlyDenied) perm.openSettings() else perm.request() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
