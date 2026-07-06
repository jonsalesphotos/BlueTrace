# BLE 传输层事实提取

> 出处代号：**[文件2]**=`protocol-analysis/01-ble-protocol.md`；**[文件3]**=`protocol-analysis/manuals/manual-ble-protocol.md`；**[文件4]**=`BLE流程与Cordio主机栈分析.html`。
> **[文件1]** `analyze-ble-protocol.md` 是 `/analyze-ble-protocol` 斜杠命令的任务规格书（GOAL/分工/DONE 条件），**不含任何 BLE 传输层事实**，故下表不引用。
> 所有行号沿用各文档自身标注的源码 `文件:行号`；文档内定位到"[文件x] §节/行N"。

## 0. 栈与链路架构（传输层上下文）

| 事实 | 值 | 出处 |
|---|---|---|
| 主机栈 | Ambiq ExactLE / ARM Cordio（Packetcraft 维护），版本 `tbird_release_sdk_4_3_1`（`svc_amdtp.c:44`） | [文件2] §1；[文件3] §1;§3；[文件4] §1(结论/证据表) |
| 射频架构 | Apollo4L 主控运行 Cordio 主机栈 + 外置 Cooper(Apollo Blue) 控制器（含 LL+PHY），**HCI over SPI(IOM)** 桥接；主机栈在 FreeRTOS `radio_task`/`RadioTask` 初始化 | [文件2] §1；[文件3] §1;§3；[文件4] §2(`am_devices_cooper.h:229` SPI_MODULE) |
| 角色 | 从机/Peripheral 为主；`DmConnMasterInit()`/`L2cMasterInit()` 被 `#if 0`（`radio_task.c:398-417`），主机扫描仅保留骨架 | [文件4] §5;§10 |

---

## 1. GATT 服务 / 特征（生效配置）

生效开关：`__BLE_AMDTPS_SUPPORT__` + `__SKG_S7__` + `USER_FACTORY_GH3X2X_BLE_ENABLE=1`；未定义 `__SKG_S7_BXG__`、`MAC_BLE_USE_OLD_NAME`（[文件2] §5 行200；[文件3] §1 行19）。

### 1.1 AMDTP 主数据服务（128-bit 自定义）

base：`0000xxxx-3C17-D293-8E48-14FE2E4DA212`（厂商私有；`svc_amdtp.h:64-65`，`#if 10` 生效分支；旧 base `0x2760...` 在 `#else` 死分支）。构造宏 `ATT_UUID_AMBIQ_BUILD(part)={base[12B], part(LE16), 0x00,0x00}`（`svc_amdtp.h:68`）。

| 名称 | 完整 UUID | 属性 | 值句柄 | 方向/用途 | 回调 | 出处 |
|---|---|---|---|---|---|---|
| AMDTP Service | `0000FFE0-3C17-D293-8E48-14FE2E4DA212` | Primary Service | `AMDTP_SVC_HDL`=0x0800 | — | — | [文件2] §5.1 行208；[文件3] §5.1 行163（`svc_amdtp.h:71,83`；`svc_amdtp.c:127-134`） |
| RX 特征 | `0000FFE1-3C17-D293-8E48-14FE2E4DA212` | **Write Without Response** | `AMDTPS_RX_HDL`=0x0802 | 手机→表（写下行） | `amdtps_write_cback`→`SYS_BleRecvData`→`B2A_RecvData` | [文件2] §5.1 行209；[文件3] §5.1 行164（`svc_amdtp.c:143-150`；`amdtps_main.c:622-644`） |
| TX 特征 | `0000FFE2-3C17-D293-8E48-14FE2E4DA212` | **Notify** | `AMDTPS_TX_HDL`=0x0804 | 表→手机（notify 上行） | `amdtpsSendData`/`AttsHandleValueNtf` | [文件2] §5.1 行210；[文件3] §5.1 行165（`svc_amdtp.c:159-166`；`amdtps_main.c:248`） |
| TX CCC | `0x2902` | Read/Write CCC（安全级 `DM_SEC_LEVEL_NONE`，开 notify 不要求加密） | `AMDTPS_TX_CH_CCC_HDL`=0x0805 | 控制 TX notify 开关 | `amdtpProcCccState`→`amdtps_start/stop` | [文件2] §5.1 行211；[文件3] §5.1 行166（`svc_amdtp.c:167-174`；`watch_main.c:719`） |
| (ACK 特征 FFEB) | `0000FFEB-3C17-D293-8E48-14FE2E4DA212` | **未启用** | — | — | — | `__BLE_AMDTPS_SUPPORT__` 下整段排除（`svc_amdtp.c:93-97,175-200`）；[文件2] §5.1 行212；[文件3] §5.1 行167 |

- 中间句柄：`AMDTPS_RX_CH_HDL`=0x0801、`AMDTPS_TX_CH_HDL`=0x0803（特征声明句柄），服务句柄基址 `AMDTPS_START_HDL=0x0800`（[文件2] §3.2 行65-74，`svc_amdtp.h:120,125-139`）。
- 写回调注册：`SvcAmdtpsCbackRegister(NULL, amdtps_write_cback)`（read 回调为 NULL），`watch_main.c:1929`（[文件2] 行214；[文件3] 行169）。
- 实际属性表只含 **Service + RX + TX + TX-CCC 四项**（`svc_amdtp.c:125-201`）。

### 1.2 GH3X2X 工厂数据服务（16-bit / 工厂专用，与 B2A 数据面独立）

受 `USER_FACTORY_GH3X2X_BLE_ENABLE` 门控（`watch_main.c:1933`）；句柄基址 `GH3X2X_BLE_START_HDL=0x0A00`。

| 名称 | UUID(16-bit) | 属性 | 值句柄 | 方向/用途 | 出处 |
|---|---|---|---|---|---|
| GH3X2X Service | `0x190E` | Primary Service | 0x0A00 | — | [文件2] §5.2 行222；[文件3] §5.1 行177 |
| RX 特征 | `0x0004` | **[矛盾]** 见下 | 0x0A02 | 命令下行（写） | [文件2] 行223；[文件3] 行178 |
| TX 特征 | `0x0003` | Notify | 0x0A04 | 数据上行 | [文件2] 行224；[文件3] 行179 |
| TX CCC | `0x2902` | Read/Write CCC | 0x0A05 | — | [文件2] 行225；[文件3] 行180 |
| (ACK 0x0005) | `0x0005` | 未启用(`#if 0`) | — | — | [文件2] 行226；[文件3] 行181 |

- **[矛盾] GH3X2X UUID 类型**：
  - [文件2] §3.1/§5.2（行55,218）：**128-bit**，但用标准蓝牙 base `0000xxxx-0000-1000-8000-00805F9B34FB`（等价 16-bit）。
  - [文件3] §5.1（行171-173）：修订为**生效是纯 16-bit UUID**（`factory_gh3x2x_ble_server.h:49-58` 的 `#else` 分支）；128-bit 标准 base 位于 `#if USER_GH3X2X_BLE_UUID128BIT`（`h:19`=0）**死分支**，未编译。数值等价，但活动定义应引 `h:49-58`。
- **[矛盾] GH3X2X RX 属性**：
  - [文件2] 行223："**Write**（命令下行）"。
  - [文件3] 行178：精修为 "**WriteNoRsp | Write**"（`factory_gh3x2x_ble_server.c:49`）。

### 1.3 服务集合（是否加 Core/DIS 等）—— **[矛盾]**

- [文件2] §1(行21) / [文件3] §2(行33)：因 `__BLE_AMDTPS_SUPPORT__` 已定义，`WatchStart()` **未添加**标准 GATT Core / DIS（`SvcCoreAddGroup`/`SvcDisAddGroup` 被 `#if 0`，`watch_main.c:1919-1926`），**仅 AMDTP + GH3X2X 两组**；`custss`/`vole`/独立 `amota` 均未编译/未注册。
- [文件4] §9(行312-320)：列出 AMDTP(服务端)、**ANCS**(客户端)、**HID**、**GATT Core + Service Changed**(默认)、GH3X2X、**DIS/TIPC/ANPC/PASPC/HRPC**(视配置/骨架)。
- 说明：[文件4] 是对 Cordio `watch` 例程的通用分析（生成 2026-06-23）；[文件2]/[文件3] 是对 `dev-en-collect` 实际编译开关的收敛结论。二者服务集合列举不一致，两值并列。

---

## 2. 广播 / 设备名 / 识别方式

分支：`__SKG_S7__` 且 `!MAC_BLE_USE_OLD_NAME`。

| 项 | 事实 | 出处 |
|---|---|---|
| 广播间隔 | `1600 × 0.625ms = 1000ms`，连续广播 duration=0（`watchAdvCfg`，`watch_main.c:121,124`） | [文件2] §3.4 行99；[文件3] §3 行76；[文件4] §10 行331 |
| 广播启动 | `AppAdvStart(APP_MODE_AUTO_INIT)` | [文件4] §4 步骤6 行217 |
| 广播数据 `watchAdvDataDisc` (`watch_main.c:311-339`) | Flags(LE Limited Disc + BR/EDR not supported) + **16-bit UUID 列表**(DIS `0x180A`、AMDTP `0xFFE0/0xFFE1/0xFFE2/0xFFEB`) + 厂商自定义数据(flags `0x21`、device_type `68 39 71 25 81`(overseas)、6 字节 MAC、1 字节 CRC8) | [文件2] §3.5 行114；[文件3] §3 行88 |
| 扫描响应 `watchScanDataDisc` (`watch_main.c:398-406`) | AD 长度字节=18，Local Name = `"SKG WATCH S7-"` + **4 个 ASCII 十六进制字符**（由 `g_BLEMacAddress[1]`、`g_BLEMacAddress[0]` 各转 2 字符；即 2 个 MAC 字节→4 字符，**非"MAC 末 2 字符"**）；源串末 4 字节占位 `0x30 31 32 33` 运行时被覆盖 | [文件2] §3.5 行115；[文件3] §3 行89 |
| 运行时回填 `SDK_AdvDataDiscUpdate()` (`watch_main.c:1478-1509`) | 广播数据填 6 字节 raw MAC(`:1487-1490`)+CRC8(`:1492-1494`)；扫描响应填 4 字节 ASCII hex(`:1502-1503`)。**CRC8 只回填广播数据，扫描响应不含 CRC8** | [文件2] §3.5 行116-119；[文件3] §3 行90 |
| 本机查询设备名 | `SDK_GetDevBleName()`（`watch_main.c:1512-1524`） | [文件2] 行120；[文件3] 行91 |
| 广播名（识别方式） | `SKG WATCH S7-XXXX`（XXXX = MAC[1]/MAC[0] 的 4 位 ASCII hex）；[文件4] 表述为 `"SKG WATCH S7-xxxx"`(`watch_main.c:404`) | [文件2] §8/§3.5；[文件3] §8 行381;§3；[文件4] §9 行323 |
| device_type | overseas=`68 39 71 25 81`；国内版 `...25 80` 被 `#if 0` 关闭(`watch_main.c:332-336`)（推断） | [文件2] 行249；[文件3] §10 行423 |
| CRC8 算法 | Poly=`0x07`, Init=0（`crc16_ccitt_false.c:45-67` `CRC8_check`，调用点 `System.c:2959`），**用于广播/扫描数据(设备名 CRC8)，不参与 B2A 帧** | [文件3] §4.5 行151 |

---

## 3. MTU 协商 / 连接参数 / 配对绑定

| 参数 | 值 | 出处 |
|---|---|---|
| 最大连接数 | 1（`watchSlaveCfg`，`watch_main.c:131-134`） | [文件2] §3.4 行100；[文件3] §3 行77；[文件4] §10 行330 |
| 连接参数更新(期望) `watchUpdateCfg` | min 8(10ms) / max 18(22.5ms) / latency 0 / supTimeout 0 / 5 次尝试；idle=0（生效 `#else` 分支，`#if 0` 的 600/800 为死代码）(`watch_main.c:173-182`) | [文件2] §3.4 行101；[文件3] §3 行78 |
| 连接参数 `watchConnCfg`(hciConnSpec) | min 8 / max 18 / latency 0 / supTimeout 0（`watch_main.c:185-193`）；connInterval 单位 1.25ms | [文件2] §3.4 行102；[文件3] §3 行79;行84 |
| 连接间隔(默认，[文件4] 口径) | 8~18 × 1.25ms ≈ 10~22.5ms（`watchConnCfg`） | [文件4] §10 行332 |
| 连接参数(快速/OTA)（仅 [文件4]） | `WATCH_CONN_PARAM_FAST`：12×1.25=15ms, latency 0, 超时 5s | [文件4] §10 行333 |
| 连接参数(低功耗)（仅 [文件4]） | `WATCH_CONN_PARAM_LOW_POWER`：40~60ms, latency 1, 超时 5s | [文件4] §10 行334 |
| MTU 协商 | **[矛盾]** 见下；目标 MTU=247 | [文件2] §3.4 行103；[文件3] §3 行80 |
| 数据长度扩展(DLE) | 连接打开即 `DmConnSetDataLen(1, 251, 0x848)`（amdtp_main 路径；watch 路径见未决） | [文件2] 行104；[文件3] 行81 |
| HCI 最大 RX ACL | 251（`HciSetMaxRxAclLen(251)`，`radio_task.c:435`） | [文件2] 行105；[文件3] 行82；[文件4] §5;§10 行335 |
| 安全/配对 | **[矛盾]** 见下 | [文件2] §3.4 行106；[文件3] §5.2 行187；[文件4] §10 行336 |
| SMP I/O 能力 | `SMP_IO_NO_IN_NO_OUT`（Just Works，无显示无输入）；keySize 7..16（`watchSmpCfg`，`watch_main.c:199-210,202`）；[文件4] 表述 IO=NoInput/NoOutput | [文件2] 行107；[文件3] 行188；[文件4] §10 行336 |
| TX CCC 安全级别 | `DM_SEC_LEVEL_NONE`（开启 notify 不要求加密）(`watch_main.c:719`) | [文件2] 行108；[文件3] §5.2 行189 |

- **[矛盾] MTU 协商机制**（两值均目标 247）：
  - [文件2] §3.4(行103)：连接打开后由**表端主动 `AttcMtuReq(connId, 247)`** 请求（`amdtp_main.c:945-977,788-801,1029`）；watch 路径未决。
  - [文件3] §3(行80)：`AttcMtuReq(...,247)` **已被注释**(@973/1028)，实际改走 **`SERVC_MSG_REQ_MTU` 服务消息**触发，247 仅作达标阈值校验(@962)；watch 路径仍未决。
  - [文件4] §8(行302)：仅记录 `ATT_MTU_UPDATE_IND`（协商完成，配合 251 字节 ACL 支持大包/OTA），未给具体值。
- **[矛盾] 配对是否 LESC(安全连接)**：
  - [文件2] §3.4(行106) / [文件3] §5.2(行187)：`DM_AUTH_BOND_FLAG`——**仅绑定，未带 SC 安全连接位**；Responder 下发 LTK（`watch_main.c:137-144,139`）。
  - [文件4] §10(行336)：`watchSecCfg`/`watchSmpCfg` = **"Bonding + LESC，LTK 分发，IO=NoInput/NoOutput"**（明确写 LESC）。

---

## 4. GATT 之上第一层成帧：B2A（Ble2App）协议

承载于 AMDTP RX(FFE1 写) / TX(FFE2 notify)。两层结构：外层 8 字节帧头 `B2A_HEAD` + 内层 4 字节命令头 `B2A_DATA_CMD`。

### 4.1 外层帧头 `B2A_HEAD`（固定 8 字节，小端）—— `ble2appEx.h:74-81`；解析 `ble2appEx.c:595-604`；写入 `ble2appEx.c:57-71`(`B2A_MakePktHead`)

| 偏移 | 字段 | 长度 | 含义/取值 | 出处 |
|---|---|---|---|---|
| 0 | `ucStartFlag`(SOF) | 1 | 固定 `0xBB`（`MAC_B2A_HEAD_FLAG`, `ble2appEx.h:20`；校验 `ble2appEx.c:618,626`） | [文件2] §3.3 行84；[文件3] §4.1 行105 |
| 1 | `ucStatus` | 1 | 状态/标志位域 `ENUM_HEAD_STATUS_TYPE`（见 4.3） | [文件2] 行85；[文件3] 行106 |
| 2 | `uiLen`(长度域) | 2 (LE) | **payload 长度，不含 8 字节帧头** | [文件2] 行86；[文件3] 行107 |
| 4 | `uiCRC` | 2 (LE) | CRC16-CCITT-FALSE，**仅覆盖 payload**（帧头之后） | [文件2] 行87；[文件3] 行108 |
| 6 | `uiIndex` | 2 (LE) | 包序号/分片索引 | [文件2] 行88；[文件3] 行109 |
| 8.. | payload | `uiLen` | Cmd/Key/SubLen 起始的命令体 | [文件2] 行89；[文件3] 行110 |

### 4.2 内层命令头 `B2A_DATA_CMD`（4 字节）—— `ble2appEx.h:513-525`；解析 `ble2appEx.c:606-611`

| 偏移(帧头后) | 字段 | 长度 | 含义 | 出处 |
|---|---|---|---|---|
| 8 | `ucCmd` | 1 | 命令字/opcode（`ENUM_B2A_CMD_TYPE`） | [文件2] §3.3 行91；[文件3] §4.2 行117 |
| 9 | `ucKey` | 1 | 子命令/功能键 | [文件2] 行91；[文件3] 行118 |
| 10 | `uiLen` | 2 (LE) | 参数 payload 长度 | [文件3] 行119 |
| 12 | `szParam[0]` | N | 业务参数 | [文件3] 行120 |

- **仅首包（`uiIndex==0`）写命令头**并 `uiBuffLen += sizeof(B2A_DATA_CMD)`（`ble2appEx.c:236-243`）；分片续包只放裸 payload；`B2A_NoCmdPkt`（`ble2appEx.c:487`）完全不带命令头（[文件3] §4.2 行113）。

### 4.3 帧头状态位 `ENUM_HEAD_STATUS_TYPE` —— `ble2appEx.h:26-43`（[文件3] §4.4 行135-146）

| 值 | 名称 | 含义 |
|---|---|---|
| 0x00 | `EHST_SUCC` | 成功 |
| 0x01 | `EHST_FAIL` | 失败（CRC 校验失败时表端回此位，`ble2appEx.c:645-652`） |
| 0x02 (1<<1) | `EHST_ACK` | 需应答（回 CommAck，`ble2appWrap.c:12845`） |
| 0x04 (1<<2) | `EHST_IS_MULTI_PKT` | 多包/分片标志 |
| 0x08 (1<<3) | `EHST_MULTI_PKT_END` | 分片结束 |
| 0x80 (1<<7) | `EHST_OTA_PART` | OTA 续传（`ble2appWrap.c:13165`） |

- **分片多包 ID** = `(ucStatus >> 4) & 0x03`（`ble2appWrap.c:11773`），与缓存 `ucMultiPktId` 比对，不一致丢弃（[文件3] §4.4 行146）。

### 4.4 CRC 与转义

| 项 | 事实 | 出处 |
|---|---|---|
| 帧 CRC | **CRC16-CCITT-FALSE**：Poly=`0x1021`，Init=`0xFFFF`，refin=refout=False，Xorout=0（`crc16_ccitt_false.c:14-42`，封装 `SYS_CRC16_CCITT_FALSE` `System.c:2944-2952`）。范围 `CRC16(pszData+8, uiLen-8)`——**仅 payload，不含 8 字节帧头**（写入 `ble2appEx.c:64`，校验 `ble2appEx.c:644`） | [文件2] §3.3 行87;§5.3 行230；[文件3] §4.5 行150;§4.3 行133 |
| 长度域语义 | `uiLen`(LE16) = payload 字节数（不含帧头）；接收侧按此拼装并做 `uiLen - sizeof(B2A_HEAD)` 计算 | [文件2] 行86-87；[文件3] §4.1 行107 |
| **转义/字节填充** | **各文档均未提及任何转义(escape)/字节填充(byte-stuffing)机制**。成帧靠固定 SOF `0xBB` + 显式长度域 + CRC16 定界，非分隔符转义式 | 三文档全文（[文件2]/[文件3]/[文件4]）无 escape/stuffing 记述（据 [文件3] §4.1-4.3 帧结构判定） |

---

## 5. 文档间矛盾汇总

| # | 事实点 | [文件2]/[文件3] 值 | [文件4] 值 | 备注 |
|---|---|---|---|---|
| 1 | 配对安全等级 | `DM_AUTH_BOND_FLAG`，**仅绑定、无 SC 位**（行106/187） | **Bonding + LESC**（§10 行336） | [文件2]/[文件3] 逐字节确认无 SC；[文件4] 为通用例程描述 |
| 2 | GH3X2X UUID 类型 | [文件2]=128-bit(标准 base)；[文件3]=纯 16-bit(修订) | 未涉及 | [文件2]↔[文件3] 内部亦有修订差异 |
| 3 | GH3X2X RX 属性 | [文件2]="Write"；[文件3]="WriteNoRsp\|Write" | 未涉及 | [文件3] 为精修值 |
| 4 | MTU 协商机制 | [文件2]=直接 `AttcMtuReq(247)`；[文件3]=`AttcMtuReq` 已注释、改走 `SERVC_MSG_REQ_MTU`，247 为阈值 | 仅 `ATT_MTU_UPDATE_IND`，无值 | 目标 MTU 均 247；watch 路径均标未决 |
| 5 | 生效服务集合 | 仅 AMDTP + GH3X2X（Core/DIS 被 `#if 0`） | AMDTP+ANCS+HID+GATT Core+DIS/TIPC/ANPC/PASPC/HRPC+GH3X2X | [文件4] 为 Cordio watch 例程通用视角，[文件2]/[文件3] 为实际编译开关收敛 |