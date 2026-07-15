package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * 手动停止 OTA 的设备善后（固件行为已代码审查坐实，2026-07-14，证据见
 * `Docs/OTA/OTA中止_固件行为审查.md`；固件仓 apollo4_watch_s7）：
 *
 * - **OTA 传输中（REQ 起 `SYS_GetOtaFlag()>0`）固件丢弃一切非 FILE_TRANS 命令**——
 *   `ble2appWrap.c:12021` 门控直接 return，重启指令(0x07/0x02)到不了 `SYS_Reset()`(:13147)，
 *   不回包、不排队。**传输中发重启指令无效，本层直接跳过不发**。
 * - 传输中止的正确姿势 = **停发切片 + 断开本地连接**，固件收包空闲看门狗（60×1024ms ≈ **61s**，
 *   `ble2appWrap.c:4662` + `System.c:841`）超时后自行 `SYS_Reset()`——复位前会 `AppConnClose()`
 *   主动断链（`System.c:3478-3488`）。
 * - ⚠️ **中止时绝不可发 FILE_TRANS_STOP**：`B2A_FileTransStopAck`(:5409) 会删除看门狗定时器
 *   且**不清 OTA 标志** → 设备既不自复位、其他命令又被门控——永久卡传输态。
 *   （当前取消路径天然不发 STOP：协程在切片循环中被取消。此注为防未来"优雅停止"改造踩雷。）
 * - 非传输态（未 REQ / 设备复位重连后）OTA 标志为 0，重启指令正常生效且 `SYS_Reset` 主动断链。
 */
object OtaAbort {
    /**
     * 手动停止善后。[otaTransferActive]=true（下载阶段中止）时**不发**重启指令（固件门控会吞），
     * 返回 null 并由调用方断开本地连接、提示看门狗自复位；false 时链路仍在则发送重启指令。
     * @return true=已发送且观测到断链（生效）；false=已发送但窗口内未断链；null=未发送（未连接或传输态跳过）。
     */
    suspend fun rebootAfterManualStop(
        ble: BleClient,
        deviceId: String,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
        otaTransferActive: Boolean = false,
    ): Boolean? {
        if (otaTransferActive) return null // 固件 OTA 门控：传输中发了也被丢弃，不发
        if (ble.linkState(deviceId).value != LinkState.CONNECTED) return null
        val console = S7Console(ble, deviceId, scope, clock, zone)
        console.start()
        return try {
            console.sendPowerCommand(S7.CTRL_RESET)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            false // 善后尽力而为：发送环节异常不上抛（调用方只看结果打日志）
        } finally {
            console.stop()
        }
    }
}
