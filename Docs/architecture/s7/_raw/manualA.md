# B2A 协议事实提取（上半部：manual-b2a-complete.md 行 1–1400）

> 来源文件：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\manual-b2a-complete.md`。以下「行号」均指该手册行号；「文件:行号」为手册内引用的固件源码位置，原样保留。

---

## 一、协议分层与信封结构

### 1. 传输通道（手册 §1，行 9–39）

| 通道 | 载体 | 收/发路径 | 承载 B2A 帧 |
|---|---|---|---|
| A. BLE AMDTP（主通道） | 收：GATT RX 特征 **0xFFE1**（Write Without Response，句柄 0x0802）；发：TX 特征 **0xFFE2**（Notify，句柄 0x0804） | 收 `amdtps_write_cback`→`SYS_BleRecvData`；发 `SYS_BleSendData`→`amdtpsSendData`→`AttsHandleValueNtf` | 是 |
| B. 工厂 UART0 | TX=GPIO0/RX=GPIO2，**921600 8-N-1** | 工厂模式 `factory_uartmode_flg==1` 时首字节 0xBB 的帧直接注入 `SYS_BleRecvData()` | 是（与 BLE 完全同帧） |
| C. 4G/AMUX（ASR3603） | SPI-TTY 上 GSM 0710 MUX | `amux_*` | **否**（另一套协议，仅上传健康数据内容，见 manual-4g-protocol.md） |

- 所有通道共用同一解析入口 `SYS_BleRecvData()`（行 11）。
- **进入产测的握手帧（HEX，原样抄录，行 22）**：`BB 02 03 00 2D 46 00 00 08 01 01`（`pc_factory_mode_main.c:1480`）。UART0 上 AT 字符串协议（`AT*PROD=`/`AT*HWTEST=`）与 B2A 并行，按首字节区分（`AT` vs `0xBB`）。

### 2. 帧格式（手册 §2，行 43–97）

每帧 = **8 字节外层帧头 `B2A_HEAD`** +（仅首包）**4 字节内层命令头 `B2A_DATA_CMD`** + payload。**全部多字节字段小端 LE**。定义 `ble2appEx.h:74-81/513-525`；组帧 `ble2appEx.c:57-71`；解析 `ble2appEx.c:595-604`。

外层帧头 `B2A_HEAD`（8 B）：

| 偏移 | 字段 | 长 | 序 | 含义 |
|---|---|---|---|---|
| 0 | ucStartFlag | 1 | — | SOF 固定 **0xBB**（`MAC_B2A_HEAD_FLAG`） |
| 1 | ucStatus | 1 | — | 状态位域（见下） |
| 2–3 | uiLen | 2 | LE | **帧头之后的字节数**：首包 = 4(命令头)+paramLen；续包 = 裸 payload 长 |
| 4–5 | uiCRC | 2 | LE | CRC16-CCITT-FALSE，覆盖偏移 8 起的 uiLen 字节（不含帧头） |
| 6–7 | uiIndex | 2 | LE | 包序号/分片索引（首包/独立包通常 0） |

内层命令头 `B2A_DATA_CMD`（4 B，仅 `uiIndex==0` 首包携带；续包为裸 payload，命令字由缓存 `ucLastCmd/ucLastKey` 延续，`ble2appWrap.c:11784-11785`）：

| 整帧偏移 | 字段 | 长 | 序 | 含义 |
|---|---|---|---|---|
| 8 | ucCmd | 1 | — | 命令字 opcode（`ENUM_B2A_CMD_TYPE`, `ble2appEx.h:85-104`） |
| 9 | ucKey | 1 | — | 子命令 `BKEY_*` |
| 10–11 | uiLen(paramLen) | 2 | LE | 参数 payload 长度 |
| 12.. | szParam[] | N | — | 业务参数 |

首包整帧布局（行 71–79）：

```
偏移: 0    1      2-3       4-5       6-7      8     9     10-11      12...
     |0xBB|status| uiLen   | uiCRC16 | uiIndex| Cmd | Key | paramLen| param ... |
     CRC16 覆盖范围 = [偏移8 .. 8+uiLen-1]；uiLen = 4 + paramLen（首包）
```

状态位 `ucStatus`（`ENUM_HEAD_STATUS_TYPE`, `ble2appEx.h:26-43`，行 81–91）：

| 位/值 | 名称 | 含义 |
|---|---|---|
| 0x00 | EHST_SUCC | 成功 |
| 0x01 | EHST_FAIL | 失败（CRC 校验失败时手表回此位作被动 NAK，`ble2appEx.c:645-652`） |
| 0x02 (1<<1) | EHST_ACK | 需应答 |
| 0x04 (1<<2) | EHST_IS_MULTI_PKT | 多包/分片标志 |
| 0x08 (1<<3) | EHST_MULTI_PKT_END | 分片结束 |
| 0x80 (1<<7) | EHST_OTA_PART | OTA 续传标志（FILE_TRANS 时判断，`ble2appWrap.c:13166`） |
| bit[5:4] | 多包 ID | `(ucStatus>>4)&0x03`，分片序列号，与缓存 `ucMultiPktId` 比对 |

校验（行 93–96）：
- **CRC16-CCITT-FALSE**：Poly=0x1021，Init=0xFFFF，refin=refout=False，Xorout=0（`crc16_ccitt_false.c:14-42`）。仅覆盖偏移 8 起的 uiLen 字节。
- **CRC8**（Poly=0x07/Init=0）：仅用于广播/扫描数据（设备名 CRC8），**不参与 B2A 帧校验**。

### 3. 分包/重组规则（手册 §4.2，行 162–179）

- 单包：status 无 EHST_IS_MULTI_PKT，首包带命令头，直接处理。
- 分片：首片置 EHST_IS_MULTI_PKT，缓存 `ucMultiPktId/ucLastCmd/ucLastKey`；续片同 ucMultiPktId、index 递增、裸 payload（无命令头）；末片置 EHST_MULTI_PKT_END 后重组交业务。
- `ucMultiPktId` 不符 → 整片丢弃（`ble2appWrap.c:11775-11778`）；进入分片分支需 EHST_IS_MULTI_PKT 置位且缓存 `ucMultiPktId != EHST_MAX`（`:11771`）。
- 发送侧分包：超过单包上限按 `iMaxParamPktLen` 切分同一 payload，同 Cmd/Key、index 递增，最后一包带 EHST_MULTI_PKT_END（行 417、837）。

### 4. MTU 约束

上半部**未给出显式 MTU 数值**；单包 payload 上限由变量 `iMaxParamPktLen` 决定（行 417、837：GET_DIAL_INFO 中「单包内塞 `uiPktNum = iMaxParamPktLen/20` 个自定义项」）。特例：Cmd=0x04(PUSH) Key=0x02(MSG) 只校 SOF，长度可超长/分片（`ble2appEx.c:616-623`，行 115）。

---

## 二、解析与交互时序（手册 §3，行 100–153）

接收入口 `B2A_RecvData`（`ble2appEx.c:555-673`）流程：
1. 解析 8B 帧头 + 4B 命令头（逐字节小端拼装）。
2. 特例：Cmd=0x04/Key=0x02 只校 SOF。
3. SOF != 0xBB 或长度不符 → `TransDataCb` 透传（非 B2A 帧）。
4. CRC 不符 → 回 `B2A_MakePkt(cmd,key,NULL,0,EHST_FAIL,index)` 作 **NAK**，丢弃。
5. `status & EHST_FAIL` → 标记失败返回；`status & EHST_ACK` → 标记需应答。
6. 异步投递：`B2A_RecvDataCb` → `SYS_Malloc+memcpy` → `SYS_Service_SendMsg(SERVC_MSG_BLE)` 入队，由 `sys_service_task`（`drv_comm.c:512`）出队后 `B2A_RecvDataHandle`（`drv_comm.c:583/603`）二级分发（外层 `switch(ucCmd)` + 内层 `if(ucKey)`，`ble2appWrap.c:11698-13196`）。

**请求-应答配对规则**：
- 应答与请求同 Cmd/Key；发送侧走 `B2A_CommAck(cmd,key,result)` 或专用 `B2A_*Ack` → `B2A_MakePkt`（`ble2appEx.c:216-280`，自动填 SOF/len/CRC/index/命令头）→ Notify FFE2。
- **是否回 Ack 由请求帧头 EHST_ACK 位决定**（多数子命令，如 BOND_DEV/BOND_DEL、SET 全系、PUSH 全系）；少数无条件回复（如 BOND_CANCEL）。
- SET 命令统一规则：handler 返回后 `ucIsOk != 0` 且帧头含 EHST_ACK → 回 `B2A_CommAck(cmd,key,EBEC_SUCC)`（`ble2appWrap.c:12775-12781`），payload=1 字节 result。
- **超时/主动重传机制：上半部未出现任何定义**（仅有 CRC 失败被动 NAK 与多包 ID 不符丢弃）。

**通用错误码 `ENUM_B2A_ERROR_CODE_TYPE`**（`ble2appEx.h:47-71`，行 216）：EBEC_SUCC=0x00, FAIL=0x01, TIMEOUT=0x02, FORMAT=0x03, MEMORY=0x04, NOT_SUPPORT=0x05, PARAM=0x06, BUSY=0x07, LOW_BAT=0x08, NO_DATA=0x09, MD5=0x0A, CRC=0x0B, FATAL=0x0C, FSUM_FAIL=0x0D。
**绑定结果码 `ENUM_BOND_RES_TYPE`**（`ble2appWrap.h:42-51`）：EBRT_SUCC/AGREE=0x00, EBRT_REFUSE=0x01, EBRT_BOND=0x02（已绑定）。
**B2A_CommAck 通用应答体**：1 字节 ucResult（0=成功, 1=失败, 5=不支持；`ble2appWrap.c:403-410`）。

---

## 三、命令字总表（手册 §5，行 183–198）

| Cmd | 名称 | 语义 | 上半部覆盖情况 |
|---|---|---|---|
| 0x01 | BCMD_BOND | 绑定 | 完整（行 207–408） |
| 0x02 | BCMD_GET | App 读设置 | 完整（行 411–896） |
| 0x03 | BCMD_SET | App 写设置 | 完整（行 899–1343） |
| 0x04 | BCMD_PUSH | App→设备推送 | **部分，行 1346 起，在行 1400 处截断，续见下半部** |
| 0x05 | BCMD_IND | 指示/控制确认 | 续见下半部 |
| 0x06 | BCMD_RPT_DATA | 同步日常健康数据 | 续见下半部 |
| 0x07 | BCMD_DEV_CTRL | 设备控制 | 续见下半部 |
| 0x08 | BCMD_TEST | 工厂测试 | 续见下半部 |
| 0x0F | BCMD_FILE_TRANS | 文件传输/OTA | 续见下半部 |
| 0x86 | BCMD_RPT_DATA2 | (0x80\|RPT_DATA)，与 0x06 合并 case | 续见下半部 |

---

## 四、逐命令细节

### BCMD_BOND (0x01)（行 207–408）

BKEY：REQ=0x01, DEV=0x02, DEL=0x03, CANCEL=0x04, KEYCODE=0x05, REQINFO_GET=0x06, REQINFO_RESET=0x07（`ble2appEx.h:123-132`）。

**0x01 BKEY_BOND_REQ 绑定请求**
- 请求（App→设备）：`[0] ucPhoneType`(1B, 0=Android/1=iOS)、`[1] ucPhoneInfoLen`(1B, 截断上限 31)、`[2..] szPhoneInfo`(变长≤31)。已绑定（NVM_ID_BIN_BOND>0）不解析直接回 EBRT_BOND。
- 应答 `B2A_BondReqAck`：**固定 71 字节** = `[0] ucResult`(1) + `[1] BleMac`(6) + `[7] IMEI`(16) + `[23] ICCID`(20) + `[43] SN`(12) + `[55] AuthCode`(16)。超单包上限按 B2A_MakePkt 分包。内存不足改发 CommAck(EBEC_MEMORY)。

**0x02 BKEY_BOND_DEV 绑定设备**
- 请求：TLV 串接（整块落盘，不在线解析）：`[0] ulUrlLen`(1) + szUrl(变长) + `ulPort`(4, LE) + `bp_res_len`(1)+bp_res + `ecg_res_len`(1)+ecg_res + `user_id_len`(1)+user_id + `comm_res_len`(1)+comm_res。字段拆分来自 `#if 0` 样例，偏移依赖前序变长段累加。
- 应答 `B2A_BondDevAck`：1 字节 ucResult（成功 0x00/已绑定 0x02）；**仅请求帧含 EHST_ACK 时回复**。

**0x03 BKEY_BOND_DEL 解除绑定**：请求无 payload 解析；按 EHST_ACK 回 1 字节 ucResult（固定 0x00）后执行 `SYS_Restore()` 恢复出厂。

**0x04 BKEY_BOND_CANCEL 取消绑定**：请求无 payload；应答 1 字节 ucResult（固定 0x00），**无条件回复**；未绑定时进入下载/配对界面。

**0x05 BKEY_BOND_KEYCODE 鉴权码（能力协商）**
- 请求（最小 20 B）：`[0] Code`(16, 占位未读内容)、`[16] ble_heart`(1)、`[17] daily_ack`(1)、`[18-19] pro_id`(2, LE, App 协议版本)。`uiParamLen<20` 仅回 Ack。
- 应答 `B2A_BondKeyCodeAck` 固定 5 B：`[0] ucResult=0x00`、`[1] ble_heart`(协商=App 支持且设备支持→1)、`[2] daily_ack`(同理)、`[3-4] pro_id`(LE, 设备 DEV_PRO_ID=40=0x0028)。设备能力宏：DEV_SUPPORT_BLE_HEART=1、DEV_SUPPORT_DAILY_ACK=1。

**0x06 BKEY_BOND_REQINFO_GET**：枚举已定义但 handler **未实现**（落入 "NOT SUPPORT" else，不解析不响应）。

**0x07 BKEY_BOND_REQINFO_RESET**
- 请求：同 BOND_DATA_INFO：`[0] ucPhoneType`、`[1] ucPhoneInfoLen`、`[2..] szPhoneInfo`。信息变化→落盘+CommAck(EBEC_SUCC)+重启 BLE 栈（HciDrvRadioShutdown/Boot/DmDevReset）；读旧绑定失败→EBEC_FAIL；**信息未变化不发 Ack**。
- 应答：CommAck 1 字节错误码。

### BCMD_GET (0x02)（行 411–896）

**通用约定**：所有 GET 请求 payload 长度为 0（仅 4B 命令头，Cmd=0x02+Key+paramLen=0）；应答为 `B2A_Get*Ack` 的 szParam；超单包按 iMaxParamPktLen 分包；内存分配失败回 CommAck(EBEC_MEMORY)。BKEY 枚举 `ble2appEx.h:135-178`。

| Key | 名称 | 应答长度 | 应答字段布局（偏移:字段(长)） |
|---|---|---|---|
| 0x01 | GET_DEV_DATE_TIME | 9 B | 0:uiYear(2,LE), 2:ucMonth, 3:ucDay, 4:ucHour, 5:ucMin, 6:ucSec, 7:ucWeek, 8:timezone(有符号字节) |
| 0x02 | GET_DEV_ALARM | 1+N×6 | 0:ucNum(N), 1..:ALARM_DATA_INFO[N]，单条 6B：ucId/ucType/ucHour/ucMinute/ucRepeate(星期位掩码)/ucSnooze。MAX 15 条；读取失败只回 1 字节 ucNum |
| 0x03 | GET_GOALS | 15 B | 0:ulStep(4,LE,默认8000), 4:ulCalorie(4,LE,默认300*1000), 8:ulDistance(4,LE,默认2000), 12:ucStandCount(1,默认8), 13:uiSportTime(2,LE,默认30) |
| 0x04 | GET_PERSON_DATA | 7 B | 0:ucHeight, 1:ucWeight, 2:ucGender, 3:uiBirthYear(2,LE), 5:uiBirthMonth, 6:uiBirthDay |
| 0x05 | GET_WEAR | 1 B | 0:wear（NVM_ID_BIN_WEAR 低字节） |
| 0x06 | GET_UNIT | 6 B | 0:ucHeight, 1:ucWeight, 2:ucDistance(km/mile), 3:ucCalorie, 4:ucTemprator(C/F), 5:ucTime(12/24h) |
| 0x07 | GET_SEDENTARY | 13 B | 0:ucOn, 1:ucRepeate(星期掩码), 2:uiInterval(2,LE,分钟), 4:StartHour, 5:StartMin, 6:EndHour, 7:EndMin, 8:NoonOn, 9:NoonStartHour, 10:NoonStartMin, 11:NoonEndHour, 12:NoonEndMin |
| 0x08 | GET_DRINK_WATER | 13 B | 布局与久坐完全一致 |
| 0x09 | GET_QUIET_MODE | 6 B | 0:ucMode, 1:ucRepeate, 2:StartHour, 3:StartMin, 4:EndHour, 5:EndMin |
| 0x0A | GET_HR_MODE | 7 B | 0:ucMode(关/自动/定时), 1:ucInterval, 2:ucTimeOn, 3:StartHour, 4:StartMin, 5:EndHour, 6:EndMin |
| 0x0B | GET_SPO2 | 7 B | 同 HR_MODE 布局 |
| 0x0C | GET_PRESSURE | 7 B | 同 HR_MODE 布局 |
| 0x0D | GET_HRSO_MODE（抬腕亮屏） | 6 B | 0:ucMode, 1:ucTimeOn, 2:StartHour, 3:StartMin, 4:EndHour, 5:EndMin |
| 0x0E | GET_HR_REMIND | 4 B | 0:ucHighOn, 1:ucHighValue(bpm), 2:ucLowOn, 3:ucLowValue(bpm) |
| 0x0F | GET_SPO2_REMIND | 2 B | 0:ucLowOn, 1:ucLowValue(%) |
| 0x10 | GET_PRESSURE_REMIND | 2 B | 0:ucHighOn, 1:ucHighValue |
| 0x11 | GET_BOOL_REMIND（实为血压提醒） | 4 B | 0:ucHighOn, 1:ucHighValue, 2:ucLowOn, 3:ucLowValue |
| 0x12 | GET_MEDICINE_REMIND | 1+N×6 | 0:ucNum(N,最多5), 单条 6B（推断，需确认）：ucId/ucOnOff/ucHour/ucMinute/ucRepeate/ucSnooze（底层每条 8B 剔除 ucNum+ulReserve） |
| 0x13 | GET_SLEEP_REMIND | 11 B | 0:ucNum(固定1), 1:ucId, 2:bOn, 3:ucTipMin(固定45), 4:ucHour, 5:ucMinute, 6:bOnSleep, 7:ucHour2, 8:ucMinute2, 9:ucRepeate, 10:bSnoose |
| 0x14 | GET_BOOL_TEST_SET（定时血压测量） | 7 B | 0:bOn, 1:ucHour, 2:ucMinute, 3:ucHour2, 4:ucMinute2, 5:ucRepeate, 6:ucSnsTime(测量间隔) |
| 0x15 | GET_ECG_TEST_SET | 5 B | 0:bOn, 1:ucHour, 2:ucMinute, 3:ucRepeate, 4:bSnoose |
| 0x21 | GET_DEV_INFO | 变长 TLV | swVerLen(1)+swVer(ASCII) + mdVerLen(1)+mdVer("1.0") + secVerLen(1)+secVer("1.0") + 固定字节 1(含义待确认) + bpVerLen(1)+bpVer。副作用：置 `g_stFtData.ucOtaMode=0xFF` |
| 0x22 | GET_DEV_FUNC | 4 B | 0:ulDevFunc(4,LE, 32 位功能掩码) |
| 0x23 | GET_DEV_BLE_MAC | 6 B | 0:bleMac(6, 原始顺序，字节序需确认) |
| 0x24 | GET_DEV_VOL | 10 B | 0:uiCapacity(2,LE), 2:uiVol(2,LE,mV), 4:ucPower(%), 5:ucStatus(充电状态), 6:ulLastTime(4,LE,代码未赋值恒为 0) |
| 0x25 | GET_DIAL_INFO | 复杂多包变长 | 首包：currDialId(ASCII, 长度=strlen, **无长度前缀**) + totalCnt(1, 内置+自定义) + 内置表盘列表(每项 ID 字符串+1B 0x00 标志)；后续包：每项自定义表盘 ID 字符串+1B 0x01 标志；单包塞 `iMaxParamPktLen/20` 项，末包带 EHST_MULTI_PKT_END。分隔语义需确认 |
| 0x26 | GET_SN_INFO | 59 B | 0:DevType(5), 5:SN(12), 17:BleMac(6), 23:IMEI(16), 39:ICCID(20)，定长不足补 0 |
| 0x27 | GET_IOS_MSG | 文件大小 | 回传 `MAC_IOS_MSG_REMIND` 配置文件原始字节；文件不存在/空则**不发送响应**。内部布局需对照 SET 命令 |
| 0x28 | GET_BOND | 1 B | 0:bondStatus（NVM_ID_BIN_BOND 低字节，0=未绑定）。注：NVM 取 4 字节但只发最低 1 字节 |
| 0x2A | GET_BLP_RAW_STATE | 5 B | 0:time_per(4,LE, Set 下发周期回显), 4:on_flag(1, App 端血压原始数据保存开关)。0x29 无定义 |

### BCMD_SET (0x03)（行 899–1343）

**通用约定**：多数请求体直接 `memcpy` 入结构体（位域按字节顺序，小端）；应答统一 CommAck 1 字节 result（0/1/5），条件：`ucIsOk!=0` 且帧头含 EHST_ACK。表盘类（DIAL_CURR/SEQ/DEL）与 SCIMEASURE 由专用 Ack 回复。BKEY 枚举 `ble2appEx.h:181-223`。

| Key | 名称 | 请求长度 | 请求字段布局（偏移:字段(长)） | 备注 |
|---|---|---|---|---|
| 0x01 | SET_DEV_DATE_TIME | 9 B | 0:uiYear(2,LE), 2:ucMonth(1-12), 3:ucDay(1-31), 4:ucHour(0-23), 5:ucMin, 6:ucSec, 7:ucWeek, 8:timezone(0=不改时区) | |
| 0x02 | SET_DEV_ALARM | 变长 | 0:lIdx/首字节(闹钟索引 0..14，同时作为数据首字节), 1..:ALARM_DATA_INFO[]（每条 6B：ucId/ucType/ucHour/ucMinute/ucRepeate/ucSnooze） | 首字节双重语义，App 端排布需确认；MAX 15 条 |
| 0x03 | SET_GOALS | 16 B | 0:ulStep(4,LE,500..20000，设备档位=value/1000), 4:ulCalorie(4,LE), 8:ulDistance(4,LE), 12:ucStandCount(1,6..16，stand=value-6), 13:uiSportTime(2,LE,分,5..90，time=(value-5)/5), 15:reserve(1) | |
| 0x04 | SET_PERSON_DATA | 8 B | 0:ucHeight(cm), 1:ucWeight(kg), 2:ucGender, 3:uiBirthYear(2,LE), 5:uiBirthMonth, 6:uiBirthDay, 7:reserve | |
| 0x05 | SET_WEAR | 1 B | 0:wear（佩戴手，通常 0=左 1=右） | |
| 0x06 | SET_UNIT | 8 B | 0:ucHeight, 1:ucWeight, 2:ucDistance, 3:ucCalorie, 4:ucTemprator, 5:ucTime(12/24h), 6:reserve(2,LE) | |
| 0x07 | SET_SEDENTARY | 16 B | 同 GET 13 字段 + 13:reserve(3) | |
| 0x08 | SET_DRINK_WATER | 16 B | 与久坐结构别名，布局一致 | |
| 0x09 | SET_QUIET_MODE | 8 B | 0:ucMode, 1:ucRepeate, 2:StartHour, 3:StartMin, 4:EndHour, 5:EndMin, 6:reserve(2,LE) | |
| 0x0A | SET_HR_MODE | 8 B | 0:ucMode, 1:ucInterval(分, 0→强制 1), 2:ucTimeOn, 3:StartHour, 4:StartMin, 5:EndHour, 6:EndMin, 7:reserve | |
| 0x0B | SET_SPO2 | 8 B | 同 HR_MODE；ucInterval==0→强制 30 | |
| 0x0C | SET_PRESSURE | 8 B | 同 HR_MODE；ucInterval==0→强制 10 | |
| 0x0D | SET_HRSO_MODE | 8 B | 0:ucMode, 1:ucTimeOn, 2:StartHour, 3:StartMin, 4:EndHour, 5:EndMin, 6:reserve(2,LE) | |
| 0x0E | SET_HR_REMIND | 4 B | 0:ucHighOn, 1:ucHighValue(bpm), 2:ucLowOn, 3:ucLowValue(bpm) | |
| 0x0F | SET_SPO2_REMIND | 4 B | 0:ucLowOn, 1:ucLowValue(%), 2:reserve(2,LE) | |
| 0x10 | SET_PRESSURE_REMIND | 4 B | 0:ucHighOn, 1:ucHighValue, 2:reserve(2,LE) | |
| 0x11 | SET_BOOL_REMIND（血压） | 4 B | 0:ucHighOn(收缩压), 1:ucHighValue, 2:ucLowOn(舒张压), 3:ucLowValue | |
| 0x12 | SET_MEDICINE_REMIND | ≤40 B | 每条 8 B：+0 ucNum, +1 ucId, +2 ucOnOff, +3 ucHour, +4 ucMinute, +5 ucRepeate, +6 ucSnooze, +7 reserve；上限 5 条 | |
| 0x13 | SET_SLEEP_REMIND | 12 B | 0:ucNum, 1:ucId, 2:ucOnOff, 3:ucTipMin, 4:ucStartH, 5:ucStartM, 6:ucOn_Off, 7:ucHour, 8:ucMinute, 9:ucRepeate, 10:ucSnooze, 11:reserve（映射 ALARM2/EA2I_SLEEP） | |
| 0x14 | SET_BOOL_TEST_REMIND（定时血压） | 8 B | 0:on_off, 1:hour, 2:minute, 3:sleep_hour, 4:sleep_minute, 5:repeate, 6:snooze(>0 置 bSnoose=1), 7:reserve（映射 EA2I_PRESS） | |
| 0x15 | SET_ECG_TEST_REMIND | 8 B | 0:on_off, 1:hour, 2:minute, 3:repeate, 4:snooze, 5:reserve(3)（映射 EA2I_ECG） | |
| 0x25 | SET_DIAL_CURR | 变长 blob | 不透明块（表盘 ID 等，源码不解析），整段落盘+转 UI | 专用 Ack `B2A_SetDevDialCurrAck` 1 字节 result |
| 0x26 | SET_DIAL_SEQ | 变长 blob，**可分包** | 非分包整段落盘；分包首包 "wb" 覆盖、续包 "ab+" 追加 | 专用 Ack `B2A_SetDevDialSeqAck` 或失败 CommAck(EBEC_FAIL) |
| 0x27 | SET_IOS_MSG | 变长 blob | iOS 各消息源开关配置，整段落盘不解析 | CommAck(EBEC_SUCC) |
| 0x28 | SET_DIAL_DEL | 变长 blob，可分包 | 待删表盘标识；表盘切换中回 EBEC_NOT_SUPPORT 不执行 | 专用 Ack `B2A_SetDevDialDelAck` 或 CommAck(FAIL/NOT_SUPPORT) |
| 0x29 | SET_SCIMEASURE_PARA | ≥1 B | 0:open_flag（科学测量血压 PPG 开关，0=关 非0=开）；其余字段当前未使用 | CommAck=EBEC_SUCC |
| 0x2A | SET_BLP_RAW_MODE | ≥5 B | 0:time_per(4,LE, 时间/时长参数), 4:on_flag(1, 0=关 非0=开，归一化为 1) | CommAck |

### BCMD_PUSH (0x04)（行 1346 起；**本命令在行 1400 处截断，续见下半部**）

BKEY 枚举（`ble2appEx.h:227-239`）：0x01 PHONE（启用）、0x02 MSG（启用）、0x03 WEATHER（启用）、0x04 MUSIC（`#if 0` 关闭）、0x05 CAMERA（`#if 0` 关闭）、0x06 CHK_SMSG（注释关闭）、0x07 MEDICAL_INFO（启用）。

通用响应：仅当帧头含 EHST_ACK 且处理成功（`ucIsOk>0`）才回 `B2A_CommAck(0x04, Key, EBEC_SUCC)`；各 Key 无业务响应 payload。

**0x01 BKEY_PUSH_PHONE 来电/电话状态**（请求 App→设备，`SysDataMgr.c:5298-5328`）：

| 偏移 | 字段 | 长 | 序 | 含义 |
|---|---|---|---|---|
| 0 | ucType | 1 | — | 1=来电, 2=接听, 3=通话中, 4=挂断 |
| 1 | 保留/计数 | 1 | — | 读取后丢弃（原 ucCount） |
| 2 | nameLenth | 2 | LE | 来电名称字节数 |
| 4 | ucName | 变长 | — | 联系人名称（UTF-8） |
| 4+n | operatorLenth | 2 | LE | 号码/运营商字符串字节数 |
| 6+n | ucOperator | 变长 | — | 号码或运营商字符串 |

- 未接来电：type==4 且上一次 type==1 → 置 is_misscall=1，`data[0]` 改写为 **33** 转入消息列表（`SDM_SetMessagePushData`）。

**0x02 BKEY_PUSH_MSG 消息通知**（请求，帧层特例：只校 SOF 可超长/分片）——**该子命令的 payload 表格与后续内容跨越行 1400 截断线，续见下半部**。行 1400 前已知：整段落盘（`MAC_SDM_MSG_PUSH`）+ 透传 UI `UI_EVT_REMIND_START`；内部结构为「类型(1B) + 计数(1B) + 若干 (2B LE 长度 + 内容) TLV」，长度前缀均 2 字节 LE。

以下均**续见下半部**：BKEY_PUSH_MSG 逐字段表与 type 取值、BKEY_PUSH_WEATHER (0x03)、BKEY_PUSH_MUSIC (0x04)、BKEY_PUSH_CAMERA (0x05)、BKEY_PUSH_CHK_SMSG (0x06)、BKEY_PUSH_MEDICAL_INFO (0x07)，以及 BCMD_IND (0x05)、BCMD_RPT_DATA (0x06/0x86)、BCMD_DEV_CTRL (0x07)、BCMD_TEST (0x08)、BCMD_FILE_TRANS (0x0F)。

---

## 五、手册标注的「未找到/需确认」项（上半部）

1. BKEY_GET_MEDICINE_REMIND 单条 6B 字段拆分为推断（代码整体拷贝未逐字段写入）。
2. BKEY_GET_DIAL_INFO 分隔语义（变长 C 字符串 + 1B 标志 0x00/0x01，首包当前表盘 ID 无长度前缀）需与 App 端确认。
3. BKEY_GET_IOS_MSG 配置块内部布局未展开。
4. GET_DEV_BLE_MAC / GET_SN_INFO 各段（MAC/IMEI/ICCID）传输字节序需与 App 核对。
5. GET_DEV_INFO 中间固定字节 1 含义未注明。
6. GET_DEV_VOL 的 ulLastTime 恒为 0（赋值代码被关闭）。
7. BOND_REQINFO_GET (0x06) 枚举已定义但固件未实现。
8. SET 的 DIAL_CURR/DIAL_SEQ/DIAL_DEL/IOS_MSG 四者 payload 为不透明块，字段含义由 UI/App 约定。
9. SET_DEV_ALARM 首字节 index 与记录数组精确排布需结合 App 端确认。
10. 位域结构体未显式 `__packed`，小端目标上布局与表一致，换编译器/字节序需复核。