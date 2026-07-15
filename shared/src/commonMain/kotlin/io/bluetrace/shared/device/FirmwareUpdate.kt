package io.bluetrace.shared.device

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope

/**
 * 固件升级面通用类型(设计 V2 §3.3): 通用层只看得到粗粒度阶段 + 进度 + 终态.
 * 包解析/校验/传输/Bootloader 切换/重连/验证全在协议实现内(S7 见 [io.bluetrace.shared.s7.S7FirmwareUpdateStrategy]).
 */

/** 粗粒度升级阶段(通用). 各协议的细阶段(如 S7 的扫描回连)映射到此 + 细文案塞 [FwUpdateProgress.detail]. */
enum class FwUpdatePhase { Preparing, Transferring, Applying, Verifying, Done }

/**
 * 升级进度(通用): 粗阶段 + 百分比 + 上传速度 + 细文案.
 * [detail] 承载协议细阶段文案(如 "扫描回连"/文件名), 通用 UI 展示用, 不进语义判断.
 */
data class FwUpdateProgress(
    val phase: FwUpdatePhase,
    val percent: Int,
    val bytesPerSec: Long,
    val detail: String?,
)

/**
 * 升级终态(通用).
 * - [Success]: 升级完成; [versionAfter]=完成后读到的设备版本(null=读不到, UI 显示"未知", 非失败).
 * - [Failed]: 失败; [summary]=人话原因(协议实现构造点解析), [percentAtFailure]=失败时进度(0..100).
 */
sealed interface FwUpdateResult {
    data class Success(val versionAfter: String?) : FwUpdateResult
    data class Failed(val summary: String, val percentAtFailure: Int) : FwUpdateResult
}

/**
 * 升级包载体(空 marker): 包类型协议自定(S7 的 [io.bluetrace.shared.s7.OtaPackage] 实现本接口).
 * 策略内 `pkg as? XxxPackage ?: return Failed(...)` 属受限/语义正确的向下转型(同 vendor 面手法):
 * 某协议策略只可能收该协议 loader 产出的包, 收错=编程错误. 具体包字段**不上移**通用层.
 */
interface FwPackage

/**
 * 固件升级策略(设计 V2 §3.3): 整体策略, 非"文件传输".
 * [run] 跑完整升级链(下载/切换/重连/验证), [abort] 做手动中止的协议正确善后.
 */
interface FirmwareUpdateStrategy {
    /** 跑完整升级. [pkg]=协议自定包(由各协议 loader 产出), [onProgress]=粗粒度进度回调. */
    suspend fun run(pkg: FwPackage, onProgress: (FwUpdateProgress) -> Unit = {}): FwUpdateResult

    /**
     * 手动中止的协议正确善后(S7=按传输态门控停发切片/断链等 61s 看门狗; 他协议=各自语义).
     * **不负责断链**(断链归调用方); 只做协议要求的中止指令收尾.
     */
    suspend fun abort()
}

/**
 * 固件升级面工厂(W3 空 marker 转正, 设计 V2 §3.3): 由 profile 声明式提供, 挂在
 * [DeviceProfile.firmwareUpdate](对称 W3 的 [ControlPlaneFactory]).协议实现在此包装其升级编排.
 *
 * 与 [ControlPlaneFactory.create] 的差异(受固件升级需要, W4 裁定):
 * - 收 [device]([ScannedDevice]) 而非 deviceId——升级链的自复位回连需整设备(scan 匹配 id + connect 用 address);
 * - 加 [abortScope](中止善后 scope, 须比 UI 壳长寿, 退屏也发得出重启指令) + [reconnectScanMs](回连扫描预算,
 *   产品下限 60s, 工程配置只可调大) + [onLog](操作日志汇);
 * - **协议专属的细粒度进度观察**(如 S7 的 OtaPhase/OtaProgress)不进本通用工厂——只能经具体策略构造注入.
 */
interface FirmwareUpdateFactory {
    fun create(
        ble: BleClient,
        device: ScannedDevice,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
        abortScope: CoroutineScope,
        reconnectScanMs: Long = 60_000,
        onLog: (String) -> Unit = {},
    ): FirmwareUpdateStrategy
}
