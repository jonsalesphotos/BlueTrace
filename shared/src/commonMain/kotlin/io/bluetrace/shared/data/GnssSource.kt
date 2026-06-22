package io.bluetrace.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 本机 GNSS 一路样本（F-GPS-1，§6.5）。落 `gps.csv`。 */
data class GpsSample(
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedMps: Double,
    val accuracyM: Double,
)

/**
 * GNSS 数据源（while-in-use，§6.5）。app 用 Android LocationManager 实现；commonMain 只认 Flow。
 * 采集会话开启 GNSS 时由 [io.bluetrace.shared.session.DefaultSessionController] 订阅 → 写 gps.csv。
 */
fun interface GnssSource {
    /** 冷流：订阅即开始定位，取消即停（不申请后台定位，D-3）。 */
    fun samples(): Flow<GpsSample>

    companion object {
        /** 空源（无 GNSS / 测试用）。 */
        val None: GnssSource = GnssSource { emptyFlow() }
    }
}
