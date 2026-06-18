package com.example.bluetrace.shared.data

import com.example.bluetrace.shared.domain.CollectMode
import com.example.bluetrace.shared.domain.Sex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `session_manifest.json`（§6.2）。**开始采集即写关键信息**（会话自描述、被杀也能解）；
 * 结束/自动收尾时补 [endEpochMs] / [stopReason] / [quality]。时间全 unix（§6.3）。
 */
@Serializable
data class SessionManifest(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val folderName: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val timezone: String,
    val utcOffsetSeconds: Int,
    val subject: ManifestSubject,
    val mode: CollectMode,
    val sampling: ManifestSampling,
    val devices: List<ManifestDevice>,
    val gnssEnabled: Boolean = false,
    val appVersion: String = "1.0",
    val stopReason: String? = null,
    val quality: ManifestQuality = ManifestQuality(),
    val files: List<ManifestFile> = emptyList(),
)

@Serializable
data class ManifestSubject(
    val alias: String,
    val sex: Sex,
    val birth: String,
    val heightCm: Int? = null,
    val weightKg: Double? = null,
)

@Serializable
data class ManifestSampling(
    /** 启用的采集类型 id（透明传输，不含 BLE 链路参数 D-5）。 */
    val enabledTypes: List<String>,
)

@Serializable
data class ManifestDevice(
    val role: String,        // "DUT" / "REFERENCE"
    val address: String,
    val name: String,
    val profileId: String? = null,
    val csvFiles: List<String> = emptyList(),
)

@Serializable
data class ManifestQuality(
    val reconnectCount: Int = 0,
    val disconnectTotalMs: Long = 0,
    val droppedPackets: Long = 0,
)

@Serializable
data class ManifestFile(
    val path: String,
    val category: String,
    val lineCount: Long = 0,
    val sizeBytes: Long = 0,
)

/** 全工程统一的 Json（manifest 读写）。 */
val BlueTraceJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
