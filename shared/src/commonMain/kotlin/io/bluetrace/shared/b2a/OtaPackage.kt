package io.bluetrace.shared.b2a

import io.bluetrace.shared.device.FwPackage

/**
 * 一个 OTA 升级包 = 有序文件清单(顺序即推送顺序; 末文件 STOP 后设备判整包完成).
 * 采集固件刷入 = 采集 `ota_all`(14 文件) 或最小 `ota_part`(ResData/ResFat/ResCheck/fw.dat).
 * 见 `Docs/OTA/S7采集OTA_设计.md` §1.1.
 *
 * 实现通用 [FwPackage] marker(W4): S7 策略 [B2aFirmwareUpdateStrategy] 内 `pkg as? OtaPackage` 受限转型据此.
 */
data class OtaPackage(
    val moduleId: Int = B2aFileTrans.MODULE_OTA,
    val files: List<OtaFile>,
) : FwPackage {
    val totalBytes: Long get() = files.sumOf { it.bytes.size.toLong() }
    val fileCount: Int get() = files.size
}

/**
 * 包内一个文件. [name] ≤12 字符, 决定设备落地位置(如 `fw.dat`/`ResData.dat`).
 * [fileType] 为 BFTT_*(仅驱动手表进度 UI, 功能落点由文件名决定).
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

/** 推送进度(文件序号 / 已发字节 / 总字节 / 上传速度).  */
data class OtaProgress(
    val fileIdx: Int,
    val fileCount: Int,
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    /** BLE 上传速度(B/s, ≥1s 滑动窗口; 0=首窗未出数).  */
    val bytesPerSec: Long = 0,
)

/**
 * OTA 结果.
 * - [DoneDownload]: 末文件 STOP 收讫(下载阶段成功, [B2aOtaSession.provision] 的终态); 生效未确认.
 * - [Reconnected]: 下载后设备自复位 → 重连成功, 读到当前版本([OtaProvisioner] 的终态).
 * - [Failed]: 下载失败或重连失败.
 */
sealed interface OtaResult {
    /** 全部文件推完, 末 STOP 应答成功; 生效由设备自主完成, 尚未确认.  */
    data object DoneDownload : OtaResult

    /**
     * 下载完成 + 设备自复位 + 重连成功. [currentVersion] = 重连后读到的设备当前版本(null=读不到, UI 显示"未知").
     * **仅供展示, 非成功/失败判据**——OTA 包不含版本信息, 无法做"包版本 vs 设备版本"的机器校验
     * (用户 2026-07-08 决定: 不做验证, 回连后读取显示当前版本即可). 是否刷成功由人工看版本判断.
     */
    data class Reconnected(val currentVersion: String?) : OtaResult
    data class Failed(val reason: OtaFailure) : OtaResult
}

/**
 * OTA 失败原因.
 * 展示统一走 [describe](哪条指令出错, 文件传输错在哪个文件哪个偏移), UI/日志共用.
 */
sealed interface OtaFailure {
    /** 未连接 / 链路非 CONNECTED.  */
    data object NotConnected : OtaFailure

    /** 等应答超时(REQ 授权 / 切片 ack / 文件 ack). [stage] 便于定位.  */
    data class Timeout(val stage: String) : OtaFailure

    /** REQ 被设备拒绝(磁盘满 / 忙 / 内存).  */
    data class ReqRejected(val status: Int) : OtaFailure

    /** 设备对 START/STOP 回错误码(EBEC_*).  */
    data class DeviceError(val stage: String, val code: Int) : OtaFailure

    /** 切片重传超过上限(校验/长度不符或设备 NAK).  */
    data class SliceFailed(val fileName: String, val offset: Long) : OtaFailure

    /** 应答格式非法(长度不足等).  */
    data class Malformed(val stage: String) : OtaFailure

    // ---- 下载后回连阶段([OtaProvisioner]) ----

    /** 设备自复位后重连失败(扫描窗口 + 直连重试均未 CONNECTED)——无法回连查看设备(需人工重连).  */
    data object ReconnectFailed : OtaFailure
    // 注: 版本读不到不算失败——回连成功即 [OtaResult.Reconnected], currentVersion=null 显示"未知".
}

/** 人话失败描述: 定位到出错指令 / 文件传输失败的文件与字节偏移(UI 结果卡 + 执行日志 + 队列行共用).  */
fun OtaFailure.describe(): String = when (this) {
    OtaFailure.NotConnected -> "设备未连接"
    is OtaFailure.Timeout -> "指令 $stage 应答超时"
    is OtaFailure.ReqRejected -> "REQ 被设备拒绝：${reqStatusName(status)}"
    is OtaFailure.DeviceError -> "指令 $stage 设备返回错误 ${B2a.errorName[code] ?: "0x${code.toString(16)}"}"
    is OtaFailure.SliceFailed -> "文件传输失败：$fileName @ 偏移 $offset（切片重传超限）"
    is OtaFailure.Malformed -> "指令 $stage 应答格式非法"
    OtaFailure.ReconnectFailed -> "回连失败（扫描窗口内未连上设备）"
}

private fun reqStatusName(status: Int): String = when (status) {
    B2aFileTrans.REQ_DISK_FULL -> "DISK_FULL(磁盘满)"
    B2aFileTrans.REQ_BUSY -> "BUSY(设备忙)"
    B2aFileTrans.REQ_MEMORY -> "MEMORY(内存不足)"
    else -> "0x${status.toString(16)}"
}

/** OTA 端到端阶段(驱动 UI 文案; [OtaProvisioner.provisionAndReconnect] 的 onPhase 回调).  */
enum class OtaPhase { Downloading, WaitingReboot, Scanning, Reconnecting, ReadingVersion, Done }
