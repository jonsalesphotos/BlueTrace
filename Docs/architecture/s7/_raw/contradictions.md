# S7 手表 B2A 协议文档 — 矛盾与陷阱猎手报告

> 审查对象：BLE传输层提取（[文件2]=`01-ble-protocol.md`、[文件3]=`manual-ble-protocol.md`、[文件4]=`BLE流程与Cordio主机栈分析.html`）、`b2a_protocol.h` 提取、`manual-b2a-complete.md` 上/下半部提取、`03-ota.md`/`04-logging.md` 提取、`02-app-protocol.md`/`05-scan-identify.md` 提取。
> 标注 ⚠️=会直接写错代码，❗=需回源码仲裁，ℹ️=需知晓的坑。

---

## 一、硬矛盾（文档间值/说法不一致）

### 1. ⚠️ CRC16 覆盖长度：`uiLen` 还是 `uiLen−8`？（全场最危险）
- **说法 A**：`b2a_protocol.h` 提取 §1.1 与 manual 上半部 §2 —— "CRC 覆盖**偏移 8 起的 `uiLen` 字节**"，且 `uiLen = 4 + paramLen`（首包）。
- **说法 B**：[文件2] §4.4 与 `02-app-protocol.md` §1.2 —— "范围 `CRC16(pszData+8, uiLen-8)`"；[文件2] §4.4 还写"接收侧做 `uiLen - sizeof(B2A_HEAD)` 计算"。
- **根因**：两个同名 `uiLen` 被混写——**帧头字段 `uiLen`（=帧头之后的字节数，不含 8B 头）** 与 **代码函数入参 `uiLen`（=整帧缓冲长度，含 8B 头）**。`ble2appEx.c:64/644` 里的 `-8` 是对整帧长做的，不是对帧头字段做的。
- **消歧**：以 manual 上半部行 71–79 的布局图为准：**CRC 范围 = [偏移8 .. 8+帧头uiLen−1]，帧头 uiLen 首包 = 4+paramLen**。任何出现 `uiLen-8` 的表述都应理解为"整帧长−8"。实现时给两个长度起不同变量名（`frameLen` vs `hdr.payloadLen`），并加断言 `frameLen == hdr.payloadLen + 8`（注意 PUSH_MSG 特例除外，见 §三.2）。

### 2. ⚠️ 配对安全等级：LESC 有没有？
- [文件2] §3.4 行106 / [文件3] §5.2 行187：`DM_AUTH_BOND_FLAG`，**仅绑定，无 SC 位**（`watch_main.c:137-144`，逐字节确认）。
- [文件4] §10 行336：**"Bonding + LESC"**。
- **消歧**：以 [文件3]（逐字节核对了实际编译分支）为准——**无 LESC**。App 端若强制要求 Secure Connections 会配对失败。[文件4] 是 Cordio watch 通用例程描述，不可作实现依据。

### 3. ⚠️ MTU 协商：谁发起？
- [文件2] §3.4 行103：表端主动 `AttcMtuReq(connId, 247)`。
- [文件3] §3 行80：**`AttcMtuReq` 已被注释**（@973/1028），改走 `SERVC_MSG_REQ_MTU` 服务消息，247 仅作达标**阈值校验**。
- [文件4] §8：只记录 `ATT_MTU_UPDATE_IND`，无值。
- **消歧**：以 [文件3]（修订版）为准。**实操结论：不能假设手表会主动发 MTU 请求，App/中央端必须自己发起 MTU 交换并请求 ≥247**，否则默认 MTU=23 下大帧 Notify 直接超限（见 §五.1）。watch 路径三份文档均标"未决"，上机前需抓包确认。

### 4. ⚠️ 生效 GATT 服务集合 + 广播 UUID 与真实属性表不符
- [文件2]/[文件3]：仅 **AMDTP + GH3X2X** 两组，`SvcCoreAddGroup`/`SvcDisAddGroup` 被 `#if 0`。
- [文件4] §9：列了 AMDTP+ANCS+HID+GATT Core+DIS/TIPC/ANPC/PASPC/HRPC+GH3X2X。
- **附加陷阱（文档自身未点破）**：广播数据 16-bit UUID 列表含 **DIS `0x180A`** 和 **`0xFFEB`**（[文件2] §3.5 行114），但 DIS 服务**未注册**、FFEB ACK 特征**未编译**（`svc_amdtp.c:93-97,175-200`）。即**广播承诺的服务在 GATT 表里不存在**。
- **消歧**：以 [文件2]/[文件3] 的编译开关收敛结论为准；扫描端可用 0x180A/0xFFEB 做识别指纹，但连接后**不要**尝试发现 DIS 或 FFEB，发现失败是正常的。

### 5. ⚠️ AMDTP UUID：16-bit 还是 128-bit 私有 base？
- `02-app-protocol.md` §1.2 写法是 "Service `0xFFE0` / RX `0xFFE1`(Write No Rsp) / TX `0xFFE2`(Notify)"，像标准 16-bit。
- [文件2]/[文件3] §1.1：实际是 **128-bit 厂商私有 base** `0000FFE0-3C17-D293-8E48-14FE2E4DA212`（`svc_amdtp.h:64-71`），**不是**蓝牙标准 base `xxxx-0000-1000-8000-00805F9B34FB`。
- **陷阱**：广播里放的是 16-bit 短码（空中字节 `E0 FF`），但**服务发现按 16-bit/标准 base 查 0xFFE0 会查不到**，必须用完整 128-bit UUID。
- **消歧**：以 `svc_amdtp.h:64-71`（[文件3] §5.1 行163-166 引用）为准；GH3X2X 服务反过来是**纯 16-bit**（见下条），两个服务的 UUID 体系不同，勿套用。

### 6. ❗ GH3X2X 服务：UUID 类型与 RX 属性
- UUID：[文件2]=128-bit（标准 base）；[文件3]=**纯 16-bit**（`factory_gh3x2x_ble_server.h:49-58` 的 `#else` 生效分支；128-bit 在 `#if USER_GH3X2X_BLE_UUID128BIT`(=0) 死分支）。数值等价，但发现方式不同。
- RX 属性：[文件2]="Write"；[文件3]="**WriteNoRsp | Write**"（`factory_gh3x2x_ble_server.c:49`）。
- **消歧**：均以 [文件3]（自称修订/精修）为准：16-bit 发现 + 两种写法均可。

### 7. ⚠️ CRC8 到底盖什么？"设备名 CRC8"是误导表述
- [文件3] §4.5 行151：CRC8 "用于广播/扫描数据（**设备名 CRC8**）"。
- `05-scan-identify.md`：设备名 17 字节**无 CRC8**；CRC8 是 **ADV_IND 厂商数据（AD 0xFF）的末字节**，覆盖 `flags+device_type+MAC` 共 **12 字节**；**SCAN_RSP 完全不含 CRC8**。[文件2] §3.5 行116-119 也确认"CRC8 只回填广播数据"。
- **消歧**：以 05-scan / [文件2] 行116-119 为准。[文件3] 的括注"(设备名 CRC8)"是历史遗留错误措辞，照它实现（对名字算 CRC8）必错。

### 8. ℹ️ 设备名后缀大小写
- `05-scan-identify.md`：**4 位大写 hex**；[文件4] 写作 `"SKG WATCH S7-xxxx"`（小写占位）。
- **消歧**：以 `SYS_One2Two()`（`System.c:176-187`）实现为准；做名字匹配时**忽略大小写**最稳。另注意后缀 = `hex(MAC[1])+hex(MAC[0])`（两个 MAC 字节 → 4 字符），不是"MAC 末 2 字符"、不是倒序（[文件2] §3.5 行115 特别辟谣）。

### 9. ❗ `BKEY_DEV_CTRL_FILE_LOG` 数值：事实还是推断？
- `b2a_protocol.h` 提取 §2.8 把 `0x07=FILE_LOG` 列为事实；`04-logging.md`（04:253）却明说"**数值未见显式赋值，按枚举顺序推断**"。
- **消歧**：打开 `ble2appEx.h:430-446` 数一遍枚举是否有隐式跳号（前面已有 0x05 缺 `BKEY_FILE_TRANS` 0x05 跳号先例，见 §三.6），否则拉日志命令发错 key。

### 10. ⚠️ Key 0x14：GET 与 SET 偏移 6 语义不同
- GET_BOOL_TEST_SET（7B）：`[6] ucSnsTime`（**测量间隔**）。
- SET_BOOL_TEST_REMIND（8B）：`[6] snooze`（>0 置 bSnoose）、`[7] reserve`。
- 连名字都不同（`_TEST_SET` vs `_TEST_REMIND`）。**同一结构体读写复用必错**。出处：manual 上半部 GET 表 0x14 / SET 表 0x14。

---

## 二、文档内部自相矛盾 / 可疑示例帧

### 1. ⚠️ 产测握手帧违反自家帧格式规则（已验证非抄写错误）
manual 上半部行 22 示例：`BB 02 03 00 2D 46 00 00 08 01 01`。
解析：SOF=0xBB，status=0x02(EHST_ACK)，**uiLen=0x0003**，CRC=0x462D，index=0，其后仅 3 字节 `08 01 01`。
- 与 §2 规则"首包 = 8B 帧头 + **4B 命令头** + payload，uiLen = 4+paramLen ≥ 4"**直接冲突**（uiLen=3 < 4，命令头被截断，2 字节 `uiParamLen` 只剩 1 字节）。
- 我已用 CRC16-CCITT-FALSE（0x1021/0xFFFF）实算：`CRC16(08 01 01) = 0x462D`，与帧内声明一致——**说明这帧就是这么发的，不是抄录笔误**。
- **消歧/实现建议**：解析器**不得** `assert(uiLen >= sizeof(B2A_DATA_CMD))`，也不得无条件读 4B 命令头（会越界读 1 字节）；对 `BCMD_TEST` 路径按 `min(uiLen,4)` 容错。回源码看 `B2A_RecvData`（`ble2appEx.c:555-673`）与 `pc_factory_mode_main.c:1480` 确认真实容忍行为。

### 2. ❗ RPT_DATA 示例帧参数字节数对不上
manual 下半部行 1888：`...86 03 04 00 00 00 00 00 00` —— paramLen=4（`04 00`）但其后列了 **5 个字节**。按 `B2A_RPT_DATA_REQ_PARAM`（4B：type/operate/today/reserve）应只有 4 字节。第二例 `...86 03 04 00 0A 00 00 00 00` 同病。**消歧**：多出的 `00` 疑为抄录残渣；以 4B 结构体（`ble2appEx.h:390-396`）为准，但组包时按 paramLen=4 发，勿照抄示例发 5 字节。

### 3. ⚠️ HEALTH_ALERT 多包状态位表述自相矛盾
manual 下半部 §IND 0x0E："单包时状态 `EHST_SUCC`；超长多包（含末包）状态**均 0x00（注意：非 EHST_SUCC）**"——可 `EHST_SUCC` 本来就 =0x00，括注不成立。真正想说的疑是：**多包帧不带 `EHST_IS_MULTI_PKT(0x04)/MULTI_PKT_END(0x08)` 标志**（与 RPT_DATA 分包"首/续包 0x04、末包 0x0C"的规则相悖）。
- **后果**：App 端若靠 status 位重组 HEALTH_ALERT 分片，将把每片当独立帧。
- **消歧**：回读 `ble2appWrap.c:3343-3388` 确认多包时 status 实值；重组 HEALTH_ALERT 可能只能靠 `uiIndex` 递增 + 内层 paramLen 总长。

### 4. ⚠️ RPT_DATA_START 响应是"文档如实记录的固件 bug"
被选中类型槽位只写 2B（days1B+size1B），未选中写 5B（days1B+size4B），槽位偏移随选中数漂移，且 days/size 全为占位 0（`ble2appWrap.c:5752-5882`，两份提取一致）。**勿按固定偏移解析该响应**；实际上其内容全 0，建议直接忽略 body，只当"开始同步"信号用。

### 5. ⚠️ timezone 符号性前后不一 + 0 哨兵吞掉 UTC+0
- GET 响应（`B2A_GET_DEV_DATE_TIME_RESP_T`）：`timezone` 为 **int8 有符号**。
- SET 请求（`B2A_SET_DEV_DATE_TIME_REQ_T`）：`timezone` 为 **uint8**，且 "**0=保持本地不改**"。
- 后果：西半球负时区在 SET 侧如何编码未定义（按补码发 0xF4=-12？）；且 **UTC+0 时区无法通过 SET 下发**（0 被当哨兵）。
- **消歧**：以 GET 侧 int8 语义为准按补码收发；UTC+0 场景需与固件确认（或回读 GET 验证）。出处：`b2a_protocol.h` 提取 §4.1。

---

## 三、易误读字段（符号/端序/长度语义/覆盖范围）

### 1. ⚠️ 嵌套双 `uiLen` + 分片时二者脱钩
外层 `B2A_HEAD.uiLen` 与内层 `B2A_DATA_CMD.uiLen(paramLen)` **同名**。单包首帧满足 `外层 = 内层+4`；**分片时不满足**：分包发送首包 "paramlen=**总长**"（manual 下半部 `8616-8655`），即**内层 paramLen = 全部分片拼起来的总参数长，外层 uiLen = 本片长度**。校验 `uiLen == paramLen+4` 只能用于无 `EHST_IS_MULTI_PKT` 的帧。

### 2. ⚠️ `ucStatus` 是"位标志 + 2bit ID + 数值 0"混合域
`EHST_SUCC=0x00` 不是位，`if (status & EHST_SUCC)` 恒假；多包 ID 占 bit[5:4]；`EHST_OTA_PART=0x80`。判断一律用掩码：`(status & 0x04)` 判分片、`(status>>4)&0x03` 取 ID，**禁止等值比较**。另有帧层特例：**Cmd=0x04/Key=0x02（PUSH_MSG）只校 SOF、不校 CRC 与长度**（`ble2appEx.c:616-623`）——解析器需为它单开豁免分支。

### 3. ⚠️ 匈牙利前缀全面不可信
`ulReserve`(1B, SET_GOALS/PERSON)、`uiBirthMonth/uiBirthDay`(各1B)、IND 各响应的 `ulType/ulStatus/ulFlag`(多为 **1B**，仅 SCIENTIFICMEASURE/SPORTS_DATA/SETTING 是 4B LE)。**以字段表的"长度"列为准，不看前缀**。IND 里同名 `ulType` 在不同 Key 下 1B/4B 混用，是最容易复制粘贴出错的地方。

### 4. ⚠️ GET/SET 同 Key 布局不对称（勿共用结构体）
| Key | GET 长度 | SET 长度 |
|---|---|---|
| PERSON_DATA 0x04 | 7B | 8B(+reserve) |
| GOALS 0x03 | 15B | 16B(+reserve) |
| SEDENTARY/DRINK 0x07/0x08 | 13B | 16B(+3B reserve) |
| QUIET/HRSO 0x09/0x0D | 6B | 8B |
| SPO2/PRESSURE_REMIND 0x0F/0x10 | 2B | 4B |
| SLEEP_REMIND 0x13 | 11B | 12B |
| MEDICINE_REMIND 0x12 | 6B/条（**推断**） | 8B/条 |
另：SET 侧 SPO2/PRESSURE 复用 HR_MODE 8B 结构但 interval=0 时分别强制 1/30/10；DRINK_WATER≡SEDENTARY 结构别名。

### 5. ⚠️ 字节序/字段顺序陷阱集
- 广播 16-bit UUID 列表**小端**：0xFFE0 空中是 `E0 FF`；AD type 是 `0x03` 不是 `0x02`。
- 厂商数据 MAC = `[0..5]` **原序（LSB-first，与空中 AdvA 一致）**；设备名只取 `[1][0]`——两处取法不同，勿混用（05-scan §2.3 明示）。
- `BOND_REQ` 应答 71B 字段顺序 **result,MAC,IMEI,ICCID,SN,AuthCode** ≠ `GET_SN_INFO` 59B 的 **DevType,SN,MAC,IMEI,ICCID**——同一批字段两种排列，复制解析代码必错。
- MAC/IMEI/ICCID 的正反序文档自标"需与 App 端确认"（`b2a_protocol.h` 提取行 573-574,609）——上机抓包定一次，写进代码注释。

### 6. ℹ️ Key 空间跳号与高位命令
- GET 无 0x29（0x28→0x2A）；SET 却有 0x29；FILE_TRANS 缺 0x05（0x04→0x06）。**枚举按顺序数会错位**。
- `0x86 = 0x80|0x06`：解析若做 `cmd &= 0x7F` 归一化，注意两者"无数据"应答不同——0x06 回空参 `B2A_CommAck`，**0x86 回 `B2A_CommAckWithParam`（回显请求 4B 且 `param[1]=0` 作无新数据标志）**。

### 7. ⚠️ 六种校验并存，别拿错
| 场景 | 算法 | 覆盖 |
|---|---|---|
| B2A 帧 | CRC16-CCITT-FALSE (0x1021/0xFFFF) | 仅偏移 8 起的 payload |
| 广播厂商数据 | CRC8 (0x07/0) | flags+type+MAC 12B |
| FILE_TRANS 数据响应 ulCheckSum | **字节累加和 u32** | 本分片数据 |
| OTA-B SecBL | 累加和（非 CRC） | 写入块 |
| OTA-C per-packet fcs | 字节累加和 u32（**非 CRC16、不盖整包**） | buf 的 len 字节 |
| OTA-C 整文件 / AMOTA | CRC16(crc16_table,0xFFFF) / CRC32 | 升级文件 / 每包尾 |

### 8. ℹ️ 其它单点
- 心跳结构命名反直觉：设备→App 叫 `..._RESP_T`，App 应答叫 `..._REQ_T`；两者同 8B 但偏移 6 一个是 `battery` 一个是 `network_status`，不可互拷。
- PUSH_PHONE `type` 命名空间有隐藏值：4(挂断)+前次1(来电) 会被改写成 **33** 走消息通道；内部还占用 24/25/26。
- 天气 `ucTemp/ucTempMax/ucTempMin` 单字节，命名无符号但温度可为负——编码（补码？+40 偏移？）文档未定义，**上机确认**。
- FILE_TRANS_START `uiFileNameLen ">13 截断到12"`（13B 缓冲存 8.3 名+NUL）——文件名一律 ≤12 字符自保；`ulOffset>0` 走 "ab+" **追加**，与 offset 具体值无关，续传前先用 0x06 查真实偏移。
- `GET_DEV_INFO`（0x21）有副作用：置 `g_stFtData.ucOtaMode=0xFF`——"读版本"不是纯读，OTA 测试序列里乱发会改状态。

---

## 四、版本演进痕迹（v1/v2 并存、死分支、废弃命令）

| 痕迹 | 现状 | 风险 |
|---|---|---|
| AMDTP 旧 base `0x2760...`（`svc_amdtp.h` `#else` 死分支） | 未编译 | 与未启用的 **AMOTA 服务 UUID `00002760-08C2-...`** 同源，网上搜到的 Ambiq 参考文档全是旧 base，照抄必连不上 |
| AMOTA（OTA 路径 A）整套 | **未编译**（03:19） | 实际 OTA 走 B2A `BCMD_FILE_TRANS(0x0F)`，勿实现 AMOTA 客户端 |
| GH3X2X 128-bit UUID 分支 | `#if 0` | 见 §一.6 |
| FFEB ACK 特征 | 未编译但**仍在广播 UUID 列表** | 见 §一.4 |
| 国内 device_type `...25 80` / `MAC_BLE_USE_OLD_NAME` | `#if 0` | 识别指纹按海外 `68 39 71 25 81` |
| `watchUpdateCfg` 600/800 参数 | `#if 0` 死代码 | 生效的是 8/18（10~22.5ms） |
| `AttcMtuReq` 直调 | 被注释（@973/1028） | 见 §一.3 |
| PUSH MUSIC(0x04)/CAMERA(0x05)/CHK_SMSG(0x06)、BLE_LOG(0x08)、TEST_MOTO_ON/OFF、RPT BREATHING(idx5)、BOND_REQINFO_GET(0x06)、hardfault dump 包 | 全部 `#if 0`/注释/无 handler | 发了会落 "NOT SUPPORT" 或无响应；**hardfault 特殊包文档自标 DEAD CODE"勿据此实现解析"** |
| [文件4] `WATCH_CONN_PARAM_FAST/LOW_POWER` | 仅见于通用例程分析 | 实际固件是否使用未证实，勿依赖 |
| `GET_BOOL_REMIND` "Bool" 实为血压 | 历史命名 | UI 文案/字段映射勿望文生义 |
| `proto 6.3.42 / 6.5.13` 编号 | 暗示存在上游 proto 规范文档 | 若能拿到，可作最终仲裁源 |

---

## 五、隐含假设（不写进代码就会炸）

1. **MTU=247 + DLE 251 是全协议的隐含地基**：日志块 237B（=244−15+8，244 即 247−3 ATT 头）、`iMaxParamPktLen` 全文**无显式数值**。默认 MTU=23 下这些帧根本发不出。**App 端连接后必须：发起 MTU 交换（≥247）→ 开 TX CCC → 再谈业务**；表端 `AttcMtuReq` 已注释（§一.3），别指望对端替你做。DLE 也只确认了 amdtp_main 参考路径（`DmConnSetDataLen(1,251,...)`），watch 路径未决。
2. **协议层没有超时/重传**：只有 CRC 失败被动 NAK（回 `EHST_FAIL`，且 NAK 里的 cmd/key 来自已损坏帧，不可信）与多包 ID 不符静默丢弃。App 必须自带请求超时。
3. **一批"合法的无响应"路径**，等 Ack 会白等：DEV_CTRL 0x01~0x06（`ucIsOk` 强制 0，**关机/复位/恢复出厂均不回包**）；`GET_IOS_MSG` 文件缺失**不发响应**；`BOND_REQINFO_RESET` 信息未变化不回 Ack；`RPT_DATA_ACK` 无响应；多数命令**仅当请求帧带 `EHST_ACK` 位才回 Ack**（BOND_CANCEL 例外无条件回）。→ 想要回执，请求帧记得置 0x02 位，且对上述清单单独处理。
4. **恢复出厂有两条路径语义不同**：`BOND_DEL(0x01/0x03)` 先回 1B result 再 `SYS_Restore()`；`DEV_CTRL_RESTORE(0x07/0x03)` 不回包直接干。测试脚本按命令区分预期。
5. **日志回传无 offset/seq/流控**：块边界仅由帧头 len 定义，App 按 Notify 到达顺序拼接；期间设备暂停采样、置 `filelog_is_sending` 互斥——并发发起第二次拉取会被拒/紊乱。
6. **广播 Flags = LE Limited Discoverable 却 duration=0 连续广播**：违背 spec 的 Limited 限时语义；扫描端**不要**按 Limited/General 模式过滤，直接匹配名字前缀/0xFFE0/厂商前 5 字节（05-scan 三选一）。
7. **设备名在 SCAN_RSP 不在 ADV_IND**：被动扫描拿不到名字，必须 active scan。
8. **安全假设**：TX CCC `DM_SEC_LEVEL_NONE`（未加密即可开 notify）+ Just Works 无 SC + `BOND_KEYCODE` 的 16B Code **固件占位未读**（不校验鉴权码）——即 B2A 业务层实际无鉴权门槛，测试工具可裸连；产品侧安全评审需知晓。
9. `watchConnCfg/watchUpdateCfg` 的 **supTimeout=0** 是非法/占位值（spec 要求 > (1+latency)×interval×2）——实际下发值需抓包确认，勿照抄进中央端参数校验。

---

## 六、建议的统一消歧基准（出现冲突时的裁决顺序）

1. **固件源码实际编译分支**（`ble2appEx.h/.c`、`ble2appWrap.c`、`svc_amdtp.h/.c`、`watch_main.c`、`factory_gh3x2x_ble_server.h/.c`）——一切行号仲裁的终点；
2. `manuals/b2a_protocol.h`（全 2121 行逐字节修订）+ `manuals/manual-b2a-complete.md`（其自称"权威逐字节规范"）——应用层帧格式与命令表以此为准，**但其行 22 示例帧与 §2 规则的冲突（本报告 §二.1）需回源码裁决**；
3. `manuals/manual-ble-protocol.md`（[文件3]，含对 [文件2] 的修订）——传输层/GATT 以此为准；
4. `01-ble-protocol.md`（[文件2]）——仅当 [文件3] 未覆盖时参考；
5. `BLE流程与Cordio主机栈分析.html`（[文件4]）——**通用例程视角，只作背景阅读，禁止直接据其实现**（LESC、服务集合、快/慢连接参数三处已被证伪或存疑）。

上机必做的 4 项抓包验证（文档自己都标"需确认"）：MAC/IMEI/ICCID 字节序；MTU 交换实际时序（watch 路径）；HEALTH_ALERT 多包 status 实值；产测帧 `uiLen=3` 的解析容忍行为。