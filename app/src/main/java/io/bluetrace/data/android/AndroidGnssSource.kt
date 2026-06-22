package io.bluetrace.data.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import io.bluetrace.shared.data.GnssSource
import io.bluetrace.shared.data.GpsSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 本机 GNSS 一路（F-GPS-1，§6.5）：LocationManager 请求定位更新（**while-in-use**，不申请后台定位，D-3）。
 * 订阅即开始、取消即停（[awaitClose] 移除监听）。缺权限/定位关 → 空流（本次会话无 GPS 一路）。
 */
class AndroidGnssSource(private val context: Context) : GnssSource {

    @SuppressLint("MissingPermission")
    override fun samples(): Flow<GpsSample> = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (lm == null || !granted) {
            close()
            return@callbackFlow
        }
        val listener = LocationListener { loc ->
            trySend(
                GpsSample(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    altM = if (loc.hasAltitude()) loc.altitude else 0.0,
                    speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0,
                ),
            )
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}
