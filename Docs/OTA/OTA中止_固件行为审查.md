# OTA 中止与重启指令：固件行为代码审查

> 2026-07-14，固件仓 `E:\1\apollo4_watch_s7`（9 代理并行审查后人工收敛）。
> 起因：真机 D8F7 传输中手动停止，App 发重启指令（DEV_CTRL 0x07 / RESET 0x02）后 20s 未观测到断链。
> 用户判断"很可能是嵌入式没有处理"——**审查证实，且推翻 App 侧此前"supervision timeout 观测滞后"的猜测**。

## 结论

1. **OTA 传输中（REQ 起）发 DEV_CTRL/RESET，固件不会重启**：`ble2appWrap.c:12021` 在命令 `switch` 之前有一道显式门控——`SYS_GetOtaFlag()>0 && cmd != BCMD_FILE_TRANS(0x0F)` → 直接 `return` 丢弃（不回 BUSY、不排队、不置延迟标志）。重启指令永远到不了 `SYS_Reset()`（`:13147`）。
2. **`SYS_Reset()` 真执行时会主动断链**：`System.c:3478-3488` 先 `B2A_DevNotifyAppOtherCtrl(BOCT_POWER_OFF)` → `AppConnClose(connId)`（发 LL_TERMINATE_IND，对端正常收到断链）→ `SDK_Reset()`（Ambiq `am_hal_reset_control(SWPOI)` 软复位，`MMI_Main.c:659`）。"硬复位不发断链包"的说法不成立。
3. **传输中止后设备真正的复位途径 = 收包空闲看门狗 ≈61s**：定时器周期 `60*1024` ms（`ble2appWrap.c:4662`，`SYS_TimerCreate` 按 `pdMS_TO_TICKS` 计，`System.c:841`），每收一片重置（`:4670-4673`）；超时 → `B2A_OtaTimeoutCb`（`:13850`）→ `UI_MSG_SYSTEM_RESET`（`:13879`）→ `lv_watch.c:3717-3718` `B2A_OtaBeforResetProc(); SYS_Reset();`——此时清 OTA 标志并主动断链。**真机 20s 观测不到断链是因为门限是 61s，不是观测滞后**。
4. ⚠️ **中止时补发 FILE_TRANS_STOP 会把设备卡死**：`B2A_FileTransStopAck`（`ble2appWrap.c:5409-5639`）删除看门狗定时器（`:5423-5428`）、置 `EOTA_IDLE`（`:5599`），但**通篇不调 `SYS_ClrOtaFlag()`**——设备既无 61s 自复位、非传输命令又持续被门控丢弃，长期不复位不断链（只剩 supervision timeout / 物理操作）。App 取消路径天然不发 STOP（协程停在切片循环）——**保持现状，永不"优雅补 STOP"**。
5. 关机 0x01 / 恢复出厂 0x03 与重启 0x02 同分发点（`case BCMD_DEV_CTRL:` `:13137`）、同一道门控、同样 `ucIsOk=0` 强制不回包；`SYS_Restore` 内部走 `SYS_Reset`（`System.c:3608-3609`），`SYS_PowerOff` 的断链块被 `#if 0` 关闭（`System.c:3221-3236`，关机不主动断链）。

## 处理链（file:line）

| 级 | 位置 | 说明 |
| --- | --- | --- |
| BLE 收包 | `amdtps_main.c:707` | FFE1/DTP 写 → `SYS_BleRecvData` |
| 入口分流 | `System.c:3080,3092-3094` | `0xBB` 头 → `B2A_RecvData` |
| 帧解析/CRC | `ble2appEx.c:570,682-685` | → `RecvDataCb` |
| 收包回调入队 | `ble2appWrap.c:194,241,321-332` | 普通命令 `SERVC_MSG_BLE`；传输中 `SERVC_MSG_BLE2`（同队列） |
| **单任务单队列** | `drv_comm.c:516,526,578-607` | 唯一 `sys_service_task` 串行消费两类消息，均入 `B2A_RecvDataHandle` |
| **OTA 门控（根因）** | `ble2appWrap.c:12021-12037` | `SYS_GetOtaFlag()>0 && cmd!=0x0F → return`（日志 "in ota process, return"） |
| DEV_CTRL 分发 | `ble2appWrap.c:13137,13144-13148` | RESET → `SYS_Reset()`，`ucIsOk=0` 不回包 |
| 复位实现 | `System.c:3470,3478-3488,3530` | 断链（AppConnClose）→ `SDK_Reset()` |
| OTA 标志 | `System.c:3716/3722/3743` | REQ 起置位（`lv_watch.c:692` 等）；仅复位前/GUI 收尾清除 |

日志佐证：`apollo4_watch_s7/Docs/log/ota.log:84-97`（OTA 中 Cmd:6 同样被 "in ota process, return" 丢弃——门控对命令码不加区分）。

## App 侧策略（本轮已落码）

| 场景 | 行为 |
| --- | --- |
| **传输中（Downloading）手动停止** | **不发**重启指令（发也被吞）→ 断开本地 GATT → 日志/UI 如实提示"设备约 60s 后由固件看门狗自动重启"（[`OtaAbort`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaAbort.kt) `otaTransferActive=true` 短路） |
| 非传输态停止（连接后未 REQ / 复位重连后） | 发重启指令，`SYS_Reset` 主动断链，观测窗内即见 |
| 任何中止路径 | **永不补发 FILE_TRANS_STOP**（见结论 4；[`S7OtaSession`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7OtaSession.kt) 类注释立此存照） |

## 固件侧改进建议（供固件仓任务线参考，App 不依赖）

- 门控处（`ble2appWrap.c:12021`）对 `BCMD_DEV_CTRL` 的电源类 key 放行（或改"置延迟复位标志、传输收尾后执行"），使 App 中止语义即刻生效；
- `B2A_FileTransStopAck` 补 `SYS_ClrOtaFlag()`（或不删看门狗），消除"STOP 后永久卡传输态"的状态泄漏。
