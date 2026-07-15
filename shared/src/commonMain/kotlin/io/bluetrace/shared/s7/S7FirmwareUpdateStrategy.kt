package io.bluetrace.shared.s7

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
import kotlinx.coroutines.launch

/**
 * S7 采集固件升级策略(设计 V2 §3.3): 把现有 S7 OTA 编排整链包成通用 [FirmwareUpdateStrategy].
 *
 * **本类只做类型皮 + 映射层**——真机验证过的编排逻辑原样保留在 [OtaProvisioner]/[S7OtaSession]/[OtaAbort]
 * (一行不改), 本类构造它们并做:
 * - [run]: 委托 [OtaProvisioner.provisionAndReconnect](下载/等自复位/扫描优先回连 60s 预算/读版本),
 *   细 [OtaPhase]->粗 [FwUpdatePhase] 映射, 细文案塞 [FwUpdateProgress.detail], 终态 [OtaResult]->[FwUpdateResult];
 * - [abort]: 委托 [OtaAbort.rebootAfterManualStop], 传输态由本类内部 [otaTransferActive] 自持.
 *
 * "同一设备 ID 自复位回连"从此是 **S7 策略的私有假设**(Bootloader 换地址/换服务/需激活命令/双分区确认的
 * 设备各写各的策略). 版本读法(短命 [S7Console].getDeviceInfo().swVer)也内迁本策略私有.
 *
 * [onOtaPhase]/[onOtaProgress]: S7 细粒度进度观察(消费方=多设备队列壳把 OtaPhase/OtaProgress 塞回队列项供
 * 现有 UI 直接消费). 通用工厂 [FirmwareUpdateFactory] 不带这两个(OtaPhase/OtaProgress 是 S7 专属类型),
 * 只能经本构造注入; 通用消费方走 [run] 的 [FwUpdateProgress] 回调.
 */
class S7FirmwareUpdateStrategy(
    private val ble: BleClient,
    private val device: ScannedDevice,
    /** 升级链 scope(session 收帧 collector / 读版本会话). */
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    /** 中止善后 scope: 须比 UI 壳长寿(退屏也发得出重启指令); 测试可与 [scope] 同一个. */
    private val abortScope: CoroutineScope,
    /** 回连扫描预算(产品下限 60s, 工程配置只可调大). */
    private val reconnectScanMs: Long = 60_000,
    /** 操作日志汇(session/prov 逐行 + 中止善后); 默认丢弃. */
    private val onLog: (String) -> Unit = {},
    /** S7 细阶段观察(消费方更新 UI 队列项 phase); 默认丢弃. */
    private val onOtaPhase: (OtaPhase) -> Unit = {},
    /** S7 细进度观察(消费方更新 UI 队列项 progress); 默认丢弃. */
    private val onOtaProgress: (OtaProgress) -> Unit = {},
) : FirmwareUpdateStrategy {

    // 传输态自持(= 细阶段处于 Downloading): abort() 据此判固件门控是否吞重启指令.
    // 由 run() 内的 onPhase 写, abort() 读; 生产/测试均在 cancelAndJoin 屏障后读, 有 happens-before.
    private var otaTransferActive = false

    // 进度映射用的滚动值: 非下载阶段(等复位/回连/读版本)无字节进度, 沿用最后一次下载百分比/速度.
    private var lastPercent = 0
    private var lastBytesPerSec = 0L

    /**
     * 跑完整 S7 OTA: 下载 -> 等自复位 -> 扫描优先回连 -> 读版本. 返回 [FwUpdateResult].
     * [pkg] 须为 [OtaPackage](S7 loader 产出); 类型不符 fail fast(受限向下转型, 收错=编程错误).
     */
    override suspend fun run(pkg: FwPackage, onProgress: (FwUpdateProgress) -> Unit): FwUpdateResult {
        val otaPkg = pkg as? OtaPackage
            ?: return FwUpdateResult.Failed("非 S7 OTA 包(FwPackage 类型不符)", 0)

        val session = S7OtaSession(ble, device.id, scope, clock)
        val provisioner = OtaProvisioner(
            session, ble, device,
            readVersion = { readVersionOnce() },
            reconnectScanMs = reconnectScanMs,
        )
        // 转发内部编排日志(session="· "前缀, prov="» "前缀), 与旧多设备壳逐字一致; run 结束即停.
        val logJobs = listOf(
            scope.launch { session.opLog.collect { onLog("· $it") } },
            scope.launch { provisioner.opLog.collect { onLog("» $it") } },
        )
        val emitFw = onProgress // 别名: 避开与 provisionAndReconnect 具名实参 onProgress 的阅读歧义
        return try {
            val result = provisioner.provisionAndReconnect(
                otaPkg,
                onPhase = { p ->
                    otaTransferActive = (p == OtaPhase.Downloading)
                    onOtaPhase(p) // S7 细阶段 -> 消费方 UI 队列项
                    emitFw(FwUpdateProgress(coarse(p), lastPercent, lastBytesPerSec, detailOf(p)))
                },
                onProgress = { pr ->
                    lastPercent = if (pr.totalBytes > 0) (pr.sentBytes * 100 / pr.totalBytes).toInt() else 0
                    lastBytesPerSec = pr.bytesPerSec // OtaProgress.bytesPerSec 透传
                    onOtaProgress(pr) // S7 细进度 -> 消费方 UI 队列项
                    emitFw(FwUpdateProgress(FwUpdatePhase.Transferring, lastPercent, pr.bytesPerSec, pr.fileName))
                },
            )
            mapResult(result)
        } finally {
            logJobs.forEach { it.cancel() }
        }
    }

    /**
     * 手动中止的 S7 设备善后(**本波最高危项**, 红线整段委托 [OtaAbort.rebootAfterManualStop]):
     * - 传输态([otaTransferActive]=Downloading): 固件 OTA 门控丢弃一切非 FILE_TRANS 命令
     *   (`ble2appWrap.c:12021` 显式 return, 证据见 `Docs/OTA/OTA中止_固件行为审查.md`)——**不发**重启指令,
     *   靠固件 61s 收包看门狗自复位; **永不补发 FILE_TRANS_STOP**(会删看门狗又不清 OTA 标志 -> 永久卡传输态);
     * - 非传输态且 CONNECTED: 发 CTRL_RESET, 固件 `SYS_Reset` 主动断链.
     *
     * **严禁**用 W3 的 `PowerOps.reboot()` 替换——PowerOps 不认识传输态门控. 断链归调用方(壳里 disconnect).
     */
    override suspend fun abort() {
        val outcome = OtaAbort.rebootAfterManualStop(
            ble, device.id, abortScope, clock, zone, otaTransferActive = otaTransferActive,
        )
        onLog(
            when (outcome) {
                true -> "手动停止 -> 已发送重启指令, 设备已断链复位"
                false -> "手动停止 -> 已发送重启指令, 未观测到断链"
                null -> if (otaTransferActive) {
                    "手动停止 -> 传输中固件屏蔽重启指令(OTA 门控), 断开连接; 设备约 60s 后由固件看门狗自动重启"
                } else {
                    "手动停止 -> 设备未连接, 跳过重启指令"
                }
            },
        )
    }

    /** 短命 [S7Console] 读软件版本(注入给 [OtaProvisioner] 重连后读版本; 失败抛, provisioner 侧容错转 null). */
    private suspend fun readVersionOnce(): String? {
        val c = S7Console(ble, device.id, scope, clock, zone)
        c.start()
        return try {
            c.getDeviceInfo().swVer
        } finally {
            c.stop()
        }
    }

    /** 终态映射: Reconnected->Success(带版本), Failed->Failed(人话原因 + 失败时进度). */
    private fun mapResult(r: OtaResult): FwUpdateResult = when (r) {
        is OtaResult.Reconnected -> FwUpdateResult.Success(r.currentVersion)
        is OtaResult.Failed -> FwUpdateResult.Failed(r.reason.describe(), lastPercent)
        // provisionAndReconnect 不返 DoneDownload(内部已并入回连链); 防御分支.
        OtaResult.DoneDownload -> FwUpdateResult.Failed("未重连(下载已收讫)", lastPercent)
    }

    /** 细 [OtaPhase] -> 粗 [FwUpdatePhase]. */
    private fun coarse(p: OtaPhase): FwUpdatePhase = when (p) {
        OtaPhase.Downloading -> FwUpdatePhase.Transferring
        OtaPhase.WaitingReboot, OtaPhase.Scanning, OtaPhase.Reconnecting -> FwUpdatePhase.Applying
        OtaPhase.ReadingVersion -> FwUpdatePhase.Verifying
        OtaPhase.Done -> FwUpdatePhase.Done
    }

    /** 细阶段文案(塞 detail; 与多设备 UI 的 OtaPhase.label() 同文案). */
    private fun detailOf(p: OtaPhase): String = when (p) {
        OtaPhase.Downloading -> "下载中"
        OtaPhase.WaitingReboot -> "等待复位"
        OtaPhase.Scanning -> "扫描回连"
        OtaPhase.Reconnecting -> "重连中"
        OtaPhase.ReadingVersion -> "读取版本"
        OtaPhase.Done -> "完成"
    }
}

/**
 * S7 固件升级面工厂(设计 V2 §3.3, 对称 W3 的 [S7ControlPlaneFactory]): 挂在 [S7DeviceProfile.firmwareUpdate].
 * 造无 S7 细进度观察(onOtaPhase/onOtaProgress 默认丢弃)的策略, 供**通用消费方**(走 [FwUpdateProgress]).
 * 需 S7 细进度的消费方(多设备队列壳)直接构造 [S7FirmwareUpdateStrategy] 注入那两个回调.
 */
class S7FirmwareUpdateFactory : FirmwareUpdateFactory {
    override fun create(
        ble: BleClient,
        device: ScannedDevice,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
        abortScope: CoroutineScope,
        reconnectScanMs: Long,
        onLog: (String) -> Unit,
    ): FirmwareUpdateStrategy =
        S7FirmwareUpdateStrategy(
            ble, device, scope, clock, zone, abortScope,
            reconnectScanMs = reconnectScanMs,
            onLog = onLog,
        )
}
