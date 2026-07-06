# B2A 协议手册下半部（第 1300–2669 行）协议事实提取

覆盖范围：`BCMD_SET(0x03)` 尾部（表盘删除/科学测量/血压原始模式）、`BCMD_PUSH(0x04)`、`BCMD_IND(0x05)`、`BCMD_RPT_DATA(0x06)/RPT_DATA2(0x86)`、`BCMD_DEV_CTRL(0x07)`、`BCMD_TEST(0x08)`、`BCMD_FILE_TRANS(0x0F)`，以及全局错误码表、抓包说明、源码索引。所有字节表仅描述 payload（内层 4 字节命令头之后的 `szParam`），不含 B2A 外层帧头。行号沿用手册标注（文档 1-indexed）。

设备维护类命令（时间同步/设备信息/固件版本/用户信息读写）在本段（1300–2669）之外——本段只出现：**电量**（心跳 0x0C 携带）、**日志拉取**（0x07/0x07）、**OTA/文件传输**（0x0F）、**重启/关机/恢复出厂**（0x07 的 0x02/0x01/0x03）。这些已在下方“设备维护类命令”小节汇总标注。

---

## 一、BCMD_SET (0x03) 尾部命令（行 1300–1343）

### BKEY_SET_DEV_DIAL_DEL 删除表盘（行 1300–1308）
- 方向：App→设备。handler `ble2appWrap.c:12717-12757`。
- 前置条件：`APP_Setting_Dial_Switch_IsAct()` 为真（表盘切换中）→ 回 `EBEC_NOT_SUPPORT`，不删除（`ble2appWrap.c:12721-12736`）。否则非分包走 `SDM_SetDevDialDel`（`SysDataMgr.c:7396`），分包走 `SDM_SetDevDialDelExt`。

请求 payload：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0.. | dial_del blob | 可变(可分包) | - | 待删表盘标识（UI/App 约定，源码不解析） | ble2appWrap.c:12739-12746; SysDataMgr.c:7396 |

响应：1 字节 result，`B2A_SetDevDialDelAck(ucResult)`（`ble2appWrap.c:2933-2942`）或失败时 `B2A_CommAck(...,EBEC_FAIL/EBEC_NOT_SUPPORT)`（`ble2appWrap.c:12725,12731,12749`）。

### BKEY_SET_SCIMEASURE_PARA (0x29) 设置科学测量参数（行 1312–1321）
- 方向：App→设备。handler `APP_Setting_ScientificMeasure_Extral`（`ble2appWrap.c:12759-12763`，`gui_setting_skg.c:91`）。仅用 `inParam[0]`。

请求 payload：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | open_flag | 1 | - | 科学测量(血压PPG)开关，0=关 非0=开（写 NVM_ID_NUM_0；开则启动 skg_ppg_bp_func_start） | gui_setting_skg.c:107-132 |
| 1.. | 其余字段 | 可变 | - | 源码注释掉(min_sel 等)，当前未用 | gui_setting_skg.c:110 |

响应：走通用 CommAck，result 1 字节（`//ucIsOk=0` 已注释，仍回 EBEC_SUCC，`ble2appWrap.c:12763,12775-12781`）。

### BKEY_SET_BLP_RAW_MODE (0x2A) 设置血压原始数据保存模式（proto 6.3.42，行 1325–1334）
- 方向：App→设备。handler `B2A_SetBlpRawMode`（`ble2appWrap.c:12765-12768`→`3504`），要求 `uiLen>=5`（`ble2appWrap.c:3511`）。

请求 payload：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | time_per | 4 | LE | 时间/时长参数（on 时回显为 on_utc，非倒计时） | ble2appWrap.c:3517-3520,3524 |
| 4 | on_flag | 1 | - | 保存开关，0=关 非0=开(归一化 1) | ble2appWrap.c:3521-3523 |

响应=CommAck，result 1 字节。

---

## 二、BCMD_PUSH (0x04) App→设备推送（行 1346–1515）

分发入口 `ble2appWrap.c:12786`，按命令头 Key 分派。BKEY 枚举 `ble2appEx.h:227-239`：

| Key | 名称 | 含义 | 状态 |
|-----|------|------|------|
| 0x01 | BKEY_PUSH_PHONE | 来电/电话状态推送 | 启用 `ble2appWrap.c:12789` |
| 0x02 | BKEY_PUSH_MSG | 消息通知推送 | 启用 `ble2appWrap.c:12793` |
| 0x03 | BKEY_PUSH_WEATHER | 天气推送 | 启用 `ble2appWrap.c:12797` |
| 0x04 | BKEY_PUSH_MUSIC | 音乐信息推送 | `#if 0` 关闭(TAPD 1000164) `12801-12805` |
| 0x05 | BKEY_PUSH_CAMERA | 相机状态推送 | `#if 0` 关闭 `12806-12809` |
| 0x06 | BKEY_PUSH_CHK_SMSG | 短消息检查 | 注释掉 `12811-12815` |
| 0x07 | BKEY_PUSH_MEDICAL_INFO | 医疗信息推送 | 启用 `12816` |

通用响应：仅当帧头 `ucStatus` 含 `EHST_ACK` 且处理成功(`ucIsOk>0`)才回 `B2A_CommAck(0x04, Key, EBEC_SUCC)`（`ble2appWrap.c:12843-12849`），单字节状态码。

### 0x01 BKEY_PUSH_PHONE 来电/电话状态（行 1366–1389）
handler `SDM_SetIncomingData(pszParam,uiParamLen,0)`（`SysDataMgr.c:5266`，is_ancs=0）。名字/运营商长度为 2 字节小端。

请求 (App→设备)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|------|------|------|--------|-----------|-----------|
| 0 | ucType | 1 | - | 1=来电,2=接听,3=通话中,4=挂断 | SysDataMgr.c:5306 |
| 1 | 保留/计数 | 1 | - | 读后丢弃(原 ucCount，仅 i++) | SysDataMgr.c:5304,5309 |
| 2 | nameLenth | 2 | LE | 来电名称字节数 | SysDataMgr.c:5312 |
| 4 | ucName | nameLenth | - | 来电名称(UTF-8) | SysDataMgr.c:5316-5318 |
| 4+nameLenth | operatorLenth | 2 | LE | 号码/运营商字节数 | SysDataMgr.c:5321 |
| 6+nameLenth | ucOperator | operatorLenth | - | 号码/运营商字符串 | SysDataMgr.c:5325-5327 |

补充：`data[0]==4`(挂断)且上次为 1(来电) → `is_misscall=1`，`data[0]` 改写为 **33** 走消息列表 `SDM_SetMessagePushData`（`SysDataMgr.c:5298-5336`）。非未接时发 `UI_EVT_INCOMING_START`（2 字节缓冲，[0]=ucType，[1]保留）。
响应：无业务 payload；如需 ACK 回 `B2A_CommAck(0x04,0x01,EBEC_SUCC)`。

### 0x02 BKEY_PUSH_MSG 消息通知（行 1393–1418）
handler `SDM_SetMessagePushData`（`SysDataMgr.c:5364`），整段落盘(`MAC_SDM_MSG_PUSH`)并透传 `UI_EVT_REMIND_START`，不逐字段拆解。稳定格式（本机构造+消费约定）：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|------|------|------|--------|-----------|-----------|
| 0 | type | 1 | - | 消息/应用类型 | gui_remind_ring.c:510 / 483 |
| 1 | count/子类 | 1 | - | TLV 字段计数(本机恒 3)；ANCS 路径作应用子类 | gui_remind_ring.c:511 |
| 2 | titleLen | 2 | LE | 标题字节数 | gui_remind_ring.c:521-522 |
| 4 | title | titleLen | - | 标题(应用名/联系人) | gui_remind_ring.c:523 |
| 4+titleLen | abstractLen | 2 | LE | 摘要字节数 | gui_remind_ring.c:536-537 |
| 6+titleLen | abstract | abstractLen | - | 摘要/副标题 | gui_remind_ring.c:538 |
| 6+titleLen+abstractLen | msgLen | 2 | LE | 正文字节数 | gui_remind_ring.c:551-552 |
| 8+titleLen+abstractLen | msg | msgLen | - | 正文 | gui_remind_ring.c:553 |

type 取值（`SysDataMgr.c:5550`）：0=OTHER,1=INCOMING_CALL,2=MISSED_CALL,3=VOICE_MAIL,4=SOCIAL,5=SCHEDULE,6=EMAIL,7=NEWS；设备内部弹窗复用 24/25/26；未接来电=33。ANCS 路径字段顺序略异但长度前缀同为 2B LE。**App 推送精确字段未在 handler 校验，以双方约定为准。**
响应：无业务 payload；如需 ACK 回 `B2A_CommAck(0x04,0x02,EBEC_SUCC)`。

### 0x03 BKEY_PUSH_WEATHER 天气（行 1422–1455）
handler `SDM_SetWeatherData`（`SysDataMgr.c:6696`），落盘后 `APP_InitWeatherInfo` 逐字节解析（`gui_weather_main.c:308`）。固定头 + 24 条逐小时 + (ucDayCount-1) 条逐日；整数单字节，UTC 4 字节 LE。

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|------|------|------|--------|-----------|-----------|
| 0 | ucVersion | 1 | - | 协议版本 | gui_weather_main.c:372 |
| 1 | ucCityLen | 1 | - | 城市名字节数(超 15 截断) | gui_weather_main.c:375-380 |
| 2 | szCityName | ucCityLen | - | 城市名 | gui_weather_main.c:392 |
| 2+ucCityLen | ulUtc | 4 | LE | 数据时间戳(UTC 秒)；≤旧值+2h 丢弃整包 | gui_weather_main.c:387-394 |
| 6+ucCityLen | ucDayCount | 1 | - | 多日预报天数(>7 截 7) | gui_weather_main.c:396-397,461-464 |
| 7+ucCityLen | ucType | 1 | - | 当前天气类型(图标索引，越界归 0) | gui_weather_main.c:399-405 |
| 8+ucCityLen | ucTemp | 1 | - | 当前温度 | gui_weather_main.c:408 |
| 9+ucCityLen | ucTempMax | 1 | - | 当日最高温 | gui_weather_main.c:412 |
| 10+ucCityLen | ucTempMin | 1 | - | 当日最低温 | gui_weather_main.c:415 |
| 11+ucCityLen | ucHumidity | 1 | - | 湿度 | gui_weather_main.c:418 |
| 12+ucCityLen | ucUv | 1 | - | 紫外线指数 | gui_weather_main.c:421 |
| 13+ucCityLen | ucPm | 1 | - | PM/空气质量 | gui_weather_main.c:425 |
| 14+ucCityLen | ucWind | 1 | - | 风力(ver2.3) | gui_weather_main.c:429 |
| 15+ucCityLen | ucSunRise | 1 | - | 日出 | gui_weather_main.c:432 |
| 16+ucCityLen | ucSunSet | 1 | - | 日落 | gui_weather_main.c:435 |
| 17+ucCityLen | reserve3 | 1 | - | 保留(ver2.3) | gui_weather_main.c:438 |
| 18+ucCityLen | hour_weater[24] | 48 | - | 24 条×2B：[ucType(1)][ucTemp(1)] | gui_weather_main.c:443-459 |
| 66+ucCityLen | next_weater | (ucDayCount-1)×3 | - | 每条 3B：[ucType][ucTemp_max][ucTemp_min] | gui_weather_main.c:466-485 |

总长 = 18 + ucCityLen + 48 + 3×(ucDayCount-1)。最后一日由当日字段回填，不在字节中。
响应：无业务 payload；如需 ACK 回 `B2A_CommAck(0x04,0x03,EBEC_SUCC)`。

### 0x04 BKEY_PUSH_MUSIC 音乐（关闭）/ 0x05 BKEY_PUSH_CAMERA 相机（关闭）/ 0x06 CHK_SMSG（注释）（行 1459–1489）
- MUSIC：`SDM_SetMusicData`(`SysDataMgr.c:6740`) 被 `#if 0`，默认走 else 回 NOT SUPPORT。启用时仅整段落盘 `MAC_SDM_MUSIC_PUSH` + 透传 `UI_SDT_MUSIC_INFO`，字段**需确认**。
- CAMERA：`SDM_SetCameraStatusData`(`SysDataMgr.c:6787`) 被 `#if 0`，落盘 `MAC_SDM_CAMERA_PUSH` + 透传 `UI_SDT_CAMERA_STATUS`，字段**需确认**。
- CHK_SMSG：整段块注释（`12811-12815`），原设计回 `B2A_CommAck(cmd,key,chk_ret)`，chk_ret ∈ {EBEC_SUCC, EBEC_NO_DATA}，payload **需确认**。

### 0x07 BKEY_PUSH_MEDICAL_INFO 医疗信息（行 1493–1507）
就地处理 `ble2appWrap.c:12816-12836`。

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|------|------|------|--------|-----------|-----------|
| 0 | 医疗信息字符串 | uiParamLen(≤64) | - | C 字符串，>64 截断 | ble2appWrap.c:12820-12823,12829 |

逻辑：与旧值 `strncmp` 不同则 `NVM_SetMedicalInfo` 持久化 + `APP_Setting_About_Update_MedicalInfo`。缓冲 `U32 medic_info[32]`(128B)。
响应：ACK 调用被注释但未清 `ucIsOk`，仍回 `B2A_CommAck(0x04,0x07,EBEC_SUCC)`。

字节序/编码说明（行 1511–1514）：所有 2B 长度前缀与 4B UTC 均 LE；字符串无终止符约束，长度以前缀为准。

---

## 三、BCMD_IND (0x05) 设备↔App 指示/控制（行 1517–1749）

双向：设备主动通知（`B2A_DevNotifyApp*`），App 也可回执/控制（`case BCMD_IND`，`ble2appWrap.c:12854`）。BKEY 枚举 `ble2appEx.h:243-262`：

```
0x01 INCOMING  0x02 SCIENTIFICMEASURE  0x03 SPORTS  0x04 MUSIC  0x05 CAMERA
0x06 SPORTS_DATA  0x07 OTHER_CTRL  0x08 OTHER_SYMPTOM  0x09 SETTING
0x0A HEALTH_DATA_UPDATE  0x0B ANCS_RECV  0x0C HEARTBEAT  0x0D BLP_RAW_STATE  0x0E HEALTH_ALERT
```

设备侧请求处理：除 SCIENTIFICMEASURE/OTHER_CTRL/HEALTH_DATA_UPDATE/HEARTBEAT 外其余 KEY 为空实现（仅 App 回执，注释「由手表发起,无需处理」`ble2appWrap.c:12855`）。

### 0x01 INCOMING 来电通知（行 1543–1551）
响应(设备→App) `B2A_DevNotifyAppIncoming`(`3143-3151`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucType | 1 | 0=接听,1=挂断 | ble2appWrap.c:3139,3148 |

请求(App→设备)：空实现。

### 0x02 SCIENTIFICMEASURE 科学测量/异常事件（行 1555–1571）
响应 `B2A_DevNotifyAppScientificMeasure`(`3157-3187`)，8 字节：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulType/flagbits | 4 | LE | 测量/异常标志位(`g_scientificMeasure_flagbits`) | ble2appWrap.c:3164-3167; drv_comm.c:655 |
| 4 | timestamp | 4 | LE | UTC 秒 | ble2appWrap.c:3168-3172 |

请求(App→设备)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucFlag | 1 | 0x00=BLE 上传完成回执→`APP_Setting_ScientificMeasure_BLEfinish_cb()`；其它忽略 | ble2appWrap.c:12863-12866 |

### 0x03 SPORTS 运动控制（行 1574–1582）
响应 `B2A_DevNotifyAppSports`(`3198-3206`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulStatus | 1 | ENUM_B2A_IND_SPORT_TYPE：1=READY,2=START,3=PAUSE,4=CONT,5=STOP | ble2appWrap.c:3198,3203; ble2appEx.h:300-310 |

请求：空实现。

### 0x04 MUSIC 音乐控制（行 1586–1594）
响应 `B2A_DevNotifyAppMusic`(`3217-3225`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulType | 1 | ENUM_B2A_IND_MUSIC_TYPE：1=PLAY,2=PAUSE,3=STOP,4=PREV,5=NEXT,6=VOL_U,7=VOL_D | ble2appWrap.c:3217,3222; ble2appEx.h:284-296 |

请求：空实现。

### 0x05 CAMERA 拍照控制（行 1598–1606）
响应 `B2A_DevNotifyAppCamera`(`3236-3244`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulType | 1 | ENUM_B2A_IND_CAMERA_TYPE：1=ENTER,2=EXIT,3=CAP_SINGLE,4=CAP_MULTI | ble2appWrap.c:3236,3241; ble2appEx.h:314-323 |

请求：空实现。

### 0x06 SPORTS_DATA 运动数据通知（行 1610–1618）
响应 `B2A_DevNotifyAppSportsData`(`3255-3263`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulType | 4 | LE | 运动数据类型/标志(U32，取值未定义，需确认) | ble2appWrap.c:3255,3260 |

请求：空实现。

### 0x07 OTHER_CTRL 其它控制（行 1622–1645）
枚举 `ENUM_B2A_OTHER_CTRL_TYPE`(`ble2appEx.h:266-278`)：
```
0x01 FIND_PHONE  0x02 FIND_PHONE_END  0x03 WEATHER_REQ  0x04 CHARGE_STATUS
0x05 POWER_OFF   0x06 FIND_WATCH_END  0x07 MEDICAL_INFO_REQ
```
响应 `B2A_DevNotifyAppOtherCtrl`(`3274-3282`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulType | 1 | 控制类型（例：关机发 BOCT_POWER_OFF=0x05） | ble2appWrap.c:3274,3279; System.c:3204,3457 |

请求(App→设备)：`switch(pszParam[0])`：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucCtrlType | 1 | BOCT_FIND_PHONE(0x01)→`APP_Find_Tip_indAck_proc()`；其它 default 忽略 | ble2appWrap.c:12883-12890 |

### 0x08 OTHER_SYMPTOM 症状/事件上报（行 1649–1657）
响应 `B2A_DevNotifyAppSymptom`(`3293-3301`)：payload 为上层透传字节缓冲（`szData`，长 `uclen`），结构由上层定义、本函数不解析，**需确认**。请求方向：无分支，落 else 打印 NOT SUPPORT。

### 0x09 SETTING 设置通知（行 1661–1669）
响应 `B2A_DevNotifyAppSetting`(`3312-3320`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulType | 4 | LE | 设置类型/标志(U32，取值未定义，需确认) | ble2appWrap.c:3312,3317 |

请求：空实现。

### 0x0A HEALTH_DATA_UPDATE 健康数据更新通知（行 1673–1685）
响应 `B2A_DevNotifyAppHealthDataUpdate`(`3326-3334`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulType | 1 | ENUM_B2A_RPT_DATA_IDX_TYPE(如 0x07=BP,0x08=ECG) | ble2appWrap.c:3326,3331; ble2appEx.h:329-357 |

请求(App 回执)：`switch(pszParam[0])`：0x07(BLOOD_PRESSURE)→`APP_Press_indAck_proc()`；0x08(ECG)→`APP_Ecg_indAck_proc()`；其它忽略（`12898-12908`）。

### 0x0B ANCS_RECV ANCS 接收标志（行 1689–1697）
响应 `B2A_DevNotifyAppAncsRecvFlag`(`3394-3402`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ulFlag | 1 | ANCS 接收标志(含义未标注，需确认) | ble2appWrap.c:3394,3399 |

请求：空实现。

### 0x0C HEARTBEAT 心跳（行 1701–1719）★维护类（携带电量）
响应(设备→App) `B2A_DevNotifyHeartbeat`(`3419-3442`)，8 字节，状态 `EHST_ACK`。布局注释「utc(4)+seq(2)+battery(1)+reserved(1)」：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | utc | 4 | LE | UTC 秒 `EQC_GetUtcTimeStampSec()` | ble2appWrap.c:3425,3428-3431 |
| 4 | seq | 2 | LE | 心跳序号 `s_usHeartbeatSeq`，发送后自增 | ble2appWrap.c:3432-3433,3437 |
| 6 | battery | 1 | - | **电量 `SYS_GetBatPower()`** | ble2appWrap.c:3434 |
| 7 | reserved | 1 | - | 固定 0x00 | ble2appWrap.c:3435 |

请求(App 心跳应答)：仅置 `B2A_set_recvData_flag(1)`，不逐字节解析。注释声明 App 应答布局「utc(4)+seq(2)+network_status(1)+reserved(1)」：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | utc | 4 | LE | (注释声明，设备未解析) | ble2appWrap.c:12915 |
| 4 | seq | 2 | LE | (同上) | ble2appWrap.c:12915 |
| 6 | network_status | 1 | - | 网络状态(同上) | ble2appWrap.c:12915 |
| 7 | reserved | 1 | - | 保留(同上) | ble2appWrap.c:12915 |

### 0x0D BLP_RAW_STATE 血压原始数据保存状态（proto 6.5.13，行 1723–1732）
响应 `B2A_DevNotifyBlpRawState`(`3480-3502`)，5 字节，状态 `EHST_ACK`：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | time_per | 4 | LE | 周期时间(取 `BLP_OTA_SAVE_DATA.on_utc`，即最近 Set 回显值) | ble2appWrap.c:3449-3451,3490-3495 |
| 4 | on_flag | 1 | - | 开关标志 | ble2appWrap.c:3472,3496 |

请求：空实现（「APP ack, no action」）。

### 0x0E HEALTH_ALERT 主动健康告警（海外无 4G 版，行 1736–1749）
响应 `B2A_DevNotifyAppHealthAlert`(`3343-3388`)，仅 BLE 连接时推送。payload = 1 字节告警类型 + 算法事件体。单包时状态 `EHST_SUCC`；超长多包（含末包）状态均 0x00（**注意：非 EHST_SUCC**）。

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucAlertType | 1 | ENUM_B2A_RPT_DATA_COMM_TYPE：0=HR_HIGH,1=HR_LOW,2=SPO2_LOW,3=GLUSE_ALERT,4=BP_ALERT,5=HR_HEALTH | ble2appWrap.c:3364; ble2appEx.h:360-371 |
| 1 | szData(body) | usLen(可变) | 算法事件体原样透传(HR 类=p_evt/size；SPO2 类=alertPackage/packageLen) | ble2appWrap.c:3350,3367; gh3x2x_eiot.c:631-637,681-682 |

调用方选择(`gh3x2x_eiot.c:631-635`)：默认 HR_HIGH；`p_event->type==1`→SPO2_LOW；`type==0&&sub_type==1`→HR_LOW。请求方向未处理(NOT SUPPORT)。

---

## 四、BCMD_RPT_DATA (0x06) / RPT_DATA2 (0x86) 同步健康数据（行 1752–2150）

命令码 `BCMD_RPT_DATA=0x06`，`BCMD_RPT_DATA2=0x86`（`ble2appEx.h:95,101`）。子命令 Key(`ble2appEx.h:410-419`)：`START=0x01`、`STOP=0x02`、`DATA=0x03`、`ACK=0x04`。分发 `ble2appWrap.c:12929-12952`。

**0x86 与 0x06 差异**：解析/组包相同，仅“无数据”回复不同：
- `0x06`：无数据直接 `B2A_CommAck(...EBEC_SUCC/空参)`，`pszParam=NULL,uiParamLen=0`。
- `0x86`：无数据回 `B2A_CommAckWithParam(...)`，`pszParam=&stReq`（回显请求 4 字节），并把 `pszParam[1]=0x0`（ucOperate 清零）作“无新数据”标志。该模式在 HR/SPO2/PRESS/ACTIVITY/SLEEP/SPORT/BP/ECG/SSHR/SHRV/SDETECT/SRRI/BP_TREND/BP_TREND_EX 各类型无数据分支反复出现（`6492-6496,6575-6582,7000-7004…`）。

### 通用 DATA 包结构（设备→App，所有类型共用，行 1766–1799）
`BKEY_RPT_DATA(0x03)` payload = 4 字节通用头 + summary + note 数组：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | type | 1 | - | 健康数据类型 RPT_DATA_IDX_* | ble2appWrap.c:6606 |
| 1 | count(块数) | 1 | - | 固定 1 | ble2appWrap.c:6613 |
| 2 | len | 2 | LE | 后续有效字节数 = uiLen-4 | ble2appWrap.c:6664-6665 |
| 4.. | summary+notes | 可变 | LE | 见各类型 | - |

数据类型枚举 `RPT_DATA_IDX_*`(`ble2appEx.h:331-356`)：

| 值 | 名称 | 值 | 名称 |
|---|---|---|---|
| 0 | HR 心率 | 9 | BP_TREND PPG血压趋势 |
| 1 | SPO2 血氧 | 10 | WEAR 佩戴心率 |
| 2 | PRESS 压力 | 11 | SSHR 静息心率 |
| 3 | ACTIVITY 活动 | 12 | SHRV HRV |
| 4 | SLEEP 睡眠 | 13 | SRRI RRI |
| 5 | BREATHING 呼吸训练(`#if 0`关闭) | 14 | SDETECT 心脏健康检测 |
| 6 | SPORT 运动 | 15 | BP_TREND_EX 血压趋势扩展 |
| 7 | BLOOD_PRESSURE 血压 | 16 | TEMP_SLEEP 睡眠临时报告 |
| 8 | ECG 心电 | | |

**分包发送(通用尾部，`8616-8655`)**：`uiParamLen>单包`时首包 `B2A_HealthPkt(...paramlen=总长,status=0x04,index=0)`，续包 `B2A_NoCmdPkt(...status=0x04)`，末包 `B2A_NoCmdPkt(...status=0x0c)`；否则单包 `B2A_MakePkt(...status=0x00,index=0)`。SRRI 自带同逻辑(`8337-8362`)。

### BKEY_RPT_DATA_START (0x01) 开始同步（行 1803–1835）
请求：handler `B2A_RptDataStartAck`(`5724-5913`)，头 4 字节掩码：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | mask | 4 | LE | RPT_DATA_MASK_*(bit n=1<<IDX_n)；仅定义到 ECG=bit8 | ble2appWrap.c:5738 |

掩码位(`ble2appEx.h:377-385`)：bit0=HR,bit1=SPO2,bit2=PRESS,bit3=ACTIVITY,bit4=SLEEP,bit5=BREATHING,bit6=SPORT,bit7=BLOOD_PRESSURE,bit8=ECG。

响应：按掩码逐类型回填「未同步天数 + size 占位」，共 RPT_DATA_IDX_MAX 槽位。**已知实现 bug**：未选中类型 else 分支步进 5 字节(days1B+size4B)，被选中类型 if 分支只写 2 字节(days1B+size1B)。故下表 5B 槽位偏移仅在其前无任何被选中类型时成立，每出现一个被选中类型其后偏移 -3。基准（全未选中）：

| 偏移(基准) | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | HR.days | 1 | 恒写 0 | ble2appWrap.c:5752-5754 |
| 1 | HR.size(占位) | 4 | 选中时仅写 1B | ble2appWrap.c:5756-5758 |
| 5 | SPO2.days/size | 1+4 | | 5765-5773 |
| 10 | PRESS.days/size | 1+4 | | 5780-5792 |
| 15 | ACTIVITY.days/size | 1+4 | | 5795-5807 |
| 20 | SLEEP.days/size | 1+4 | | 5810-5822 |
| 25 | BREATHING.days/size | 1+4 | | 5825-5837 |
| 30 | SPORT.days/size | 1+4 | | 5840-5852 |
| 35 | BLOOD_PRESSURE.days/size | 1+4 | | 5855-5867 |
| 40 | ECG.days/size | 1+4 | | 5870-5882 |

days/size 全写 0（占位未计算），总长非定值，**偏移含歧义，需确认**。

### BKEY_RPT_DATA_STOP (0x02) 结束同步（行 1839–1847）
请求：`B2A_RptDataStopAck`(`5926-5936`)，**未解析任何入参**。响应：`B2A_CommAck(BCMD_RPT_DATA,STOP,EBEC_SUCC)`，1 字节错误码。

### BKEY_RPT_DATA_ACK (0x04) App 数据确认（行 1851–1871）
请求：`B2A_RptDataAck`(`6014-6141`)，要求 `uiLen>=3`：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | type | 1 | RPT_DATA_IDX_* | ble2appWrap.c:6023 |
| 1 | result | 1 | 0=成功,1=失败 | ble2appWrap.c:6024 |
| 2 | today | 1 | 0=当天,1=历史 | ble2appWrap.c:6025 |
| 3.. | reserved | 可变 | 预留 | ble2appWrap.c:6009 |

行为：BP_TREND_EX(0x0F) result==0 标记 utc 已确认；SRRI(0x0D) 推进游标(历史发完即删)；其余匹配 pending 后对 SPORT/BP/ECG 置 `Is_upload=1` 或 today!=0 时 `ADM_Remove*Data(dayindex)`。响应：**无响应**（`return EECT_OK`）。

### BKEY_RPT_DATA (0x03) 数据上报（行 1875–2150）
请求(App→设备)：`B2A_RptData`(`6416`)，位域结构 `B2A_RPT_DATA_REQ_PARAM`(`ble2appEx.h:390-396`)：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucType | 1 | RPT_DATA_IDX_* | ble2appEx.h:392 |
| 1 | ucOperate | 1 | 操作(无数据回显时清 0) | ble2appEx.h:393 |
| 2 | ucToday | 1 | 0=当天,非0=历史(day1..6) | ble2appEx.h:394 |
| 3 | ucReserve | 1 | 预留 | ble2appEx.h:395 |

**示例帧(行 1888，原样抄录)**：`...86 03 04 00 00 00 00 00 00`（type=0,operate=0,today=0,reserve=0）/ `...86 03 04 00 0A 00 00 00 00`（type=0x0A=WEAR）。

响应各类型 summary/note 逐字节格式（线上手工装包，与磁盘 struct 不完全一致）：

**HR(0x00)/WEAR(0x0A) 心率/佩戴心率（`6504-6671`）** — summary：UTC(4,LE,偏移4)+count(2,LE,偏移8)+silent_hr(1,偏移10)+reserved(1,偏移11)。note 每条 5B：utc(4,LE)+value(1,心率值)。

**SPO2(0x01) 血氧（`6672-6793`）** — summary：UTC(4,LE)+count(2,LE)+reserved(2,LE)。note 每条 6B：utc(4,LE)+spo2(1)+hr(1)。

**PRESS(0x02) 压力（`6794-6915`）** — 与 SPO2 同构(STRESS_MEASURE/STRESS_NOTE,`AppDataMgr.h:309-325`)：summary=UTC(4)+count(2)+reserved(2)；note 每条 6B=utc(4)+stress(1)+hr(1)。

**ACTIVITY(0x03) 活动（`6916-7210`）** — summary：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 4 | UTC | 4 | LE | | 7027-7034 |
| 8 | count | 2 | LE | note 条数 | 7091-7102 |
| 10 | Step | 4 | LE | 总步数 | 7104-7111 |
| 14 | calorie | 4 | LE | 总卡路里 | 7112-7119 |
| 18 | distance | 4 | LE | 总距离(**值=distance/1000**) | 7120-7128 |
| 22 | Sport_time | 2 | LE | 总运动时间 | 7129-7132 |
| 24 | Stand_count | 1 | - | 总站立次数 | 7133 |

note 每条 19B：utc(4,LE,`utc-=1`)+Step(4,LE)+calorie(4,LE)+distance(4,LE,/1000)+Sport_time(2,LE)+Stand_count(1)（`7142-7200`）。

**SLEEP(0x04)/TEMP_SLEEP(0x10) 睡眠（`7211-7386`）** — TEMP_SLEEP 时通用头 type 改写为 0x10(`7300`)，其余相同，临时报告经 `skg_sleep_get_temp_report()`。summary(基于 `S_SLEEP_FILE_INFO_T`,`sleep_data.h:52-64`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 4 | Utc | 4 | LE | 睡眠日期标识 | 7317-7324 |
| 8 | SleepIn | 4 | LE | 入睡时间 | 7326-7333 |
| 12 | WakeUp | 4 | LE | 起床时间 | 7335-7342 |
| 16 | LightTime | 2 | LE | 浅睡(s) | 7344-7347 |
| 18 | DeepTime | 2 | LE | 深睡(s) | 7349-7352 |
| 20 | RemTime | 2 | LE | 眼动(s) | 7354-7357 |
| 22 | WakeTime | 2 | LE | 清醒(s) | 7359-7362 |
| 24 | Count | 1 | - | note 条数 | 7364 |

note 每条 5B(`S_SLEEP_DATA_T`,`sleep_data.h:44-49`)：Utc(4,LE)+State(1)。

**BREATHING(0x05) 呼吸训练（`7387-7467`，`#if 0` 禁用）** — 历史线格式：summary=UTC(4)+count(2)+duration(2)+breath_count(2)+average_hr(1)+average_stress(1)；note 每条 4B=Second(2)+stress(1)+hr(1)。当前不上报。

**SPORT(0x06) 运动（`7468-7744`）** — summary(`7554-7647`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 4 | Utc_start | 4 | LE | 开始时间 | 7554-7561 |
| 8 | Sport_time | 2 | LE | 时长 | 7562-7565 |
| 10 | Sport_type | 1 | - | 运动类型 | 7566 |
| 11 | Data_format | 1 | - | 数据格式 | 7568 |
| 12 | data_count | 2 | LE | note 个数 | 7570-7573 |
| 14 | Average_hr | 1 | - | 平均心率 | 7574 |
| 15 | Max_hr | 1 | - | 最大心率 | 7576 |
| 16 | Min_hr | 1 | - | 最小心率 | 7578 |
| 17 | resoved1 | 1 | - | 预留 | 7580 |
| 18 | warm_up | 2 | LE | 热身 | 7582-7585 |
| 20 | burning | 2 | LE | 燃脂 | 7586-7589 |
| 22 | aerobic | 2 | LE | 有氧 | 7590-7593 |
| 24 | anaerobic | 2 | LE | 无氧 | 7594-7597 |
| 26 | extreme | 2 | LE | 极限 | 7598-7601 |
| 28 | step | 4 | LE | 步数 | 7602-7609 |
| 32 | distance | 4 | LE | 距离 | 7610-7617 |
| 36 | calorie | 4 | LE | 卡路里(**×1000**,见7532) | 7618-7625 |
| 40 | avg_pace | 2 | LE | 平均配速 | 7626-7629 |
| 42 | avg_stride_freq | 1 | - | 平均步频 | 7630 |
| 43 | avg_stride_len | 1 | - | 平均步幅 | 7634 |
| 44 | avg_speed | 2 | LE | 平均速度 | 7636-7639 |
| 46 | max_speed | 2 | LE | 最大速度 | 7640-7643 |
| 48 | min_speed | 2 | LE | 最小速度 | 7644-7647 |

note：紧随整块拷贝 `S_SPORT_DATA_T`(紧凑格式 `SPORT_ITEM_COMPACT_FORMAT`)，超 `s_max_count` 分块拼接。总长=summary+data_count×sizeof(S_SPORT_DATA_T)。**可变长，结构含 tl/th/hr 需确认**。

**BLOOD_PRESSURE(0x07) 血压（`7745-7854`）** — 通用头后整块拷 `PRESS_DATA_4G_INFO`(`SysDataMgr.h:205-225`，偏移相对 4)：

| 偏移(相对4) | 字段 | 长度 | 字节序 | 含义 |
|---|---|---|---|---|
| +0 | utc | 4 | LE | 时间 |
| +4 | Level_h | 1 | - | 收缩压 |
| +5 | Level_l | 1 | - | 舒张压 |
| +6 | hr | 1 | - | 心率 |
| +7 | angle_statue:2/reserved:6 | 1 | - | 姿势角度状态 |
| +8 | Acc_x_before | 4 | LE | 加速度(测前) |
| +12 | Acc_y_before | 4 | LE | |
| +16 | Acc_z_before | 4 | LE | |
| +20 | Acc_x_after | 4 | LE | 加速度(测后) |
| +24 | Acc_y_after | 4 | LE | |
| +28 | Acc_z_after | 4 | LE | |
| +32 | extend_flags | 2 | LE | 0x1/0x5 时带原始波形 |
| +34 | Info_len | 2 | LE | |
| +36 | Version | 4 | LE | |
| +40 | Err_code | 4 | LE | |
| +44 | reserved2[2] | 8 | LE | |

原始波形(可变长)：`extend_flags==0x1或0x5` 时紧随 `blp_raw_len(2,LE)`+原始数据(≤20KB)(`7829-7842`)。

**ECG(0x08) 心电（`7855-7958`）** — 通用头后整块 `ECG_DATA_DATA_INFO` 头(`AppDataMgr.h:564-575`，偏移相对 4)：UTC(4,LE,+0)+count(2,LE,+4,采样点数)+sample_rate(2,LE,+6)+symptom(2,LE,+8)+reserved2(2,LE,+10)。采样数组：count 个 `ECG_DATA_NOTE_DATA_INFO`(每点 4B：Val 16bit+reserved 16bit,`AppDataMgr.h:557-562`)，读取上限 `(30*250)*2+sizeof(头)`。`count==0` 回空。**可变长**。

**SSHR(0x0B) 静息心率（`7959-8051`）** — summary：UTC(4,LE)+count(2,LE)+reserved1(1)+reserved2(1)。note 每条 12B：start_utc(4,LE)+end_utc(4,LE)+value(2,LE,静息HR bpm)+context(1)+confidence(1)。

**SHRV(0x0C) HRV（`8052-8144`）** — 与 SSHR 同构：summary=UTC(4)+count(2)+reserved1(1)+reserved2(1)；note 每条 12B=start_utc(4)+end_utc(4)+value(2,HRV ms)+context(1)+confidence(1)。

**SDETECT(0x0E) 心脏健康检测（`8145-8242`）** — summary=UTC(4)+count(2)+reserved1(1)+reserved2(1)。note 每条 16B：

| 偏移 | 字段 | 长度 | 字节序 | 含义 |
|---|---|---|---|---|
| +0 | start_utc | 4 | LE | |
| +4 | end_utc | 4 | LE | |
| +8 | hr | 2 | LE | 固定 0(BLE 未携带) |
| +10 | value | 1 | - | 检测类型 |
| +11 | reserved | 1 | - | |
| +12 | context | 1 | - | |
| +13 | confidence | 1 | - | |
| +14 | rri_len | 2 | LE | 固定 0(BLE 未携带 RRI) |

**SRRI(0x0D) RRI（`8243-8387`，单文件流式）** — summary 为 8 字节：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | type=0x0D | 1 | - | | 8323 |
| 1 | count=1 | 1 | - | | 8324 |
| 2 | len | 2 | LE | uiParamLen-4 | 8334-8335 |
| 4 | lastEnd_utc | 4 | LE | 本切片最后节点 end_utc | 8325-8328 |
| 8 | nodeCnt | 2 | LE | 本切片节点数 | 8329-8330 |
| 10 | totalrri | 2 | LE | 本切片 RRI 总个数 | 8331-8332 |
| 12.. | node 数组 | 可变 | LE | 原始 `SRRI_NOTE_DATA_INFO`+rri_cnt 个 U16 整块拷贝 | 8287-8289 |

单节点 `SRRI_NOTE_DATA_INFO`(`AppDataMgr.h:676-685`，16B 头+rri_cnt×2B)：start_utc(4)+end_utc(4)+reserved(2)+context(1)+confidence(1)+rri_cnt(4)+rri_values[rri_cnt](U16,LE)。合法性 `rri_cnt∈[1,64]`。**切片上限 10KB**。

**BP_TREND(0x09) PPG 血压趋势（`8388-8486`）** — summary：node_count(2,LE,偏移4)+reserved(2,LE,偏移6,=0)。note(变长，每条 12B 固定+fea_count×4B float)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 |
|---|---|---|---|---|
| +0 | utc_time | 4 | LE | |
| +4 | time_peroid | 2 | LE | 时间周期 |
| +6 | sbp_value | 1 | - | 收缩压 |
| +7 | dbp_value | 1 | - | 舒张压 |
| +8 | fea_count | 4 | LE | 特征个数(≤4) |
| +12 | fea_all[] | fea_count×4 | LE(float) | 特征值数组 |

**BP_TREND_EX(0x0F) 血压趋势扩展（`8487-8609`）** — 仅当天(today==0)有效。一次上报一个 sub_type，App 拉两次分取昼夜节律(0)/呼吸暂停(1)。包结构 20 字节：

| 偏移 | 字段 | 长度 | 字节序 | dipping(sub0) | apnea(sub1) | 文件:行号 |
|---|---|---|---|---|---|---|
| 4 | sub_type | 2 | LE | 0=昼夜节律 | 1=呼吸暂停 | 8516-8517/8569-8570 |
| 6 | node_count | 2 | LE | 固定 1 | 固定 1 | 8518-8519/8571-8572 |
| 8 | start_utc | 4 | LE | dipping start_utc | sleep_in utc | 8520-8524/8573-8577 |
| 12 | end_utc | 4 | LE | dipping end_utc | 当前 utc | 8525-8529/8578-8582 |
| 16 | result | 1 | - | 0未知/1勺型/2非勺型 | osa_level 0未知/1无/2轻/3中/4重 | 8530/8583 |
| 17 | val1 | 1 | - | sbp_drop_ratio | AHI | 8531/8584 |
| 18 | val2 | 1 | - | dbp_drop_ratio | resp_rate | 8532/8585 |
| 19 | reserved | 1 | - | 0 | 0 | 8533/8586 |

算法结果不清除，重复发送直到 App ACK 确认对应 utc。

---

## 五、BCMD_DEV_CTRL (0x07) 设备控制（行 2153–2336）★维护类核心

Cmd1=`BCMD_DEV_CTRL=0x07`(`ble2appEx.h:96`)。Key 枚举 `ENUM_B2A_DEV_CTRL_KEY_TYPE`(`ble2appEx.h:430-446`)：

| Key | 名称 | 方向 | 说明 |
|---|---|---|---|
| 0x01 | POWER_OFF | App→设备 | 关机 |
| 0x02 | RESET | App→设备 | 重启 |
| 0x03 | RESTORE | App→设备 | 恢复出厂 |
| 0x04 | FIND | App→设备 | 查找手表(开始) |
| 0x05 | FIND_END | App→设备 | 查找手表(结束) |
| 0x06 | FIND_PHONE_END | App→设备 | 查找手机(结束)回执 |
| 0x07 | FILE_LOG | App→设备 | 请求拉取日志文件 |
| 0x08 | BLE_LOG | App→设备 | BLE 实时日志(`#if 0` 未启用) |
| 0x09 | DEV_ACK_FILE_LOG | 设备→App | 日志分块回传 |
| 0x0A | FILE_ALGO | App→设备 | 请求拉取算法特征文件 |
| 0x0B | DEV_ACK_FILE_ALGO | 设备→App | 算法特征回传 |

分发 `ble2appWrap.c:12956-13028`。通用约定：POWER_OFF/RESET/RESTORE/FIND/FIND_END/FIND_PHONE_END 请求 payload **为空**；若帧头带 `EHST_ACK` 回 `B2A_CommAck(cmd,key,EBEC_SUCC)`（1 字节错误码，`13010-13016/403-410`）。

### 0x01 POWER_OFF 关机（行 2178–2186）
请求：无 payload，仅 `SYS_PowerOff()`(`12958-12962`)。响应：`ucIsOk=0`(12961)→**不发 Ack**，直接关机。

### 0x02 RESET 重启（行 2190–2198）
请求：无 payload，`SYS_Reset()`(`12963-12968`)。响应：`ucIsOk=0`(12967)→不发 Ack，直接重启。

### 0x03 RESTORE 恢复出厂（行 2202–2210）
请求：无 payload，`SYS_Restore()`(`12969-12973`)。响应：`ucIsOk=0`(12972)→不发 Ack。

### 0x04 FIND 查找手表开始 / 0x05 FIND_END / 0x06 FIND_PHONE_END（行 2214–2246）
- FIND：发 `UI_EVT_FIND_WATCH_START`(`12974-12978`)，无 payload，不发 Ack(`ucIsOk=0`,12977)。
- FIND_END：发 `UI_EVT_FIND_WATCH_END`(`12980-12984`)，不发 Ack(12983)。
- FIND_PHONE_END：`APP_Find_Tip_indAck_proc()`+`watch_AppEvtHandler(UI_EVT_FIND_PHONE_END,...)`(`12985-12991`)，不发 Ack(12990)。

### 0x07 FILE_LOG 请求拉取日志文件（行 2250–2289）★维护类日志拉取
请求：分发处 `SDM_SetFileLogCtrlData()` 被 `#if 0`；实际在 `13018-13022` 调 `B2A_FileLogCtrlAck(pszParam,uiParamLen)`。仅第 0 字节 model 被使用。

**示例帧(行 2254，原样抄录)**：`bb 02 09 00 37 bd 00 00 07 07 05 00 01 00 00 00 00`（payload=`01 00 00 00 00`，注释 `ble2appWrap.c:11329`）。

请求 payload：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | model | 1 | ==1：拉取文件日志(now+bak)；!=1：hardfault 分支(dump 已 `#if 0` mute，totalLen 恒 0 不发包) | ble2appWrap.c:11359,11421,11534 |
| 1..(n-1) | 透传扩展参数 | n-1 | 未被解析，原样回填分块包头(示例 `00 00 00 00`，含义未确认) | ble2appWrap.c:11364,11473,11499 |

`uiInParamLen=uiLen`(整个请求 payload 长度)作为每个回传分块前缀头长度。

响应(设备→App)：`B2A_FileLogCtrlAck` 以 `Cmd=0x07/Key=0x09` 逐块 `B2A_MakePkt` 主动发出（先 hardfault 包(通常不发)，再 now 各分块，再 bak 各分块）。分块 payload 通用结构：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0..(uiInParamLen-1) | 请求前缀回填 | uiInParamLen | 原样拷回请求 payload(含 model) | ble2appWrap.c:11473,11565 |
| uiInParamLen..end | 日志数据块 | logChunkLen | 日志文件内容分片(PSRAM tempBuf)，每片≤maxLogChunkLen=sizeof(pszParam)-8-uiInParamLen(pszParam=237B) | ble2appWrap.c:11322,11479,11499,11513-11514,11571,11591,11605-11606 |

整包 `uiParamLen=uiInParamLen+logChunkLen`。分块**不带偏移/序号字段**，App 按到达顺序拼接。文件名：now=`MAC_SDM_EIOT_LOG_NOW`(11423)，bak=`MAC_SDM_EIOT_LOG_BAK`(11536)。hardfault 特殊包为死代码且布局不自洽（`model!=1`分支，`#if 0` 禁用 dump，totalLen 恒 0 提前 break，`11365-11389`），**不据此实现，需确认**。旧版 Ack 分支(13010-13016)：FILE_LOG 进入时 `ucIsOk` 保持 1，若带 EHST_ACK 会先回 1 字节 EBEC_SUCC 通用 Ack，再发分块包。进度 `log_update_progress()`(25%→100%) 仅 UI，不在 payload。

### 0x08 BLE_LOG BLE 实时日志（未启用，行 2293–2301）
分发分支 `SYS_SetBleLogCtrl(pszParam,uiParamLen)` 整段 `#if 0`(12993-13003)，进 case 落 else 打印 NOT SUPPORT，`ucIsOk=0`(13006) 不发 Ack。payload 结构**需确认**。

### 0x09 DEV_ACK_FILE_LOG 日志分块回传（设备→App，行 2305–2307）
设备主动上行，无请求 handler。payload 结构即 0x07 响应表。Cmd/Key 固定 `0x07/0x09`(11310)。

### 0x0A FILE_ALGO 请求拉取算法特征文件（行 2311–2331）
请求：分发 `13023-13026` 调 `B2A_FileAlgoCtrlAck`(`11657-11684`)，首字节为特征文件类型。

**示例帧(行 2315，原样抄录)**：`BB 02 06 00 A6 5E 00 00 07 0A 02 00 01 00`（payload=`01 00`，注释 `ble2appWrap.c:11674`）。

请求 payload：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | featureFileType | 1 | ENUM_B2A_DEV_FEA_FILE_TYPE(`ble2appEx.h:422-428`)：0x00=RPT_FEA_FILE_MIN,0x01=RPT_FEA_FILE_SLEEP(睡眠特征) | ble2appWrap.c:11671-11672 |
| 1..end | 透传扩展 | 可变 | 仅 default 分支回传，SLEEP 不用(示例尾 `00`，含义未确认) | ble2appWrap.c:11679 |

校验：`pszData==NULL||uiLen==0` 直接返回。响应：
- `SLEEP(0x01)`：`skg_sleep_manager_features_send()` 由 SKG 睡眠算法自行组织发送，结构**需确认**。
- default：`B2A_MakePkt(0x07,0x0B,pszData,uiLen,…)` 原样回显请求 payload 作 ACK。

### 0x0B DEV_ACK_FILE_ALGO 算法特征回传（设备→App，行 2334–2336）
设备主动上行。default 分支以 `0x07/0x0B` 回显请求 payload；SLEEP 实际特征数据由 `skg_sleep_manager_features_send()` 组织，字段**需确认**。

---

## 六、BCMD_TEST (0x08) 工厂测试（行 2339–2406）

`BCMD_TEST`(`ble2appEx.h:97`，实测 0x08)。Key 枚举 `ENUM_B2A_TEST_KEY_TYPE`(`ble2appEx.h:108-117`)：`BKEY_TEST_MIN=0x00`、`ENTER_FACTORY_MODE=0x01`、`MOTO_ON=0x02`、`MOTO_OFF=0x03`。分发 `case BCMD_TEST`(`13031`)**仅实现 ENTER_FACTORY_MODE**，MOTO_ON/MOTO_OFF 无处理代码。

### 0x01 ENTER_FACTORY_MODE 进入/退出工厂模式（行 2355–2384）
handler `13037-13112`，仅取 `pszParam[0]`：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucMode | 1 | 0x00=退出;0x01=BLE;0x02=UART;0x03=apollo;0x04=ASR;0x05=ECG | ble2appWrap.c:13041,13055,13063,13069,13073,13077 |

动作：0x00 退出(停采集/清标志/关 LCD)；0x01 BLE(置标志/启 HW_TEST/写 `AT+CFun=1`)；0x02 UART(置 uart 工厂标志，影响响应通道)；0x03 apollo(仅打印)；0x04 ASR(仅打印)；0x05 ECG(LCD 常亮/UI_MSG_SCR_ON/切工厂 ECG)。其余(≥0x06)不命中分支但带 EHST_ACK 仍回成功。

响应：仅带 `EHST_ACK`(13107) 才应答，经 `Factory_B2A_CommAck(...,EBEC_SUCC,...)`(13110)。通道：UART 工厂模式(`get_factory_uartmode_flg()==1`)走 `Uart_B2A_MakePkt`(串口)，否则 `B2A_MakePkt`(BLE)(`461-464`)。

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucResult | 1 | 恒 EBEC_SUCC=0x00 | ble2appWrap.c:13110; ble2appEx.h:49-70 |

### 0x02 MOTO_ON 马达开 / 0x03 MOTO_OFF 马达关（行 2388–2406）
枚举存在但 `case BCMD_TEST` 内**未实现**。请求：无 handler，不解析入参，无动作(即使带 EHST_ACK 也不应答)。响应：无。**预留未实现，需确认**。

---

## 七、BCMD_FILE_TRANS (0x0F) 文件传输/OTA（行 2409–2612）★维护类 OTA 核心

`BCMD_FILE_TRANS=0x0F`(`ble2appEx.h:99`)。分发 `case BCMD_FILE_TRANS`(`13118`)。Key：

| Key | 名称 | 值 | 方向/handler |
|---|---|---|---|
| 0x01 | FILE_TRANS_START | 1 | App→设备 `B2A_FileTransStartAck`(4121;分发13120) |
| 0x02 | FILE_TRANS | 2 | App→设备数据块 `B2A_FileTransDataAck3`(4609;分发13124/13131) |
| 0x03 | FILE_TRANS_STOP | 3 | App→设备 `B2A_FileTransStopAck`(5380;分发13157) |
| 0x04 | FILE_TRANS_REQ | 4 | App→设备 `B2A_FileTransReqAck`(3559;分发13161) |
| 0x06 | FILE_TRANS_OFFSET | 6 | App→设备 `B2A_FileTransGetOffsetAck`(5624;分发13176) |

Key 枚举 `ble2appEx.h:451-461`(0x05 跳过)。响应成败语义由外层 `ucStatus` 承载：`B2A_CommAck`→ucStatus=0x00(成功包,payload 为错误码)；`B2A_CommAckError`→ucStatus=EHST_FAIL(0x01)。

**枚举速查**：
- ModuleId `ENUM_B2A_FILE_TRANS_MODULE_ID_TYPE`(`ble2appEx.h:464-477`)：OTA=1,GPS_FW=2,WATCH_BG=3,MODEM_FW=4,SECBL_FW=5,BP_FW=6。
- FileType `ENUM_B2A_FT_FILE_TYPE`(`ble2appEx.h:480-495`)：BOOTLOADER=1,FW=2,RES=3,MODEM=4,GPS_FW=5,GPS_HIS=6,FONT=7,WATCH_BG=8,WATCH_CFG=9,BP_FW=10。
- 请求状态 `ENUM_B2A_FT_REQ_STATUS_TYPE`(`ble2appEx.h:498-509`)：BFRS_OK=0,DISK_FULL=1,BUSY=2,MEMORY=3。
- 底层结构 `FILE_TRANS_DATA_INFO`(`SysDataMgr.h:722-752`)。

### 0x04 FILE_TRANS_REQ 传输请求（行 2446–2492）
handler `B2A_FileTransReqAck`(3559)。分发前若外层带 `EHST_OTA_PART(0x80)` 则 `g_stFtData.ucOtaMode=0xFF`(差分,`13165-13172`)。

请求 payload(`3665-3676`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ucModuleId | 1 | - | BMID_*(1=OTA…6=BP) | ble2appWrap.c:3666 |
| 1 | ucIsOffset | 1 | - | >0=按已存偏移续传;0=从头 | ble2appWrap.c:3669,3619-3626 |
| 2 | ucFileCount | 1 | - | 会话文件数 | ble2appWrap.c:3670 |
| 3 | ucReserved | 1 | - | 保留 | ble2appWrap.c:3671 |
| 4 | ulTotalSize | 4 | LE | 所有文件总大小;不足回 BFRS_DISK_FULL | ble2appWrap.c:3673-3676,3692-3694 |

OTA 模式从 EHST_OTA_PART 推断(差分 EOTM_DIFF/全量 EOTM_FULL,`3651-3662`)。

响应(两路径，格式相同，共 12 字节)：
- (A) 出错(非 BFRS_OK)本函数直接组包(`3706-3775`)；(B) 成功(BFRS_OK)落盘 `SDM_SetFileTransReqData`(3781)由 MMI 确认后 `B2A_FileTransReqAckFromMMI`(3876)发送(`4029` 起)。MODEM_FW(4) 成功路径同步调 MMI(3833)。

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号(A/B) |
|---|---|---|---|---|---|
| 0 | ucStatus | 1 | - | BFRS_*(1=DISK_FULL,2=BUSY,3=MEMORY;0=OK) | 3709/4029 |
| 1 | ucModuleId | 1 | - | 回显模块 ID | 3711/4031 |
| 2 | ucFileCount | 1 | - | 文件数 | 3713/4033 |
| 3 | ucCurrFileIdx | 1 | - | 当前文件序号(续传为已完成索引) | 3715/4035 |
| 4 | ulSliceMaxSize | 4 | LE | 单分片最大字节(MAC_SLICE_SIZE_FROM_MCU 定义时=stFtData.ulSliceMaxSize,否则=iMaxParamPktLen) | 3719-3737/4039-4057 |
| 8 | ulOffset | 4 | LE | 断点续传偏移(ucIsOffset>0 为已收字节,否则 0) | 3740-3747/4060-4067 |

### 0x01 FILE_TRANS_START 单个文件开始（行 2496–2522）
handler `B2A_FileTransStartAck`(4121)。请求 payload(`4149-4174`)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulCurrFileSize | 4 | LE | 当前文件总大小 | ble2appWrap.c:4150 |
| 4 | ulOffset | 4 | LE | 续传起始偏移(0="wb",>0="ab+") | ble2appWrap.c:4153,4821-4838 |
| 8 | ulSliceSize | 4 | LE | 分片大小(据此分配 g_pszFtBuff) | ble2appWrap.c:4156,4207 |
| 12 | ucType | 1 | - | BFTT_*(1=BOOTLOADER,2=FW,3=RES,7=FONT,8=WATCH_BG,9=WATCH_CFG,10=BP_FW…) | ble2appWrap.c:4159 |
| 13 | ucZipFlag | 1 | - | 压缩标志 | ble2appWrap.c:4161 |
| 14 | uiFileNameLen | 2 | LE | 文件名长度(>13 截 12) | ble2appWrap.c:4164-4172 |
| 16 | szFile | uiFileNameLen(可变) | - | 文件名 ASCII(不含结尾0,存 szFile[13]) | ble2appWrap.c:4173-4174 |

总长=16+uiFileNameLen。响应：通用应答 1 字节错误码：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | ucResult | 1 | EBEC_SUCC(0)=就绪;EBEC_MEMORY(4)=缓冲失败或 ulSliceSize>ulSliceMaxSize | ble2appWrap.c:4210-4226,407 |

### 0x02 FILE_TRANS 数据块（行 2526–2560）
handler 默认 `B2A_FileTransDataAck`，当前构建(定义 `MAC_B2A_RECV_INDEX_ACK`+`MAC_B2A_RECV_STATUS_ACK`)为 `B2A_FileTransDataAck3`(4609)。分片由外层 `EHST_IS_MULTI_PKT(0x04)/EHST_MULTI_PKT_END(0x08)` 拼包。

请求 payload：纯文件二进制，无字段结构：

| 偏移 | 字段 | 长度 | 含义 | 文件:行号 |
|---|---|---|---|---|
| 0 | data | uiLen(可变) | 原始文件字节流(累加 ulCurrSliceRecvLen/ulCurrFileRecvLen) | ble2appWrap.c:4666-4668,4681-4683 |

响应(分片收齐后)：
- (A) 成功写盘 `B2A_MakePkt`(ucStatus=0x00,`4967-5009`)，9 字节：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulCurrSliceRecvLen | 4 | LE | 本分片实际收到字节数 | 4972-4979 |
| 4 | ulCheckSum | 4 | LE | 本分片逐字节累加和 | 4981-4988,4733-4736 |
| 8 | ucStatus | 1 | - | 固定 EHST_SUCC(0x00) | 4990 |

- (B) 出错 `B2A_CommAckError`(ucStatus=EHST_FAIL)，1 字节错误码：EBEC_MEMORY(4)=缓冲空/超限;EBEC_FAIL(1)=多包丢包;EBEC_FSUM_FAIL(13)=回读校验失败(`4674,4686,4694,4720,4858,4868,4948,5020`)。丢包保护：END 标志但累计<ulSliceSize 且文件未完→EBEC_FAIL+OTAR_ERROR 中止(`4712-4722,13139-13154`)。

### 0x03 FILE_TRANS_STOP 单个文件结束（行 2564–2586）
handler `B2A_FileTransStopAck`(5380)。请求：未解析任何字段(仅 hexdump)，可为空。响应：通用应答 1 字节：EBEC_SUCC(0)=结束 OK(`B2A_CommAck`);EBEC_FSUM_FAIL(13)=尺寸/资源校验失败(`B2A_CommAckError`)(`5484,5574,5464,5578`)。未传完(ucCurrFileIdx<ucFileCount)也回 EBEC_SUCC 提前返回;最后文件走资源校验(`MAC_RES_FILE_CHECK` 比对 ulCurrFileSize 与盘上大小)。

### 0x06 FILE_TRANS_OFFSET 查询续传偏移（行 2590–2612）
handler `B2A_FileTransGetOffsetAck`(5624)。请求：未解析(偏移取自 `SDM_GetFileTransData`)，可为空。响应 `B2A_MakePkt`(ucStatus=0x00)：

| 偏移 | 字段 | 长度 | 字节序 | 含义 | 文件:行号 |
|---|---|---|---|---|---|
| 0 | ulOffset | 4 | LE | 当前已接收字节偏移(续传起点) | ble2appWrap.c:5654-5661 |

内存不足改回 `B2A_CommAck(EBEC_MEMORY)`(1 字节,5696)。历史代码曾在偏移后追加文件名(`5663-5670`)现已 `#if 0`。

---

## 八、应用层错误码表 ENUM_B2A_ERROR_CODE_TYPE（`ble2appEx.h:47-71`，行 2617–2638）

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | EBEC_SUCC | 成功 |
| 1 | EBEC_FAIL | 失败 |
| 2 | EBEC_TIMEOUT | 超时 |
| 3 | EBEC_FORMAT | 格式错 |
| 4 | EBEC_MEMORY | 内存不足 |
| 5 | EBEC_NOT_SUPPORT | 不支持 |
| 6 | EBEC_PARAM | 参数错 |
| 7 | EBEC_BUSY | 忙 |
| 8 | EBEC_LOW_BAT | 低电 |
| 9 | EBEC_NO_DATA | 无数据 |
| 10 | EBEC_MD5 | MD5 校验失败 |
| 11 | EBEC_CRC | CRC 失败 |
| 12 | EBEC_FATAL | 致命错 |
| 13 | EBEC_FSUM_FAIL | 文件校验和失败 |

result 多为 payload 第 1 字节，经 `B2A_CommAck(cmd,key,result)`→`B2A_MakePkt`(`ble2appWrap.c:403-410`)。应答变体：`B2A_CommAckError`(带 EHST_FAIL,`413`)、`B2A_CommAckNoData`/`B2A_CommAckWithParam`(`424-443`)。

## 外层状态码表 ENUM_HEAD_STATUS_TYPE（`ble2appEx.h:26-43`，行 2439–2440）

| 值 | 名称 | 含义 |
|---|---|---|
| 0x00 | EHST_SUCC | 成功 |
| 0x01 | EHST_FAIL | 失败(被动 NAK) |
| 0x02 | EHST_ACK | 需应答 |
| 0x04 | EHST_IS_MULTI_PKT | 分片中间包 |
| 0x08 | EHST_MULTI_PKT_END | 分片结束包 |
| 0x80 | EHST_OTA_PART | OTA 差分标记 |

（另健康数据分包用 0x0c=末包标志，见通用尾部）

---

## 九、设备维护类命令汇总（本段 1300–2669 覆盖情况）

| 维护功能 | 命令 | 位置 | 说明 |
|---|---|---|---|
| 电量 | BCMD_IND 心跳 0x0C，payload 偏移 6 battery | 行 1701–1710 | `SYS_GetBatPower()`，1 字节 |
| 重启 | BCMD_DEV_CTRL 0x07/0x02 RESET | 行 2190–2198 | 无 payload，不回 Ack，`SYS_Reset()` |
| 关机 | BCMD_DEV_CTRL 0x07/0x01 POWER_OFF | 行 2178–2186 | 无 payload，不回 Ack，`SYS_PowerOff()` |
| 恢复出厂 | BCMD_DEV_CTRL 0x07/0x03 RESTORE | 行 2202–2210 | 无 payload，不回 Ack，`SYS_Restore()` |
| 日志拉取 | BCMD_DEV_CTRL 0x07/0x07 FILE_LOG + 0x09 回传 | 行 2250–2307 | model 字节选择;分块回传(前缀回填+数据块) |
| OTA/固件传输 | BCMD_FILE_TRANS 0x0F (REQ/START/DATA/STOP/OFFSET) | 行 2409–2612 | 完整会话协商+分片+续传+校验 |
| 算法特征拉取 | BCMD_DEV_CTRL 0x07/0x0A FILE_ALGO + 0x0B 回传 | 行 2311–2336 | 睡眠特征由 SKG 模块组织 |
| 医疗信息写 | BCMD_PUSH 0x04/0x07 MEDICAL_INFO | 行 1493–1507 | C 字符串≤64B，NVM 持久化 |

**本段未覆盖（应在手册上半部 <1300 行）**：时间同步、设备信息读取、固件版本读取、用户信息读写（身高/体重/性别/年龄/心率区间等）。这些通常在 `BCMD_SET`/`BCMD_GET` 命令族，不在本段范围。

---

## 十、抓包/调试与源码索引（行 2642–2668）

**BLE 空中抓包**：RX 特征 `0xFFE1`(写)、TX 特征 `0xFFE2`(notify)；BOND-only + Just Works，需捕获配对或导入 LTK。

**工厂 UART 注入(无手机)**：UART0 921600 8-N-1，先发进入产测帧 **`BB 02 03 00 2D 46 00 00 08 01 01`**（原样抄录），之后用 B2A 帧(`0xBB…`)交互；AT 命令以 `AT` 开头区分。

**发帧自检**：① SOF=0xBB；② uiLen=命令头+参数字节数(不含 8B 帧头)；③ uiCRC=对偏移 8 起 uiLen 字节算 **CRC16-CCITT-FALSE(0x1021/0xFFFF，无反转无 xorout)**；④ 首包带 4B 命令头，续包不带靠 uiIndex+多包 ID；⑤ 需应答置 EHST_ACK。**收帧**：CRC 不符回 EHST_FAIL(被动 NAK，无主动重传，需 App 重发)。

**源码索引关键项**：`ble2appEx.h:19-21`(SOF/帧头/CRC 偏移)`,26-43`(状态位)`,47-71`(错误码)`,74-81`(B2A_HEAD)`,85-104`(命令字)`,513-525`(命令头/szParam)；`ble2appEx.c:57-71`(MakePktHead)`,216-280`(MakePkt)`,555-673`(RecvData 解析+CRC)；`ble2appWrap.c:11698`(RecvDataHandle 入口)`,11771-11799`(分片)`,11886-13196`(各命令 case)`,403-443`(CommAck 系列)；`crc16_ccitt_false.c:14-42`(CRC16)`,45-67`(CRC8)；`System.c:2944-2952`(SYS_CRC16)`,3068-3089`(收发)。

**未决/说明(行 2664–2668)**：无独立超时重传状态机；OTA 期间非 FILE_TRANS 帧被丢弃(`ble2appWrap.c:11835-11852`)；4G(通道 C)不承载 B2A 帧(见 `manual-4g-protocol.md`)。§6 各命令逐字节由并行 agent 抠取并经一轮独立核验，复杂可变长结构与少量 BKEY 子项标注「需确认」。

（源文件：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\manual-b2a-complete.md`，本次读取行 1300–2669。）