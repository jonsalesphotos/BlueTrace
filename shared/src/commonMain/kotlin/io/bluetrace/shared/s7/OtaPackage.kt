package io.bluetrace.shared.s7

/**
 * 一个 OTA 升级包 = 有序文件清单（顺序即推送顺序；末文件 STOP 后设备判整包完成）。
 * 采集固件刷入 = 采集 `ota_all`(14 文件) 或最小 `ota_part`(ResData/ResFat/ResCheck/fw.dat)。
 * 见 `Docs/OTA/S7采集OTA_设计.md` §1.1。
 */
data class OtaPackage(
    val moduleId: Int = S7FileTrans.MODULE_OTA,
    val files: List<OtaFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes.size.toLong() }
    val fileCount: Int get() = files.size
}

/**
 * 包内一个文件。[name] ≤12 字符，决定设备落地位置（如 `fw.dat`/`ResData.dat`）。
 * [fileType] 为 BFTT_*（仅驱动手表进度 UI，功能落点由文件名决定）。
 */
data class OtaFile(
    val name: String,
    val bytes: ByteArray,
    val fileType: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtaFile) return false
        return name == other.name && fileType == other.fileType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + fileType
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

/** 推送进度（文件序号 / 已发字节 / 总字节）。 */
data class OtaProgress(
    val fileIdx: Int,
    val fileCount: Int,
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
)

/** OTA 推送结果。DoneDownload = 末文件 STOP 收讫（设备将自复位生效，App 需重连读版本确认）。 */
sealed interface OtaResult {
    /** 全部文件推完、末 STOP 应答成功；生效由设备自主完成。 */
    data object DoneDownload : OtaResult
    data class Failed(val reason: OtaFailure) : OtaResult
}

/** OTA 失败原因。 */
sealed interface OtaFailure {
    /** 未连接 / 链路非 CONNECTED。 */
    data object NotConnected : OtaFailure

    /** 等应答超时（REQ 授权 / 切片 ack / 文件 ack）。[stage] 便于定位。 */
    data class Timeout(val stage: String) : OtaFailure

    /** REQ 被设备拒绝（磁盘满 / 忙 / 内存）。 */
    data class ReqRejected(val status: Int) : OtaFailure

    /** 设备对 START/STOP 回错误码（EBEC_*）。 */
    data class DeviceError(val stage: String, val code: Int) : OtaFailure

    /** 切片重传超过上限（校验/长度不符或设备 NAK）。 */
    data class SliceFailed(val fileName: String, val offset: Long) : OtaFailure

    /** 应答格式非法（长度不足等）。 */
    data class Malformed(val stage: String) : OtaFailure
}
