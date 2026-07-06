# S7 手表 B2A 协议交叉校验报告：头文件提取 vs 手册上/下

> 校验对象：**[头]**=`b2a_protocol.h` 提取件（代码级事实源，全 2121 行）；**[册上]**=`manual-b2a-complete.md` 行 1–1400 提取件；**[册下]**=同手册行 1300–2669 提取件。
> **材料完整性警示**：[册下] 提取件在 §四 RPT_DATA 的 HR/WEAR summary 处截断，其标称覆盖的 `BCMD_DEV_CTRL(0x07)` 明细、`BCMD_TEST(0x08)`、`BCMD_FILE_TRANS(0x0F)` 逐字段部分**缺失于本汇编**。下文差集表区分「材料级差集」与「真差集」。

---

## 一、一致项确认（核心骨架无冲突）

### 1.1 外层帧头 `B2A_HEAD`（8 字节）— 完全一致

| 偏移 | 字段 | 宽度 | [头] | [册上] | 判定 |
|---|---|---|---|---|---|
| 0 | ucStartFlag | 1 | 0xBB | 0xBB (`MAC_B2A_HEAD_FLAG`) | 一致 |
| 1 | ucStatus | 1 | 位域 ENUM_HEAD_STATUS_TYPE | 同 | 一致 |
| 2-3 | uiLen | 2 LE | 帧头之后字节数（首包=4+paramLen） | 同 | 一致 |
| 4-5 | uiCRC | 2 LE | CRC16-CCITT-FALSE，覆盖偏移 8 起 uiLen 字节 | 同 | 一致 |
| 6-7 | uiIndex | 2 LE | 包序号/分片索引 | 同 | 一致 |

双方同引 `ble2appEx.h:74-81`。字段名、宽度、顺序、字节序逐项吻合。

### 1.2 内层命令头 `B2A_DATA_CMD`（4 字节）— 布局一致，命名呈现有差（见二.1）

Cmd(1) + Key(1) + 参数长度(2 LE) + szParam[]；仅首包（uiIndex==0）携带，续包裸 payload、命令字由缓存 `ucLastCmd/ucLastKey` 延续。双方一致（`ble2appEx.h:513-525`）。

### 1.3 帧头状态位 — 完全一致

EHST_SUCC=0x00 / FAIL=0x01 / ACK=0x02 / IS_MULTI_PKT=0x04 / MULTI_PKT_END=0x08 / OTA_PART=0x80；多包 ID=`(ucStatus>>4)&0x03`（bit[5:4]）。[头]行 39-47 与 [册上]行 81-91 逐值相同。

### 1.4 校验算法 — 完全一致

| 项 | [头] | [册上] | 判定 |
|---|---|---|---|
| 帧 CRC | CRC16-CCITT-FALSE：Poly=0x1021, Init=0xFFFF, refin/refout=False, Xorout=0 | 同（`crc16_ccitt_false.c:14-42`） | 一致 |
| 覆盖范围 | 偏移 8 起 uiLen 字节，不含帧头 | 同 | 一致 |
| CRC 失败行为 | 回帧头 EHST_FAIL 作被动 NAK | 同（`ble2appEx.c:645-652`） | 一致 |
| CRC8(0x07/0) | — （未涉及，属广播层） | 仅用于广播/扫描设备名，不参与 B2A 帧 | 无冲突 |
| 转义/字节填充 | 无（packed + 显式长度定界） | 无任何 escape/stuffing 记述 | 一致 |

### 1.5 应用层错误码 — 完全一致（14 项逐值相同）

EBEC_SUCC=0x00, FAIL=0x01, TIMEOUT=0x02, FORMAT=0x03, MEMORY=0x04, NOT_SUPPORT=0x05, PARAM=0x06, BUSY=0x07, LOW_BAT=0x08, NO_DATA=0x09, MD5=0x0A, CRC=0x0B, FATAL=0x0C, FSUM_FAIL=0x0D（[头]行 64-79 = [册上]行 216，同引 `ble2appEx.h:47-71`）。绑定结果码 EBRT_SUCC/AGREE=0x00, REFUSE=0x01, BOND=0x02 亦一致。CommAck 取值（0/1/5）与错误码枚举自洽。

### 1.6 命令 ID — 一级命令字与已覆盖子键逐值一致

- **一级 10 项**：0x01 BOND / 0x02 GET / 0x03 SET / 0x04 PUSH / 0x05 IND / 0x06 RPT_DATA / 0x07 DEV_CTRL / 0x08 TEST / 0x0F FILE_TRANS / 0x86 RPT_DATA2 —— 双方完全相同。
- **BOND 0x01–0x07**（含 0x06 REQINFO_GET 未实现）：一致。
- **GET 0x01–0x15、0x21–0x28、0x2A（0x29 缺号）**：键值、响应定长（9/15/7/1/6/13/13/6/7/7/7/6/4/2/2/4/…/59/10/…B）逐项吻合。
- **SET 0x01–0x15、0x25–0x2A**：一致；请求定长（9/16/8/8/8/16/16/8/8/8/8/8/4/4/4/4/…）吻合。
- **PUSH 0x01–0x07**（0x04/0x05 `#if 0`、0x06 注释停用）：键值与启用状态一致。
- **IND 0x01–0x0E**：一致；各动作枚举（SPORT 1-5、MUSIC 1-7、CAMERA 1-4、OTHER_CTRL 0x01-0x07、告警类型 0-5）一致。
- **RPT_DATA 0x01–0x04**、**RPT_DATA_IDX 0–16**（BREATHING=5 停用）：一致；START 响应「选中写 2B / 未选写 5B」的固件 bug 双方同述。
- **代表性 payload 抽查一致**：日期时间 9B（GET timezone 有符号）、SN_INFO 59B（5+12+6+16+20）、DEV_VOL 10B（ulLastTime 恒 0）、心跳 8B（utc+seq+battery+reserved，应答 network_status）、KEYCODE 请求 20B/响应 5B（DEV_PRO_ID=40=0x0028）、BOND_DEV TLV 串、PUSH_PHONE/MSG/WEATHER 布局与总长公式、BLP_RAW 5B、GET_DEV_INFO TLV（含 ucOtaMode=0xFF 副作用）。

---

## 二、不一致处明细（双方值 + 出处 + 判定）

### 2.1 内层命令头第 3 字段命名：`uiParamLen` vs `uiLen`

| 方 | 值 | 出处 |
|---|---|---|
| [头] | `uiParamLen`（uint16 LE） | b2a_protocol.h §1.2 行 27-32 |
| [册上] | `uiLen(paramLen)`（uint16 LE） | 手册 §2，引 `ble2appEx.h:513-525` |

**判定：手册更可信（此处例外于"头文件优先"惯例）**。理由：`b2a_protocol.h` 本身是位于 `manuals/` 的**派生文档**，真源是 `ble2appEx.h`；手册与 BLE 传输层文档（[文件3] §4.2）均按源码写作 `uiLen`，[头]的 `uiParamLen` 属消歧改名。宽度/偏移/字节序双方一致，**线上格式无差异**，纯文档呈现问题。

### 2.2 RPT_DATA 类型掩码定义范围

| 方 | 值 | 出处 |
|---|---|---|
| [头] | 「对应 mask：bit n = 1<<idx」（通式，隐含覆盖全部 0–16） | b2a_protocol.h 行 1440-1442 |
| [册下] | 掩码宏 `RPT_DATA_MASK_*` **仅定义到 bit8=ECG**（bit0=HR…bit8=ECG） | 手册 §RPT_DATA_START，引 `ble2appEx.h:377-385` |

**判定：手册更可信**。手册给出宏枚举的确切源码行区间（377-385 共 9 项），[头]为概括通式。语义不真冲突（bit9+ 类型仍按 1<<idx 规则使用），但**实现 START 掩码时应知宏只显式定义到 ECG**，高位掩码需自行按位构造。

### 2.3 `0x86 RPT_DATA2` 与 `0x06` 的关系表述

| 方 | 值 | 出处 |
|---|---|---|
| [头] | 「与 0x06 合并处理」 | b2a_protocol.h §2.1 |
| [册下] | 解析/组包相同，**但"无数据"回复路径不同**：0x06 回空参 CommAck；0x86 回 `B2A_CommAckWithParam` 回显请求 4 字节且 `pszParam[1]=0`（ucOperate 清零）作无新数据标志 | 手册，引 `ble2appWrap.c:6492-6496,6575-6582,7000-7004…` |

**判定：手册更可信（精化）**。[头]的「合并」仅指 switch case 合并，不算错但粒度粗；App 实现必须按 [册下] 区分两种无数据应答，否则 0x86 通道会把回显帧误判为数据帧。

### 2.4 SET 0x14/0x15 键名后缀（轻微，非数值冲突）

[头]仅给 GET 侧名 `GET_BOOL_TEST_SET`/`GET_ECG_TEST_SET` 并称 SET 一一对应；[册上]给 SET 侧名 `SET_BOOL_TEST_REMIND`/`SET_ECG_TEST_REMIND`。键值（0x14/0x15）与布局（8B/8B 请求、7B/5B 响应）一致。**判定：无线上影响**；确切枚举名以 `ble2appEx.h:181-223` 为准。

### 2.5 双方一致的帧结构 vs 手册自引的源码示例帧（重要发现）

[册上]行 22 抄录的进产测握手帧：`BB 02 03 00 2D 46 00 00 08 01 01`（`pc_factory_mode_main.c:1480`）。按双方一致的帧结构解读：uiLen=3，**小于 4 字节命令头**——payload 仅 `08 01 01`（Cmd=0x08 TEST, Key=0x01 ENTER_FACTORY, 1 字节选择子 0x01=BLE，**缺 2 字节 paramLen 域**）。
本审计已独立复算：CRC16-CCITT-FALSE(`08 01 01`) = **0x462D**，与帧内 `2D 46`(LE) 精确吻合——即该短帧是**真实自洽的线上帧**，非笔误。
**判定：双方的「首包必带完整 4B 命令头」通则存在至少一个代码级反例**；解析器对产测短帧有容错（或该路径特判）。互操作实现（如 BLE 控制台/上位机）复现产测握手时应原样发送 11 字节短帧，勿按通则补 paramLen。

---

## 三、单向差集表

### 3.1 手册有而头文件提取没有

命令/子键 **ID 层面差集为空**——手册出现的每个 Cmd/Key 在 [头] 中均有对应。以下为**内容级**手册独有项：

| # | 手册独有内容 | 出处 | 说明 |
|---|---|---|---|
| 1 | BOND_REQ 应答 71B 布局（result+MAC6+IMEI16+ICCID20+SN12+AuthCode16） | [册上] BOND 0x01 | [头]未展开该 Ack 结构 |
| 2 | GET 全系响应**逐字节偏移表**（如 GET_UNIT 6B、GET_SEDENTARY 13B、GET_SLEEP_REMIND 11B 等） | [册上] GET 表 | [头]多数仅给结构体行号 |
| 3 | PUSH 帧层特例：Cmd=0x04/Key=0x02 **只校 SOF**，可超长/分片（`ble2appEx.c:616-623`） | [册上]行 115 | 影响接收端校验实现，[头]未提 |
| 4 | 请求-应答配对规则细节（EHST_ACK 门控、BOND_CANCEL 无条件回复、REQINFO_RESET 信息未变不回 Ack） | [册上] §二 | [头]仅部分覆盖 |
| 5 | IND 0x0E HEALTH_ALERT 多包异常：超长多包（含末包）status 均 0x00，**不置分片位** | [册下] IND 0x0E | [头]无此告警 |
| 6 | RPT_DATA 各类型**线上手工装包格式**与磁盘 struct 不完全一致的声明 | [册下] §四 | [头]按 struct 描述 |
| 7 | 产测握手帧 HEX 样例（见二.5）、`0x86` 请求示例帧 | [册上]行 22、[册下]行 1888 | 实测锚点仅手册有 |
| 8 | PUSH_MSG type 取值表（0-7、24/25/26 内部、33=未接来电） | [册下] PUSH 0x02 | [头]未列 |

### 3.2 头文件提取有而手册（本汇编所供材料）没有

| # | [头]独有命令/枚举 | 值 | 差集性质 |
|---|---|---|---|
| 1 | BCMD_TEST 子键 MOTO_ON / MOTO_OFF（无 handler 未实现） | 0x02 / 0x03 | 疑似材料级（[册下]截断，TEST 节缺失） |
| 2 | BCMD_DEV_CTRL 子键明细：FIND / FIND_END / FIND_PHONE_END / BLE_LOG(`#if 0`) / ACK_FILE_LOG / FILE_ALGO / ACK_FILE_ALGO | 0x04 / 0x05 / 0x06 / 0x08 / 0x09 / 0x0A / 0x0B | 材料级（手册材料仅零散提及 0x01/0x02/0x03/0x07） |
| 3 | BCMD_FILE_TRANS 子键：START / TRANS / STOP / REQ / OFFSET（**0x05 缺号**） | 0x01 / 0x02 / 0x03 / 0x04 / 0x06 | 材料级（[册下]标称覆盖但截断） |
| 4 | FILE_TRANS 全套结构：REQ 8B、REQ 响应 12B、START 16+N B、数据块成功响应 9B（含 **ulCheckSum=u32 字节累加和**）、STOP/OFFSET 响应 | — | 材料级 |
| 5 | 模块/状态/文件类型枚举：BMID_*（1-6）、BFRS_*（0-3）、BFTT_*（1,2,3,7,8,9,10…） | — | 材料级 |
| 6 | 算法特征文件类型 ENUM_B2A_DEV_FEA_FILE_TYPE（0x00=MIN, 0x01=SLEEP）；工厂模式选择子 0x00–0x05 | — | 材料级 |
| 7 | 日志回传块结构（szReqPrefix 回显 + szLogChunk，无 offset/seq，按到达序拼接）；hardfault 特殊包判为 DEAD CODE | 0x07/0x09 | 材料级（04 文档有旁证且相容） |
| 8 | SET 别名复用细节：DRINK_WATER≡SEDENTARY（16B）、SPO2/PRESSURE≡HR_MODE（interval=0 分别强制 1/30/10） | — | [册上]有各表但未点破别名关系（半独有） |

> 判定基线：第 1–7 项在源手册（2669 行全文）中**很可能存在**（[册下]开篇声明覆盖这些命令），差集源于本汇编截断而非手册缺漏；复核时应回读 `manual-b2a-complete.md` 行 2150–2669 原文确认。

---

## 四、补充：跨文档裁决（头文件解决手册系不确定项）

| 事实点 | 手册系文档表述 | [头]表述 | 裁决 |
|---|---|---|---|
| `BKEY_DEV_CTRL_FILE_LOG` 数值 | 04-logging：「未见显式赋值，按枚举顺序**推断**」（04:253） | 明确 **0x07**（§2.8） | 采信 [头]=0x07（代码级），推断成立并落定 |
| `BKEY_DEV_ACK_FILE_LOG` 数值 | 04-logging 仅给名（`ble2appWrap.c:11310`） | 明确 **0x09**，且注明设备发起 | 采信 [头]=0x09 |
| GET_DIAL_INFO 分隔语义、SN/MAC/IMEI/ICCID 字节序、SET_DEV_ALARM 首字节双义 | [册上]列为需确认 | [头]同样标注需与 App 端确认 | 双方一致悬置，非矛盾 |

---

## 五、审计结论

1. **协议骨架零冲突**：帧头 8B、命令头 4B、状态位、CRC16-CCITT-FALSE（参数与覆盖范围）、错误码 14 项、一级命令 10 项及全部已双覆盖子键的数值——头文件与手册上/下逐值一致，两者显然同源（均以 `ble2appEx.h`/`ble2appWrap.c` 为底），可交叉互证。
2. **实际不一致共 5 处**，全部为文档粒度/命名级，无一处线上字节格式矛盾；其中 2 处（2.2 掩码范围、2.3 0x86 应答差异）例外地**手册比头文件提取更精确**——因为 `b2a_protocol.h` 是派生摘要而非原始源码，「头文件=事实源」的惯例在本案中应修正为「`ble2appEx.h/ble2appWrap.c` 行号引证密度更高的一方为准」。
3. **最有价值的发现**是二.5：产测握手帧（uiLen=3）违反双方共同声明的 4B 命令头通则，且经本审计独立复算 CRC（0x462D）确认该帧真实自洽——任何互操作实现必须对该短帧特判。
4. **遗留动作**：回读源手册行 2150–2669 补齐 DEV_CTRL/TEST/FILE_TRANS 三节的交叉校验（本汇编截断导致表 3.2 多数条目暂为材料级差集）；并核对 `ble2appEx.h:181-223` 落定 SET 0x14/0x15 确切枚举名。