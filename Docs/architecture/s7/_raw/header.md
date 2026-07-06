# S7 手表 B2A(Ble2App) 应用层协议 — 代码级事实源分析

> 依据：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\b2a_protocol.h`（全 2121 行已通读）
> 全局约定（行 6-8）：**所有多字节字段小端 LE**；结构体均 `__attribute__((packed))` **无填充**；帧首包 = `B2A_HEAD(8B) + B2A_DATA_CMD(4B) + payload`，续包 = `B2A_HEAD(8B) + 裸payload`。

---

## 1. 帧/包结构（逐字段布局）

### 1.1 外层帧头 `B2A_HEAD`（固定 8 字节，行 18-24；源 ble2appEx.h:74-81）

| 偏移 | 字段 | 类型 | 字节 | 含义 |
|---|---|---|---|---|
| [0] | `ucStartFlag` | uint8 | 1 | **SOF 同步字/magic，固定 `0xBB`**（`MAC_B2A_HEAD_FLAG`） |
| [1] | `ucStatus` | uint8 | 1 | 状态/标志位域，见 `ENUM_HEAD_STATUS_TYPE`（可按位或） |
| [2-3] | `uiLen` | uint16 LE | 2 | **长度域**：帧头之后字节数（首包=命令头+payload；续包=裸payload） |
| [4-5] | `uiCRC` | uint16 LE | 2 | **校验：CRC16-CCITT-FALSE**，**覆盖范围=偏移 8 起的 `uiLen` 字节**（即帧头之后全部载荷，不含帧头本身） |
| [6-7] | `uiIndex` | uint16 LE | 2 | **包序号/分片索引**（首包=0，续包递增） |

### 1.2 内层命令头 `B2A_DATA_CMD`（4 字节，仅首包 uiIndex==0 携带，行 27-32；源 ble2appEx.h:513-525）

| 偏移 | 字段 | 类型 | 字节 | 含义 |
|---|---|---|---|---|
| [0] | `ucCmd` | uint8 | 1 | 命令字 opcode，见 `ENUM_B2A_CMD_TYPE` |
| [1] | `ucKey` | uint8 | 1 | 子命令/功能键（各命令的 `BKEY_*`） |
| [2-3] | `uiParamLen` | uint16 LE | 2 | 参数 payload 长度 |
| [4..] | `szParam[]` | — | 变长 | 业务参数（各命令 payload 结构体） |

### 1.3 版本字段说明
- 帧层无独立协议版本字段；**协议版本靠握手交换**：
  - `BKEY_BOND_KEYCODE` 请求携带 `pro_id`（APP BLE 协议版本，行 227），响应回 `DEV_PRO_ID=40 (0x0028)`（行 239）。
  - 天气 payload 内含 `ucVersion`（行 1102）；设备信息命令 `GET_DEV_INFO` 内含 sw/modem/secBL/bp 各版本字符串段（行 547-562）。

### 1.4 字节序 / 对齐 / 可变长表示（行 5-8, 汇总）
- **字节序**：所有 `uint16/uint32/int` 多字节字段一律 **小端 LE**（注释统一标 `// LE`）。
- **对齐**：全部 `packed`，无编译器填充；结构体总长即 on-wire 长度。
- **可变长 payload 的三种表示**：
  1. **C 柔性数组** `field[]`（如 `szPhoneInfo[]`、`alarms[]`）；
  2. **长度前缀 TLV**（1 字节或 2 字节 LE 长度 + 变长内容，逐段游走解析，如 BOND_DEV、PUSH_PHONE、PUSH_MSG、天气）；
  3. **占位数组 `field[1]`**（实际长度 = `uiParamLen`，如各 OTA/blob）。
- **多包分片**：由**外层 `ucStatus`** 的 `EHST_IS_MULTI_PKT`/`EHST_MULTI_PKT_END` 驱动；多包 ID 占 `ucStatus` 的 bit[5:4]，`id=(ucStatus>>4)&0x03`（行 46）。续包不带命令头，直接裸 payload 拼接。

---

## 2. 完整命令/消息 ID 表

### 2.1 一级命令字 `ENUM_B2A_CMD_TYPE`（行 50-61；源 ble2appEx.h:85-104）

| 十六进制 | 名称 | 含义 |
|---|---|---|
| `0x01` | `BCMD_BOND` | 绑定 |
| `0x02` | `BCMD_GET` | App 读设备设置 |
| `0x03` | `BCMD_SET` | App 写设备设置 |
| `0x04` | `BCMD_PUSH` | App→设备 推送（来电/消息/天气…） |
| `0x05` | `BCMD_IND` | 设备↔App 指示/控制确认 |
| `0x06` | `BCMD_RPT_DATA` | 同步日常健康数据 |
| `0x07` | `BCMD_DEV_CTRL` | 设备控制（关机/复位/恢复/找表/取日志） |
| `0x08` | `BCMD_TEST` | 工厂测试 |
| `0x0F` | `BCMD_FILE_TRANS` | 文件传输 / OTA |
| `0x86` | `BCMD_RPT_DATA2` | (0x80\|RPT_DATA) 健康数据另一通道，与 0x06 合并处理 |

### 2.2 BOND 子键（BCMD_BOND=0x01；行 92-99；源 ble2appEx.h:123-132）

| Hex | 名称 | 含义 |
|---|---|---|
| `0x01` | `BKEY_BOND_REQ` | 绑定请求 |
| `0x02` | `BKEY_BOND_DEV` | 绑定设备（下发 URL/端口/资源/user_id TLV 串） |
| `0x03` | `BKEY_BOND_DEL` | 解除绑定（触发 `SYS_Restore()` 恢复出厂） |
| `0x04` | `BKEY_BOND_CANCEL` | 取消绑定 |
| `0x05` | `BKEY_BOND_KEYCODE` | 鉴权码 + 能力协商 |
| `0x06` | `BKEY_BOND_REQINFO_GET` | 获取绑定请求信息（**固件未实现**） |
| `0x07` | `BKEY_BOND_REQINFO_RESET` | 重置/更新绑定请求信息 |

### 2.3 GET 子键（BCMD_GET=0x02；源 ble2appEx.h:135-178）

| Hex | 名称 | 含义 | 结构体行 |
|---|---|---|---|
| `0x01` | GET_DEV_DATE_TIME | 日期时间 | 296 |
| `0x02` | GET_DEV_ALARM | 闹钟列表 | 320 |
| `0x03` | GET_GOALS | 运动目标 | 327 |
| `0x04` | GET_PERSON_DATA | 个人信息 | 337 |
| `0x05` | GET_WEAR | 佩戴检测开关 | 348 |
| `0x06` | GET_UNIT | 单位设置 | 354 |
| `0x07` | GET_SEDENTARY | 久坐提醒 | 365 |
| `0x08` | GET_DRINK_WATER | 喝水提醒 | 382 |
| `0x09` | GET_QUIET_MODE | 勿扰模式 | 399 |
| `0x0A` | GET_HR_MODE | 心率监测模式 | 410 |
| `0x0B` | GET_SPO2 | 血氧监测模式 | 422 |
| `0x0C` | GET_PRESSURE | 压力监测模式 | 434 |
| `0x0D` | GET_HRSO_MODE | 抬腕亮屏 | 446 |
| `0x0E` | GET_HR_REMIND | 心率高低提醒 | 457 |
| `0x0F` | GET_SPO2_REMIND | 低血氧提醒 | 466 |
| `0x10` | GET_PRESSURE_REMIND | 高压力提醒 | 473 |
| `0x11` | GET_BOOL_REMIND | 血压提醒（"Bool"实为血压） | 481 |
| `0x12` | GET_MEDICINE_REMIND | 吃药提醒（最多5） | 503 |
| `0x13` | GET_SLEEP_REMIND | 睡眠提醒 | 510 |
| `0x14` | GET_BOOL_TEST_SET | 定时血压测量 | 527 |
| `0x15` | GET_ECG_TEST_SET | 定时ECG测量 | 539 |
| `0x21` | GET_DEV_INFO | **设备版本信息（TLV）** | 547 |
| `0x22` | GET_DEV_FUNC | 功能支持位掩码 | 567 |
| `0x23` | GET_DEV_BLE_MAC | BLE MAC | 575 |
| `0x24` | GET_DEV_VOL | **电量/电池信息** | 581 |
| `0x25` | GET_DIAL_INFO | 表盘信息（多包变长） | 589 |
| `0x26` | GET_SN_INFO | **SN/身份信息** | 610 |
| `0x27` | GET_IOS_MSG | iOS 消息提醒 app 列表 | 623 |
| `0x28` | GET_BOND | 绑定状态 | 629 |
| `0x2A` | GET_BLP_RAW_STATE | 血压原始数据保存状态（0x29未定义） | 636 |

### 2.4 SET 子键（BCMD_SET=0x03；源 ble2appEx.h:181-223）
`0x01..0x15` 与 GET 一一对应（写向），另加：`0x25 SET_DIAL_CURR`（当前表盘）、`0x26 SET_DIAL_SEQ`（表盘排序）、`0x27 SET_IOS_MSG`、`0x28 SET_DIAL_DEL`（删表盘）、`0x29 SET_SCIMEASURE_PARA`（科学测量参数）、`0x2A SET_BLP_RAW_MODE`（血压原始数据保存）。结构体行 675-1015。响应统一 `B2A_SET_COMMACK_RESP_T`（1B，行 671-673）。

### 2.5 PUSH 子键（BCMD_PUSH=0x04）

| Hex | 名称 | 含义 | 状态/行 |
|---|---|---|---|
| `0x01` | PUSH_PHONE | 来电/通话状态 | 行 1045 |
| `0x02` | PUSH_MSG | 消息通知（title/abstract/body TLV） | 行 1063 |
| `0x03` | PUSH_WEATHER | 天气 | 行 1101/1125 |
| `0x04` | PUSH_MUSIC | 音乐 | **DISABLED `#if 0`** 行 1143 |
| `0x05` | PUSH_CAMERA | 相机状态 | **DISABLED `#if 0`** 行 1151 |
| `0x06` | PUSH_CHK_SMSG | 短信检查 | **整块注释掉** 行 1159 |
| `0x07` | PUSH_MEDICAL_INFO | 医疗信息（C 字符串≤64） | 行 1172 |

### 2.6 IND 子键（BCMD_IND=0x05；源 ble2appEx.h:243-262；行 1190-1203）

| Hex | 名称 | 含义 |
|---|---|---|
| `0x01` | IND_INCOMING | 来电通知（0=接听,1=挂断） |
| `0x02` | IND_SCIENTIFICMEASURE | 科学测量/异常事件标志 |
| `0x03` | IND_SPORTS | 运动控制 |
| `0x04` | IND_MUSIC | 音乐控制 |
| `0x05` | IND_CAMERA | 拍照控制 |
| `0x06` | IND_SPORTS_DATA | 运动数据通知 |
| `0x07` | IND_OTHER_CTRL | 其它控制（找手机/天气/充电/**关机**） |
| `0x08` | IND_OTHER_SYMPTOM | 症状/事件上报 |
| `0x09` | IND_SETTING | 设置通知 |
| `0x0A` | IND_HEALTH_DATA_UPDATE | 健康数据更新通知 |
| `0x0B` | IND_ANCS_RECV | ANCS 接收标志 |
| `0x0C` | IND_HEARTBEAT | **心跳** |
| `0x0D` | IND_BLP_RAW_STATE | 血压原始数据保存状态上报 |
| `0x0E` | IND_HEALTH_ALERT | 主动健康告警（海外无4G版替代上传） |

### 2.7 RPT_DATA 子键（BCMD_RPT_DATA=0x06/0x86；源 ble2appEx.h:410-419；行 1390-1391）

| Hex | 名称 | 含义 |
|---|---|---|
| `0x01` | BKEY_RPT_DATA_START | 开始（下发类型 mask） |
| `0x02` | BKEY_RPT_DATA_STOP | 停止 |
| `0x03` | BKEY_RPT_DATA | 数据（请求/传输） |
| `0x04` | BKEY_RPT_DATA_ACK | App 回执 |

### 2.8 DEV_CTRL 子键（BCMD_DEV_CTRL=0x07；源 ble2appEx.h:430-446；行 1794-1795, 1811-1904）

| Hex | 名称 | 含义 |
|---|---|---|
| `0x01` | BKEY_DEV_CTRL_POWER_OFF | **关机**（空 payload，不回包） |
| `0x02` | BKEY_DEV_CTRL_RESET | **复位/重启**（空 payload，不回包） |
| `0x03` | BKEY_DEV_CTRL_RESTORE | **恢复出厂**（空 payload，不回包） |
| `0x04` | BKEY_DEV_CTRL_FIND | 找手表 |
| `0x05` | BKEY_DEV_CTRL_FIND_END | 找手表结束 |
| `0x06` | BKEY_DEV_CTRL_FIND_PHONE_END | 找手机结束 |
| `0x07` | BKEY_DEV_CTRL_FILE_LOG | **拉取日志文件** |
| `0x08` | BKEY_DEV_CTRL_BLE_LOG | 实时 BLE 日志（**DISABLED `#if 0`**） |
| `0x09` | BKEY_DEV_ACK_FILE_LOG | 日志回传块（设备发起） |
| `0x0A` | BKEY_DEV_CTRL_FILE_ALGO | 拉取算法特征文件 |
| `0x0B` | BKEY_DEV_ACK_FILE_ALGO | 算法特征文件回传（设备发起） |

### 2.9 TEST 子键（BCMD_TEST=0x08）
- `0x01` `BKEY_TEST_ENTER_FACTORY_MODE`（进入/退出工厂模式，行 1927）
- `0x02` `BKEY_TEST_MOTO_ON`、`0x03` `BKEY_TEST_MOTO_OFF`（枚举存在但**无 handler，未实现**，行 1941-1951）

### 2.10 FILE_TRANS 子键（BCMD_FILE_TRANS=0x0F；源 ble2appEx.h:451-461；行 1968-1972）

| Hex | 名称 | 含义 |
|---|---|---|
| `0x01` | BKEY_FILE_TRANS_START | 开始单文件 |
| `0x02` | BKEY_FILE_TRANS | 数据块 |
| `0x03` | BKEY_FILE_TRANS_STOP | 结束文件/会话 |
| `0x04` | BKEY_FILE_TRANS_REQ | 传输会话请求 |
| `0x06` | BKEY_FILE_TRANS_OFFSET | 查询续传偏移 |

---

## 3. 全部枚举（状态/错误/类型码）

### 3.1 帧头状态位 `ENUM_HEAD_STATUS_TYPE`（可按位或，行 39-47）

| 值 | 名称 | 含义 |
|---|---|---|
| `0x00` | EHST_SUCC | 成功 |
| `0x01` | EHST_FAIL | 失败（CRC 失败时设备回此位作被动 NAK） |
| `0x02` (1<<1) | EHST_ACK | 需应答 |
| `0x04` (1<<2) | EHST_IS_MULTI_PKT | 多包/分片 |
| `0x08` (1<<3) | EHST_MULTI_PKT_END | 分片结束 |
| `0x80` (1<<7) | EHST_OTA_PART | OTA 续传（差分）标志 |
| — | 多包 ID | 占 bit[5:4]：`id=(ucStatus>>4)&0x03` |

### 3.2 应用层错误码 `ENUM_B2A_ERROR_CODE_TYPE`（行 64-79；源 ble2appEx.h:47-71）

| Hex | 名称 | | Hex | 名称 |
|---|---|---|---|---|
| `0x00` | EBEC_SUCC | | `0x07` | EBEC_BUSY |
| `0x01` | EBEC_FAIL | | `0x08` | EBEC_LOW_BAT |
| `0x02` | EBEC_TIMEOUT | | `0x09` | EBEC_NO_DATA |
| `0x03` | EBEC_FORMAT | | `0x0A` | EBEC_MD5 |
| `0x04` | EBEC_MEMORY | | `0x0B` | EBEC_CRC |
| `0x05` | EBEC_NOT_SUPPORT | | `0x0C` | EBEC_FATAL |
| `0x06` | EBEC_PARAM | | `0x0D` | EBEC_FSUM_FAIL |

### 3.3 绑定结果码 `ENUM_BOND_RES_TYPE`（行 101-102；源 ble2appWrap.h:42-51）
`EBRT_SUCC/EBRT_AGREE=0x00`、`EBRT_REFUSE=0x01`、`EBRT_BOND=0x02`（已绑定）

### 3.4 健康数据类型 `ENUM_RPT_DATA_IDX`（行 1403-1421；源 ble2appEx.h:331-356）

| 值 | 名称 | | 值 | 名称 |
|---|---|---|---|---|
| 0 | RPT_DATA_IDX_HR | | 9 | RPT_DATA_IDX_BP_TREND |
| 1 | RPT_DATA_IDX_SPO2 | | 10 | RPT_DATA_IDX_WEAR |
| 2 | RPT_DATA_IDX_PRESS | | 11 | RPT_DATA_IDX_SSHR |
| 3 | RPT_DATA_IDX_ACTIVITY | | 12 | RPT_DATA_IDX_SHRV |
| 4 | RPT_DATA_IDX_SLEEP | | 13 | RPT_DATA_IDX_SRRI |
| 5 | RPT_DATA_IDX_BREATHING (`#if 0` 停用) | | 14 | RPT_DATA_IDX_SDETECT |
| 6 | RPT_DATA_IDX_SPORT | | 15 | RPT_DATA_IDX_BP_TREND_EX |
| 7 | RPT_DATA_IDX_BLOOD_PRESSURE | | 16 | RPT_DATA_IDX_TEMP_SLEEP |
| 8 | RPT_DATA_IDX_ECG | | | |

（对应 mask：bit n = 1<<idx，行 1440-1442）

### 3.5 IND 动作枚举（行内注释）
- `ENUM_B2A_IND_SPORT_TYPE`（行 1239）：1=READY,2=START,3=PAUSE,4=CONT,5=STOP
- `ENUM_B2A_IND_MUSIC_TYPE`（行 1248）：1=PLAY,2=PAUSE,3=STOP,4=PREV,5=NEXT,6=VOL_U,7=VOL_D
- `ENUM_B2A_IND_CAMERA_TYPE`（行 1257）：1=ENTER,2=EXIT,3=CAP_SINGLE,4=CAP_MULTI
- `ENUM_B2A_OTHER_CTRL_TYPE`（行 1272-1275）：`BOCT_FIND_PHONE=0x01, FIND_PHONE_END=0x02, WEATHER_REQ=0x03, CHARGE_STATUS=0x04, POWER_OFF=0x05, FIND_WATCH_END=0x06, MEDICAL_INFO_REQ=0x07`
- `ENUM_B2A_RPT_DATA_COMM_TYPE`（告警类型，行 1370-1371）：0=HR_HIGH,1=HR_LOW,2=SPO2_LOW,3=GLUSE_ALERT,4=BP_ALERT,5=HR_HEALTH

### 3.6 文件传输/OTA 枚举
- 模块 ID `BMID_*`（行 1990）：1=OTA, 2=GPS, 3=WATCH_BG, 4=MODEM, 5=SECBL, 6=BP
- 请求状态 `BFRS_*`（行 2005）：0=OK, 1=DISK_FULL, 2=BUSY, 3=MEMORY
- 文件类型 `BFTT_*`（行 2028）：1=BOOTLOADER, 2=FW, 3=RES, 7=FONT, 8=WATCH_BG, 9=WATCH_CFG, 10=BP_FW…
- 算法特征文件 `ENUM_B2A_DEV_FEA_FILE_TYPE`（行 1889）：0x00=RPT_FEA_FILE_MIN, 0x01=RPT_FEA_FILE_SLEEP
- 工厂模式选择子（行 1928）：0x00=退出, 0x01=BLE, 0x02=UART, 0x03=apollo, 0x04=ASR, 0x05=ECG

---

## 4. 设备维护相关结构（重点详列）

### 4.1 时间同步

**SET 日期时间 `B2A_SET_DEV_DATE_TIME_REQ_T`**（9B，行 679-688）

| 偏移 | 字段 | 类型 | 含义 |
|---|---|---|---|
| 0-1 | uiYear | uint16 LE | 年 |
| 2 | ucMonth | uint8 | 月 1-12 |
| 3 | ucDay | uint8 | 日 1-31 |
| 4 | ucHour | uint8 | 时 0-23 |
| 5 | ucMin | uint8 | 分 0-59 |
| 6 | ucSec | uint8 | 秒 0-59 |
| 7 | ucWeek | uint8 | 星期 |
| 8 | timezone | uint8 | 时区（**0=保持本地不改**） |

**GET 日期时间 `B2A_GET_DEV_DATE_TIME_RESP_T`**（9B，行 296-305）：同上布局，但末字段 `timezone` 为 **int8 有符号**（`Time_zone.timezone`）。响应固定 9 字节。

### 4.2 设备信息 / 身份

**GET_DEV_INFO (0x21) 设备版本信息 — 变长 TLV，无定长 struct**（行 547-563，源 ble2appWrap.c:2357-2463）
布局：`swVerLen(1B)+swVer(L1 ASCII 数字与'.') | mdVerLen(1B)+mdVer(L2 "1.0") | secVerLen(1B)+secVer(L3 "1.0") | reserved(1B 固定1) | bpVerLen(1B)+bpVer(L4 ASCII 十进制)`。
**副作用**：置 `g_stFtData.ucOtaMode=0xFF`（ble2appWrap.c:2457）。

**GET_SN_INFO (0x26) `B2A_GET_SN_INFO_RESP_T`**（固定 59B=5+12+6+16+20，零填充，行 610-616）

| 偏移 | 字段 | 类型 | 含义 |
|---|---|---|---|
| 0 | DevType[5] | uint8×5 | 设备型号（SYS_GetDevType，原始） |
| 5 | SN[12] | uint8×12 | 序列号（SYS_GetSN） |
| 17 | BleMac[6] | uint8×6 | BLE MAC |
| 23 | IMEI[16] | uint8×16 | IMEI |
| 39 | ICCID[20] | uint8×20 | ICCID |

> 注：BleMac/IMEI/ICCID 原始填充，**字节序（正/反序）需与 App 端确认**（行 573-574, 609）。

**GET_DEV_FUNC (0x22)**：`uint32 ulDevFunc` LE 功能支持位掩码（4B，行 567-569）。
**GET_DEV_BLE_MAC (0x23)**：`uint8 bleMac[6]`（6B，行 575-577）。
**GET_BOND (0x28)**：`uint8 bondStatus`（1B，行 629-631）。

### 4.3 电量 / 电池

**GET_DEV_VOL (0x24) `B2A_GET_DEV_VOL_RESP_T`**（固定 10B，行 581-587，源 ble2appWrap.c:2606-2696）

| 偏移 | 字段 | 类型 | 含义 |
|---|---|---|---|
| 0-1 | uiCapacity | uint16 LE | 电池容量 |
| 2-3 | uiVol | uint16 LE | 电池电压 (mV) |
| 4 | ucPower | uint8 | 电量百分比 |
| 5 | ucStatus | uint8 | 充电状态 |
| 6-9 | ulLastTime | uint32 LE | 最近更新时间戳（代码未赋值，实测 0） |

**心跳带电量 `B2A_IND_HEARTBEAT_RESP_T`**（8B，行 1334-1339；布局注释 "utc(4)+seq(2)+battery(1)+reserved(1)"）：`uint32 utc(LE) | uint16 seq(LE 心跳序号自增) | uint8 battery(SYS_GetBatPower) | uint8 reserved=0`。App 应答 `B2A_IND_HEARTBEAT_REQ_T`（8B，行 1344-1349）：utc(4)+seq(2)+network_status(1)+reserved(1)，**设备侧仅置 recvData 标志、不逐字节解析**。

### 4.4 用户/个人信息

**GET_PERSON_DATA (0x04) `B2A_GET_PERSON_DATA_RESP_T`**（7B，行 337-344）：`ucHeight(1) | ucWeight(1) | ucGender(1) | uiBirthYear(uint16 LE) | uiBirthMonth(1) | uiBirthDay(1)`。
**SET_PERSON_DATA (0x04) `B2A_SET_PERSON_DATA_REQ_T`**（8B，行 728-736）：同上 + 末尾 `ulReserve(1)`；身高 cm、体重 kg。
**运动目标 SET `B2A_SET_GOALS_REQ_T`**（16B，行 715-722）：`ulStep(u32 LE 500..20000, 设备存 value/1000) | ulCalorie(u32) | ulDistance(u32) | ucStandCount(1, 6..16, 存 value-6) | uiSportTime(u16 LE, 5..90, 存 (value-5)/5) | ulReserve(1)`。GET 对应固定 15B（行 327-333）。

### 4.5 日志（拉取）

**FILE_LOG 请求 `B2A_DEV_CTRL_FILE_LOG_REQ_T`**（行 1821-1824，源 ble2appWrap.c:11329…；示例帧 `01 00 00 00 00`）

| 偏移 | 字段 | 类型 | 含义 |
|---|---|---|---|
| 0 | ucModel | uint8 | 日志类型选择：**==1 拉文件日志（now+bak）**；!=1 走 hardfault 分支（dump 被 `#if 0` 静音） |
| 1.. | szPassthru[] | 变长 | 透传扩展参，固件不解析，原样回填每个回传块头（示例 `00 00 00 00`，含义需确认） |

**日志回传块 `B2A_DEV_CTRL_ACK_FILE_LOG_RESP_T`**（Cmd/Key 固定 0x07/0x09，行 1837-1847）：`szReqPrefix[]`（=请求 payload 逐字节副本 uiInParamLen，作块头供 App 识别）**紧接** `szLogChunk[]`（变长 logChunkLen 原始日志片段，来自 PSRAM，每块 ≤ `sizeof(pszParam)-8-uiInParamLen`）。块**无显式 offset/seq，App 按到达顺序拼接**，块边界仅由帧头 len 定义。`uiParamLen=uiInParamLen+logChunkLen`。
**hardfault 特殊包**（行 1859-1869）：**DEAD CODE**，dump 抽取 `#if 0`，totalLen 恒 0 提前 break，源自身不一致，**勿据此实现解析**。
**BLE_LOG (0x08)**：`#if 0` 编译掉，落入 "NOT SUPPORT"，无 struct（行 1871-1878）。
**FILE_ALGO (0x0A) 请求 `B2A_DEV_CTRL_FILE_ALGO_REQ_T`**（行 1888-1891）：`ucFeatureFileType(1B, 0=MIN/1=SLEEP) | szPassthru[]`；响应 0x0B `szEcho[]` 默认原样回显（行 1901-1903）。

### 4.6 OTA / 文件传输

**FILE_TRANS_REQ (0x04) 会话请求 `B2A_FILE_TRANS_REQ_REQ_T`**（8B，行 1989-1995）

| 偏移 | 字段 | 类型 | 含义 |
|---|---|---|---|
| 0 | ucModuleId | uint8 | 模块 ID `BMID_*`（1=OTA,2=GPS,3=WATCH_BG,4=MODEM,5=SECBL,6=BP） |
| 1 | ucIsOffset | uint8 | >0=按已存偏移续传；0=从头 |
| 2 | ucFileCount | uint8 | 本次会话文件数量 |
| 3 | ucReserved | uint8 | 保留 |
| 4-7 | ulTotalSize | uint32 LE | 所有文件总大小；不足回 `BFRS_DISK_FULL` |

**FILE_TRANS_REQ 响应 `B2A_FILE_TRANS_REQ_RESP_T`**（12B，行 2004-2011）：`ucStatus(BFRS_*) | ucModuleId | ucFileCount | ucCurrFileIdx | ulSliceMaxSize(u32 LE 单分片最大) | ulOffset(u32 LE 断点续传起始偏移)`。

**FILE_TRANS_START (0x01) `B2A_FILE_TRANS_START_REQ_T`**（16+N B，行 2024-2032）：`ulCurrFileSize(u32 LE) | ulOffset(u32 LE, 0="wb"从头/>0="ab+"追加) | ulSliceSize(u32 LE) | ucType(BFTT_*) | ucZipFlag(1) | uiFileNameLen(u16 LE, >13 截断到12) | szFile[N] ASCII 文件名(设备缓冲固定13B 8.3名)`。响应 1B `ucResult`（SUCC=就绪 / MEMORY=分配失败或 ulSliceSize>ulSliceMaxSize，行 2039-2041）。

**FILE_TRANS (0x02) 数据块**：请求 `data[]` 原始文件字节流（无任何头，长度=uiLen；多包由外层 `ucStatus` 的 `EHST_IS_MULTI_PKT/EHST_MULTI_PKT_END` 驱动，行 2054-2056）。
- 成功响应 `B2A_FILE_TRANS_DATA_RESP_T`（9B，行 2063-2067）：`ulCurrSliceRecvLen(u32 LE) | ulCheckSum(u32 LE 字节累加和) | ucStatus(固定 EHST_SUCC)`。
- 错误响应（1B，行 2074-2076）：`ucResult` — MEMORY(4)=缓冲空/超限, FAIL(1)=多包丢包, FSUM_FAIL(13)=回读校验失败。

**FILE_TRANS_STOP (0x03)**：请求无 payload；响应 1B `ucResult`（SUCC / FSUM_FAIL 尺寸/资源校验失败，行 2095-2097）。
**FILE_TRANS_OFFSET (0x06)**：请求无 payload；响应 4B `ulOffset(u32 LE)` 当前已收字节偏移（低内存回退 `B2A_CommAck(EBEC_MEMORY)` 1B，行 2117-2119）。
> OTA 差分续传：外层 `ucStatus` 的 `EHST_OTA_PART=0x80` 标记差分续传（行 45）。

### 4.7 重启 / 复位 / 恢复出厂 / 关机

**DEV_CTRL 空 payload 键**（行 1791-1799，源 ble2appWrap.c:12958-12991）：`POWER_OFF(0x01)`、`RESET(0x02)`、`RESTORE(0x03)`、`FIND(0x04)`、`FIND_END(0x05)`、`FIND_PHONE_END(0x06)` **均无 payload**，且 `ucIsOk` 被强制 0 → **不发响应包**。
另有两条恢复出厂路径：`BKEY_BOND_DEL(0x03)` 触发 `SYS_Restore()`（行 182）；关机也可经 IND `BOCT_POWER_OFF(0x05)` 通知（行 1279）。
**通用 Ack `B2A_DEV_CTRL_ACK_T`**（1B，行 1807-1809）：仅当帧头带 `EHST_ACK` 且 handler 保留 `ucIsOk!=0` 时才发单字节错误码。

---

## 5. 补充：其它 payload 关键结构（简表）

- **健康数据通用头 `B2A_RPT_DATA_HEADER_T`**（4B，行 1427-1432）：`type(RPT_DATA_IDX_*) | count(固定1) | len(u16 LE = uiLen-4)`，之后接各类型 summary + note 数组。
- **RPT_DATA_START 响应**（行 1456-1459）：每类型 `{days(1)+size(4 LE)}` 槽位；**已知固件 bug/歧义**：被选类型只写 2B（uiIdx+2）、未选类型写 5B（uiIdx+5），导致后续槽位偏移，总长非定长（行 1450-1454，需确认）。
- **各健康类型 summary/note**（行 1499-1774）：HR/SPO2/PRESS/ACTIVITY/SLEEP/SPORT/BLOOD_PRESSURE/ECG/SSHR/SHRV/SDETECT/SRRI/BP_TREND/BP_TREND_EX，均 `packed` 定长 summary + 变长 note 数组，字段及偏移见对应行注释（含 `@N` 偏移标注）。
- **PUSH_PHONE**（行 1045-1052）：`ucType(1=来电,2=接听,3=通话中,4=挂断) | ucReserved | nameLen(u16 LE)+name | operatorLen(u16 LE)+operator`。
- **PUSH_MSG**（行 1063-1074）：`type | count | titleLen(u16 LE)+title | abstractLen+abstract | msgLen+msg`（3 段 TLV，长度前缀均 2B LE）。
- **PUSH_WEATHER**（行 1101-1141）：`ucVersion | ucCityLen + city | ulUtc(u32 LE) | ucDayCount(>7→7) | 当前天气各字段 | hour_weater[24]×2B | next_weater[dayCount-1]×3B`。

---

## 关键工程注意点
1. **CRC16-CCITT-FALSE** 覆盖偏移 8 起 `uiLen` 字节（不含帧头本身），CRC 失败设备回帧头 `EHST_FAIL` 作被动 NAK（行 22, 41）。
2. 大量 MAC/IMEI/ICCID **字节序需与 App 端核对**（行 573-574, 609）。
3. 多处功能**编译停用/未实现**：PUSH_MUSIC/CAMERA/CHK_SMSG、BLE_LOG、TEST_MOTO_*、BREATHING、BOND_REQINFO_GET、hardfault dump（dead code）。
4. `SET_*` 别名复用：DRINK_WATER≡SEDENTARY（16B），SPO2/PRESSURE≡HR_MODE（8B，但设备对 interval=0 分别强制 1/30/10）。
5. 文件路径（本源文件）：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\b2a_protocol.h`；权威逐字节规范另见同目录 `manual-b2a-complete.md`。