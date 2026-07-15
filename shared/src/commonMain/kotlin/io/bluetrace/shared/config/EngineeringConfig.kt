package io.bluetrace.shared.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 工程配置（`config/bluetrace_config.json`）：实验室调参用的落盘 JSON，与用户偏好(DataStore)分开。
 * 真源在 app 私有 `files/config/`，公共 `Download/BlueTrace/config/` 放只读镜像供翻看。
 * 字段一律带默认值：文件缺失/字段缺失/解析失败都回默认，不阻塞启动。
 */
@Serializable
data class EngineeringConfig(
    val version: Int = 1,
    val export: ExportConfig = ExportConfig(),
    val ota: OtaConfig = OtaConfig(),
    val log: LogConfig = LogConfig(),
)

/** 数据导出。[rawdataByDate]=true 时会话 zip 落 `rawdata/<YYYY-MM-DD>/`，false 落 `rawdata/` 平铺。 */
@Serializable
data class ExportConfig(
    val rawdataByDate: Boolean = true,
)

/** OTA 调参。 */
@Serializable
data class OtaConfig(
    /**
     * OTA 后 BLE 回连的扫描预算（秒）。**下限 60**（产品要求：回连保证扫描至少 60s），
     * 配置只能调大不能调小——经 [reconnectScanMs] 取用时钳制。
     */
    val reconnectScanSeconds: Int = 60,
    /** 多设备批量刷前电量门槛（%），低于则跳过。 */
    val lowBatteryPct: Int = 30,
) {
    val reconnectScanMs: Long get() = reconnectScanSeconds.coerceAtLeast(MIN_RECONNECT_SCAN_SECONDS) * 1000L

    companion object {
        const val MIN_RECONNECT_SCAN_SECONDS = 60
    }
}

/** 日志。 */
@Serializable
data class LogConfig(
    /** 应用滚动日志保留天数（`log/app/app-YYYY-MM-DD.log`）。 */
    val appRetainDays: Int = 7,
)

private val configJson = Json {
    ignoreUnknownKeys = true // 老 App 读新字段配置不炸
    isLenient = true // 容忍手改 JSON 的宽松写法
    prettyPrint = true
    encodeDefaults = true // 默认值也写盘，落地文件即完整可改清单
}

/** 解析失败（含空串/坏 JSON）返回 null，调用方回默认配置。 */
fun parseEngineeringConfig(text: String): EngineeringConfig? = try {
    configJson.decodeFromString<EngineeringConfig>(text)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

fun EngineeringConfig.toJsonText(): String = configJson.encodeToString(this)
