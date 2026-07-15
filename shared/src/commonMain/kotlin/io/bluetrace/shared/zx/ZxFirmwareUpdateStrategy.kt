package io.bluetrace.shared.zx

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.FirmwareUpdateFactory
import io.bluetrace.shared.device.FirmwareUpdateStrategy
import io.bluetrace.shared.device.FwPackage
import io.bluetrace.shared.device.FwUpdatePhase
import io.bluetrace.shared.device.FwUpdateProgress
import io.bluetrace.shared.device.FwUpdateResult
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * ZX 升级包载体(空 marker, 对照 [io.bluetrace.shared.b2a.OtaPackage]): 本协议无真实固件格式,
 * 仅供策略类型校验(收错包类型 fail fast, 同 vendor 面手法——受限/语义正确的向下转型).
 */
class ZxPackage : FwPackage

/**
 * ZX 固件升级策略(W6 异构验收核心判据, 设计 V2 §3.3): 与 S7"下载 -> 等自复位 -> 扫描回连 -> 读版本"
 * 完全相反的重连行为——**设备不复位, 原连接上直接生效**: 假传输进度回调若干次后直接终态 Success,
 * 全程不碰 [BleClient.connect]/[BleClient.disconnect]. 证明"同一设备 ID 自复位回连"是
 * [io.bluetrace.shared.b2a.B2aFirmwareUpdateStrategy] 的私有假设, 不是
 * [io.bluetrace.shared.device.FirmwareUpdateStrategy] 框架契约的一部分.
 */
class ZxFirmwareUpdateStrategy(
    private val ble: BleClient,
    private val device: ScannedDevice,
    private val versionAfter: String = "zx-2.1",
) : FirmwareUpdateStrategy {

    override suspend fun run(pkg: FwPackage, onProgress: (FwUpdateProgress) -> Unit): FwUpdateResult {
        // 类型不符 fail fast(受限/语义正确的向下转型, 同 vendor 面手法): 本协议策略只可能收
        // ZxPackage(由本协议 loader 产出), 收错=编程错误——ZxPackage 无字段, 确认类型后无需持有实例.
        if (pkg !is ZxPackage) return FwUpdateResult.Failed("非 ZX 升级包(FwPackage 类型不符)", 0)

        onProgress(FwUpdateProgress(FwUpdatePhase.Preparing, 0, 0, "准备中"))
        for (i in 1..STEPS) {
            delay(STEP_DELAY_MS) // 假传输进度: 无真实字节收发, 纯延时模拟耗时
            val percent = i * 100 / STEPS
            onProgress(FwUpdateProgress(FwUpdatePhase.Transferring, percent, FAKE_BYTES_PER_SEC, "写入中 $i/$STEPS"))
        }

        // 原地生效指令(单帧, fire-and-forget, 按 gattSpec 声明的写特征): 与 S7"发指令后等复位"不同,
        // ZX 固件在同一连接上直接切换分区——**本类全程不碰 ble.connect/ble.disconnect**, 不断链不回连.
        ble.write(device.id, byteArrayOf(APPLY_CMD), WRITE_CHAR_16)
        onProgress(FwUpdateProgress(FwUpdatePhase.Applying, 100, 0, "原地生效"))
        onProgress(FwUpdateProgress(FwUpdatePhase.Done, 100, 0, "完成"))
        return FwUpdateResult.Success(versionAfter)
    }

    /** 无传输态门控/无看门狗语义(本协议不复位): 中止即结束, 无需任何协议收尾指令. */
    override suspend fun abort() = Unit

    private companion object {
        const val STEPS = 4
        const val STEP_DELAY_MS = 10L
        const val FAKE_BYTES_PER_SEC = 1_000L
        const val APPLY_CMD: Byte = 0x01
        const val WRITE_CHAR_16 = "AA01"
    }
}

/**
 * ZX 固件升级面工厂: 挂在 [ZxDeviceProfile.firmwareUpdate]. [scope]/[clock]/[zone]/[abortScope]/
 * [reconnectScanMs]/[onLog] 均未使用——ZX 策略无重连编排, 无需回连扫描预算/中止善后 scope,
 * 框架同一工厂签名照样能装(未用到的能力不强加实现负担, 同 [ZxControlPlaneFactory]).
 */
class ZxFirmwareUpdateFactory : FirmwareUpdateFactory {
    override fun create(
        ble: BleClient,
        device: ScannedDevice,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
        abortScope: CoroutineScope,
        reconnectScanMs: Long,
        onLog: (String) -> Unit,
    ): FirmwareUpdateStrategy = ZxFirmwareUpdateStrategy(ble, device)
}
