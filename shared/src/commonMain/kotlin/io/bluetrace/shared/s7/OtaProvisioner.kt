package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OTA 端到端编排: 下载(独占事务) → 等设备自复位 → 自动重连 → 读取并**显示**当前版本 → [OtaResult.Reconnected].
 *
 * 为什么要这层: [S7OtaSession.provision] 只到 [OtaResult.DoneDownload](下载收讫). 设备在末文件 STOP 后
 * 自己做 ResCheck→OTA_SetFlag→跳 SecBL 刷写→SYS_Reset 自复位, **这段 App 观测不到**(协议无生效事件).
 * 本层把"下载后回连 + 读当前版本"闭环起来. 协议细节见 [`Docs/OTA/S7采集OTA_指令交互与流程.md`] §4/§5.
 *
 * **回连 = 扫描优先**(2026-07-14 产品要求: 回连保证扫描至少 60s): 设备复位后重新广播的时刻不可预测
 * (自校验+刷写+开机共 ~20-60s+), 盲直连成功率差. 改为在 [reconnectScanMs] 预算内扫描等目标广播出现,
 * 扫到才发起连接; 连不上回到扫描继续等, 直到预算耗尽. 扫描流不可用(实现不支持/立即完结)时退回
 * 直连重试老路径([reconnectAttempts]), 预算耗尽后也再做一轮直连兜底(设备可能恰在广播间隙).
 *
 * **不做版本校验**(用户 2026-07-08): OTA 包不含版本信息, 无法"包版本 vs 设备版本"机器比对——
 * 回连后**读取并显示**当前版本即可, 是否刷成功由人工看版本判断. 读不到版本不算失败([OtaResult.Reconnected] 带 null).
 *
 * 读版本 [readVersion] 由外部注入(生产接 [S7Console.getDeviceInfo] 的 `swVer`), 使本层与具体读法解耦, Mock 可测.
 * **本层对 [readVersion] 抛异常有容错**([S7Console.getDeviceInfo] 超时/解析失败是 *抛* `S7CommandException`
 * 而非返 null)——[readVersionWithRetry] 捕获非取消异常转 null 重试, 保证 [provisionAndReconnect] 始终返 [OtaResult].
 */
class OtaProvisioner(
    private val session: S7OtaSession,
    private val ble: BleClient,
    private val device: ScannedDevice,
    /** 读设备软件版本(重连后). 生产 = S7Console.getDeviceInfo().swVer; 失败返 null(不抛).  */
    private val readVersion: suspend () -> String?,
    /** 末 STOP 后到设备真正断链(自校验+SetFlag 耗时~5-15s)的最长等待; 观测不到断链**不算失败**(设备可能已快速复位).  */
    private val rebootWaitMs: Long = 90_000,
    /** 单次重连尝试等 CONNECTED 的上限(设备开机耗时~15-30s).  */
    private val reconnectTimeoutMs: Long = 30_000,
    /** 回连扫描预算: 在此窗口内持续扫描等目标广播, 扫到才连(**产品下限 60s**, 配置只可调大).  */
    private val reconnectScanMs: Long = 60_000,
    /** 直连兜底尝试次数(扫描流不可用 / 扫描预算耗尽后走).  */
    private val reconnectAttempts: Int = 5,
    /** 读版本重试次数(刚开机 BLE 栈可能未就绪). 读不到不算失败(返 Reconnected(null)).  */
    private val versionReadRetries: Int = 3,
) {
    val deviceId: String get() = device.id

    private val _opLog = MutableSharedFlow<String>(replay = 32, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<String> = _opLog

    /**
     * 跑完整 OTA: 下载 → 等自复位 → 扫描回连 → 读当前版本. 返回 [OtaResult.Reconnected](重连成功, 带当前版本供显示)
     * / [OtaResult.Failed](下载失败直通 / 回连失败). 读不到版本不算失败(Reconnected(null), UI 显示"未知").
     * @param onPhase 阶段回调(UI 文案) @param onProgress 下载进度回调
     */
    suspend fun provisionAndReconnect(
        pkg: OtaPackage,
        onPhase: (OtaPhase) -> Unit = {},
        onProgress: (OtaProgress) -> Unit = {},
    ): OtaResult {
        onPhase(OtaPhase.Downloading)
        val dl = session.provision(pkg, onProgress)
        if (dl !is OtaResult.DoneDownload) {
            (dl as? OtaResult.Failed)?.let { log("下载失败：${it.reason.describe()}") }
            return dl // 下载失败直通(Failed)
        }

        onPhase(OtaPhase.WaitingReboot)
        log("download done → 等设备自复位(≤${rebootWaitMs}ms, best-effort)")
        val dropped = withTimeoutOrNull(rebootWaitMs) {
            ble.linkState(device.id).first { it != LinkState.CONNECTED }
        } != null
        log(if (dropped) "设备已断链(复位中)" else "未观测到断链(设备或已快速复位), 继续重连")

        if (!reconnect(onPhase)) {
            log("回连失败（扫描 ${reconnectScanMs / 1000}s + 直连 $reconnectAttempts 次均未连上）")
            return OtaResult.Failed(OtaFailure.ReconnectFailed)
        }
        log("重连成功")

        onPhase(OtaPhase.ReadingVersion)
        val version = readVersionWithRetry() // null = 读不到, 不算失败, UI 显示"未知"
        onPhase(OtaPhase.Done)
        log(if (version != null) "重连成功, 当前版本=$version" else "重连成功, 版本读取失败(显示未知)")
        return OtaResult.Reconnected(version)
    }

    private enum class ScanOutcome { CONNECTED, SCAN_UNAVAILABLE }

    /**
     * 回连: 已连返 true; 否则**扫描优先**——[reconnectScanMs] 预算内等目标广播出现再连,
     * 连不上回到扫描继续等; 预算耗尽/扫描不可用 → [directReconnect] 直连兜底.
     */
    private suspend fun reconnect(onPhase: (OtaPhase) -> Unit): Boolean {
        if (ble.linkState(device.id).value == LinkState.CONNECTED) return true

        onPhase(OtaPhase.Scanning)
        log("扫描回连：预算 ${reconnectScanMs / 1000}s, 等目标广播出现…")
        val outcome = withTimeoutOrNull(reconnectScanMs) {
            var sightings = 0
            while (true) {
                // firstOrNull: 扫描流意外完结(实现不支持)时不抛, 转直连兜底; 正常实现持续 emit 不会走到 null
                val sighted = ble.scan().firstOrNull { list -> list.any { it.id == device.id } } != null
                if (!sighted) return@withTimeoutOrNull ScanOutcome.SCAN_UNAVAILABLE
                sightings++
                log("已扫到设备广播(第 $sightings 次) → 连接")
                onPhase(OtaPhase.Reconnecting)
                ble.connect(device) // 失败不抛(BleClient 契约), 以 linkState 收敛为准
                val ok = withTimeoutOrNull(reconnectTimeoutMs) {
                    ble.linkState(device.id).first { it == LinkState.CONNECTED }
                } != null
                if (ok) return@withTimeoutOrNull ScanOutcome.CONNECTED
                log("连接未成功 → 回到扫描")
                onPhase(OtaPhase.Scanning)
            }
            @Suppress("UNREACHABLE_CODE") ScanOutcome.SCAN_UNAVAILABLE
        }
        when (outcome) {
            ScanOutcome.CONNECTED -> return true
            ScanOutcome.SCAN_UNAVAILABLE -> log("扫描流不可用 → 直连重试兜底")
            null -> log("扫描预算耗尽未连上 → 直连兜底（设备可能在广播间隙）")
        }
        onPhase(OtaPhase.Reconnecting)
        return directReconnect()
    }

    /** 直连兜底: connect + 等 CONNECTED, 失败重试至 [reconnectAttempts](扫描不可用时的老路径).  */
    private suspend fun directReconnect(): Boolean {
        repeat(reconnectAttempts) { attempt ->
            if (ble.linkState(device.id).value == LinkState.CONNECTED) return true
            log("reconnect ${attempt + 1}/$reconnectAttempts")
            ble.connect(device)
            val ok = withTimeoutOrNull(reconnectTimeoutMs) {
                ble.linkState(device.id).first { it == LinkState.CONNECTED }
            } != null
            if (ok) return true
        }
        return ble.linkState(device.id).value == LinkState.CONNECTED
    }

    /**
     * 读版本(重试). 容错 [readVersion] 抛异常(生产 [S7Console.getDeviceInfo] 超时/失败会抛 `S7CommandException`):
     * 非取消异常转 null 重试; 取消异常照常上抛(结构化并发). 任一次读到非空即返.
     */
    private suspend fun readVersionWithRetry(): String? {
        repeat(versionReadRetries) {
            val v = try {
                readVersion()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("readVersion 抛异常(容错转 null 重试): ${t.message}")
                null
            }
            if (v != null) return v
        }
        return null
    }

    private fun log(text: String) {
        _opLog.tryEmit(text)
    }
}
