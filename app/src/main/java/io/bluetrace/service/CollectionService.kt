package io.bluetrace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.bluetrace.MainActivity
import io.bluetrace.R
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.session.RunStatus
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.util.formatDurationHms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 前台服务托管采集（§7.4 / §5.4）。常驻通知 = "正在采集"的在场感（点回运行页）。
 * 被回收靠 `START_STICKY`；会话 STOPPED 时自停。采集逻辑实际跑在 app 级 [SessionController] 上，
 * 本服务负责把进程保持在前台 + 通知。
 */
class CollectionService : Service() {

    private val controller: SessionController by inject()
    private val bleClient: BleClient by inject() // 只依赖接口："蓝牙关→重连中"是产品行为，换真实实现不丢
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    /** 采集中蓝牙关 → 设备转"重连中"（§5.4 横切A）；开 → 恢复。 */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> bleClient.onAdapterStateChanged(off = true)
                BluetoothAdapter.STATE_ON -> bleClient.onAdapterStateChanged(off = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // sticky 重启（进程被杀后）注入的是全新 READY controller：没有会话可托管，立即自杀——
        // 否则会留一条冻结在 00:00:00、不可滑除的僵尸"正在采集"通知（数据侧由 AppStartup 自动收尾）。
        if (controller.state.value.status != RunStatus.COLLECTING) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startAsForeground(controller.state.value.elapsedMs, controller.state.value.datasCount) }
        // 收集协程只起一次：重复 onStartCommand（如再次点开始）不再叠加收集器
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.collectLatest { st ->
                    if (st.status == RunStatus.STOPPED) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        notify(st.elapsedMs, st.datasCount)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(btReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(elapsedMs: Long, datas: Long) {
        val notification = buildNotification(elapsedMs, datas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notify(elapsedMs: Long, datas: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(elapsedMs, datas))
    }

    private fun buildNotification(elapsedMs: Long, datas: Long): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, formatDurationHms(elapsedMs), datas))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "collection_running"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CollectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CollectionService::class.java))
        }
    }
}
