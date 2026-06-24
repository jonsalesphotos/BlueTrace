package io.bluetrace.shared.domain

/** 会话结束原因（写 manifest.stopReason，§6.2）。 */
enum class StopReason(val id: String) {
    NORMAL("normal"),
    STORAGE_FULL("storage_full"),
    INTERRUPTED("interrupted"),
}

/** 开始采集时冻结的设备快照（DeviceAssignmentDraft → DeviceAssignment，§7.3）。 */
data class AssignedDevice(
    val deviceId: String,
    val name: String,
    val address: String,
    val kind: DeviceKind,
    val profileId: String? = null,
)

/**
 * 开始采集入参（§7.3 SessionConfig）。开始即可写 manifest 关键信息（会话自描述）。
 * 时间全用 unix（§6.3）：[startEpochMs] + [timezoneId] + [utcOffsetSeconds]。
 */
data class SessionConfig(
    val subject: Subject,
    val scene: SceneSelection,
    val devices: List<AssignedDevice>,
    val enabledTypes: Set<CollectType>,
    val gnssEnabled: Boolean,
    val startEpochMs: Long,
    val timezoneId: String,
    val utcOffsetSeconds: Int,
) {
    val dutDevices: List<AssignedDevice> get() = devices.filter { it.kind == DeviceKind.DUT }
    val referenceDevices: List<AssignedDevice> get() = devices.filter { it.kind == DeviceKind.REFERENCE }
}

/** 会话级质量小结（manifest，§6.2）—— 离线判断完整度，与全局诊断日志是两回事。 */
data class QualitySummary(
    val reconnectCount: Int = 0,
    val disconnectTotalMs: Long = 0,
    val droppedPackets: Long = 0,
)

/** 会话文件夹里的一个产物（结束摘要 / 详情逐项导出用）。 */
data class SessionFile(
    val relativePath: String, // 相对会话夹，如 "raw/dut.hexlog" / "csv/ppg_g.csv"
    val category: SessionFileCategory,
    val sizeBytes: Long = 0,
    val lineCount: Long = 0,
)

enum class SessionFileCategory {
    RAW_HEX,      // 原始 HEX 行日志（source of truth）
    DECODED_CSV,  // 每模块解码 CSV
    COMBO_CSV,    // 组合包兼容 CSV
    GNSS_CSV,     // 本机 GNSS
    MANIFEST,     // session_manifest.json
}

/** 采集结束摘要（结束A），也用于数据 Tab 列表项的轻量元数据。 */
data class SessionSummary(
    val sessionId: String,
    val folderName: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val subjectAlias: String,
    val scene: SceneSelection,
    val deviceCount: Int,
    val sensorCount: Int,
    val totalLines: Long,
    val totalBytes: Long,
    val files: List<SessionFile>,
    val quality: QualitySummary,
    val stopReason: StopReason,
    val enabledTypes: Set<CollectType> = emptySet(),
    val gnssEnabled: Boolean = false,
) {
    val durationMs: Long get() = (endEpochMs - startEpochMs).coerceAtLeast(0)
}
