# S7 · B2A 协议完整规格（逐字节 · 含位域展开）

> **合成说明**：本文由四份独立抽取结果合成——
> ①传输/帧层（固件 `apollo4_watch_s7` 源码）②应用命令全集（固件 `ble2appWrap.c`）③日志+OTA 子协议（固件）④**代码实证**（BlueTrace 侧 `shared/.../s7/*.kt`，真机 SKG WATCH S7-FCC4 联调过）。
>
> **权威顺序**：字节布局以**第 4 份代码实证**为最高权威（真机验证过）；语义、命令全集、示例以固件源文档（①②③）补充；两者冲突处于该字段/命令下**标注 `⚠差异`**，并在 [第 10 章](#10-代码实证-vs-文档源码差异汇总)集中列出。
>
> **实现状态口径**：参考 [command-status.md](command-status.md)。每条命令标注 `✅已实现` / `⬜未实现（业务类·不在维护工具范围）` / `⛔固件未开(#if 0)` / `🅾OTA二期`。
>
> **CRC / golden 锚点**：`CRC16-CCITT-FALSE(08 01 01) = 0x462D`；完整帧 `BB 02 03 00 2D 46 00 00 08 01 01`（产测握手，代码实证 `S7Frame.kt:119`）。

---

## 目录

1. [概述与传输栈](#1-概述与传输栈gatt--mtu--分包重组)
2. [帧格式逐字节](#2-帧格式逐字节)
3. [数据组包：逻辑包→分片→重组](#3-数据组包逻辑包分片重组)
4. [命令总览与分发](#4-命令总览与分发)
5. [BOND 绑定命令族（0x01）](#5-bond-绑定命令族-cmd0x01)
6. [GET 读命令族（0x02）](#6-get-读命令族-cmd0x02)
7. [SET 写命令族（0x03）· PUSH（0x04）· IND（0x05）· RPT_DATA（0x06/0x86）](#7-set-写命令族-0x03--push-0x04--ind-0x05--rpt_data-0x060x86)
8. [DEV_CTRL 设备控制（0x07）· TEST 工厂（0x08）](#8-dev_ctrl-设备控制-0x07--test-工厂-0x08)
9. [FILE_TRANS / OTA（0x0F）· 日志拉取子协议](#9-file_trans--ota-0x0f--日志拉取子协议)
10. [代码实证 vs 文档（源码）差异汇总](#10-代码实证-vs-文档源码差异汇总)
11. [附录：枚举 / 状态码 / CRC / 命令索引](#11-附录枚举--状态码--crc--命令索引)

---

## 1. 概述与传输栈（GATT / MTU / 分包重组）

B2A（BLE-to-App）是 SKG WATCH S7 手表与配套 App 间的应用层协议，承载于 **AMDTP** 128-bit 厂商私有 GATT 服务之上。所有多字节字段**小端（LE）**，帧结构 **packed 无填充、无转义**。

### 1.1 GATT 服务 / 特征（承载 B2A）

128-bit 私有 base：`0000xxxx-3C17-D293-8E48-14FE2E4DA212`（广播里以 16-bit `0xFFE0/E1/E2` 段出现）。

| 名称 | 完整 UUID | 属性 / 方向 | 值句柄 |
|---|---|---|---|
| **AMDTP Service** | `0000FFE0-3C17-D293-8E48-14FE2E4DA212` | Primary Service | `0x0800` |
| **RX 特征** | `0000FFE1-3C17-D293-8E48-14FE2E4DA212` | **Write Without Response**（App→表，下行命令） | `0x0802` |
| **TX 特征** | `0000FFE2-3C17-D293-8E48-14FE2E4DA212` | **Notify**（表→App，应答/上报） | `0x0804` |
| **TX CCC** | `0x2902` | Read/Write CCC，安全级 `DM_SEC_LEVEL_NONE`（开 notify **不要求加密**） | `0x0805` |
| ~~ACK 特征 FFEB~~ | `0000FFEB-...` | 广播 UUID 表列出但**实际不建服务**（`__BLE_AMDTPS_SUPPORT__` 下整段被排除） | — |

> 另有 **GH3X2X 工厂数据服务**（`0x190E`，标准蓝牙 base，句柄基址 `0x0A00`），是产测专用通道，**与 B2A 数据面独立**，不走 B2A 帧协议。本文不展开。

**收发链路（旁证 B2A 承载路径）**：
- **接收**（App→表，写 FFE1）：ATTS write → `amdtps_write_cback` → `SYS_BleRecvData` → `B2A_RecvData`（校验 SOF/Len/CRC16）→ `B2A_RecvDataHandle` → `switch(ucCmd)`。
- **发送**（表→App，notify FFE2）：`SYS_BleSendData` → `SDK_BleSendData` → `amdtpsSendData`（检查 txReady）→ `AttsHandleValueNtf(connId, AMDTPS_TX_HDL, ...)`。

### 1.2 链路 / 连接参数

| 参数 | 值 | 备注 |
|---|---|---|
| 广播间隔 | 1000ms（连续广播 duration=0） | — |
| 最大连接数 | **1** | 设备侧限制 |
| 期望连接参数 | interval min 8(10ms) / max 18(22.5ms) / latency 0 | — |
| **MTU 协商** | 连接后表端主动 `AttcMtuReq(247)` | App 侧接受协商结果即可 |
| **DLE 数据长度扩展** | `DmConnSetDataLen(251, 0x848)`；HCI 最大 RX ACL 251 | 单 BLE 逻辑包链路层可承载 ~244B ATT payload |
| 配对 | Just Works（`SMP_IO_NO_IN_NO_OUT`），仅绑定（`DM_AUTH_BOND_FLAG`），TX CCC 不要求加密 | **连上即可通信** |

### 1.3 分包 / 重组机制（关键结论）

**B2A 没有独立的「分片头」。** 逻辑包的拆分/重组完全由 **8 字节帧头**里的三个字段承担：
- **`Status`（偏移 1）**：多包标志位 `IS_MULTI_PKT` / `MULTI_PKT_END` + 多包 ID `bit[5:4]`；
- **`Index`（偏移 6，LE16）**：包序号，首包 0，续包递增，用于顺序校验；
- **`Len`（偏移 2，LE16）**：本帧 payload 长度。

每个 BLE 分片本身即携带完整的 8 字节 B2A 帧头。**首包**带 4 字节命令头（`cmd/key/paramLen`），**续包**只有裸 param 续段（命令字由首包延续）。链路层（L2CAP/ACL）的分段/重组由 ExactLE/Cordio 协议栈透明完成，应用层不可见。

**App 侧必须自建的可靠性层**（协议未定义超时/重传/事务 ID）：
1. **单飞串行队列**：同一时刻最多一个未决请求（无事务 ID，同 Cmd/Key 并发无法区分应答）；
2. **超时**：默认 3s；
3. **不回包命令**（关机/重启/恢复出厂）：以 **LinkState 断链** 作为成功判据，10s 未断链报「设备未响应」；
4. **设备主动帧**（心跳/日志块/告警）与应答共用 Notify 通道：分发器先按未决请求的 Cmd/Key 拦截应答，其余走上报处理器；
5. **下行分片**：维护控制台全部命令 payload ≤ 20B，**永不分片**；日志/OTA 上行重组按 [第 3 章](#3-数据组包逻辑包分片重组)规则实现。

### 1.4 扫描识别（B2aDetect）

| 优先级 | 判据 | 位置 |
|---|---|---|
| ★★★ | 广播 16-bit Service UUID 含 **`0xFFE0`** | ADV_IND，AD type 0x03（实测广播含 180A + FFE0/E1/E2/EB） |
| ★★☆ | 连接后 service discovery 查 **RX=FFE1 且 TX=FFE2** 同时存在 | GATT |
| ★☆☆ | 设备名前缀 `SKG WATCH S7-` | **SCAN_RSP**（被动扫描拿不到，仅辅助过滤，不作协议判据） |

- 完整设备名 = `"SKG WATCH S7-" + 4 位大写 hex`；后缀算法 = `大写Hex(MAC[1]) + 大写Hex(MAC[0])`（先字节[1] 后字节[0]，各 2 位大写 hex）。例：MAC `71:61:48:19:FC:C4` → 名 `SKG WATCH S7-FCC4`（代码实证真机）。
- `B2aDetect.extract16`：4 位 UUID 直返；`0000` 前缀 128-bit 取 `[4,8)`（bits16..31 段）。

---

## 2. 帧格式逐字节


帧信封 bit-level memory map（与共识稿 §3.2 同源，此处为分册自包含）：

```
Byte Offset:   0        1        2        3        4        5        6        7
              +--------+--------+--------+--------+--------+--------+--------+--------+
B2A_HEAD      | 0xBB   | status |  uiLen u16 LE   |  uiCRC u16 LE   | uiIndex u16 LE  |
              | SOF    | 位图↓  |  头后字节数     |  覆盖偏移8起    | 首包0, 续包++   |
              +--------+--------+--------+--------+--------+--------+--------+--------+
Byte Offset:   8        9        10       11       12 ...
              +--------+--------+--------+--------+---------------------------+
B2A_DATA_CMD  | ucCmd  | ucKey  | uiParamLen LE   | szParam ...               |  ← 仅 Index==0
              +--------+--------+--------+--------+---------------------------+

ucStatus (byte 1)，bit7 → bit0（ble2appEx.h:26-43）：
              +----------+------+------+---------------+---------------+----------+----------+
         bit  |    7     |  6   |  5   |       3       |       2       |    1     |    0     |
              +----------+------+------+---------------+---------------+----------+----------+
              | OTA_PART |    保留     | MULTI_PKT_END | IS_MULTI_PKT  |   ACK    |   FAIL   |
              |   0x80   |             |     0x08      |     0x04      |   0x02   |   0x01   |
              +----------+------+------+---------------+---------------+----------+----------+
（uiLen<4 的短帧无 uiParamLen 域，见 §3.3 例①；bit[5:4] 另作多包 ID，见 §2.4）
```

### 2.1 帧头 B2A_HEAD（固定 8 字节，全小端）

代码实证：`S7Frame.kt:100-109`（编码）/ `:151-177`（解码）。字段名对齐固件 `ble2appEx.h:74-81`。

| 偏移 | 字段 | 字节数 | 字节序 | 含义 / 取值 |
|---|---|---|---|---|
| **0** | `ucStartFlag`（SOF） | 1 | — | 固定 **`0xBB`**（`S7FrameCodec.SOF`；固件 `MAC_B2A_HEAD_FLAG`） |
| **1** | `ucStatus` | 1 | — | 状态位域，见 [§2.4](#24-ucstatus-位域逐-bit-展开)（含多包/ACK/OTA 标志） |
| **2** | `uiLen` | 2 | **LE** | 帧头之后 payload 字节数（首包 = 4 + paramLen；续包 = 续段长） |
| **4** | `uiCRC` | 2 | **LE** | **CRC16-CCITT-FALSE**，**仅覆盖 payload（偏移 8 起 uiLen 字节），不含帧头本身** |
| **6** | `uiIndex` | 2 | **LE** | 包序号：首包 0，续包递增 |
| **8..** | `payload` | uiLen | — | 首包=命令头(4B)+param；续包=裸续段 |

空中示意：`BB | Status | Len(LE16) | CRC16(LE16) | Index(LE16) | payload[Len]`

### 2.2 命令头 B2A_DATA_CMD（payload 起始 4 字节，仅首包携带）

代码实证：`S7Frame.kt:78-83`。字段名对齐固件 `ble2appEx.h:513-518`。

| 偏移（payload 内） | 字段 | 字节数 | 字节序 | 含义 |
|---|---|---|---|---|
| 0 | `ucCmd` | 1 | — | 一级命令字（[§4.1](#41-一级命令字)） |
| 1 | `ucKey` | 1 | — | 子命令 / 键 |
| 2 | `uiParamLen`（SubLen） | 2 | **LE** | 参数长度 |
| 4.. | `szParam[]` | paramLen | — | 命令参数体 |

### 2.3 CRC16-CCITT-FALSE（代码实证精确算法）

`S7Frame.kt:13-26`：

- **poly = 0x1021** · **init = 0xFFFF** · **输入不反转 / 输出不反转**（无 refin/refout，直接按 MSB 处理）· **xorout = 0**。
- 逐字节：`crc ^= (byte << 8)`；每字节循环 8 次：`if (crc & 0x8000) crc = (crc<<1)^0x1021 else crc <<= 1`；每轮 `crc &= 0xFFFF`。
- **覆盖范围 = 仅 payload**（帧偏移 8 起、共 uiLen 字节），**不含 8 字节帧头**。
- 别名：CRC-16/CCITT-FALSE，又称 CRC-16/AUTOSAR（init=0xFFFF 版）。
- **golden 锚点**：`CRC16-CCITT-FALSE(08 01 01) = 0x462D`。

### 2.4 `ucStatus` 位域逐 bit 展开

代码实证 `S7Frame.kt:29-39`（`S7Status`），语义对齐固件 `ENUM_HEAD_STATUS_TYPE`（`ble2appEx.h:26-43`）。**整字节按位与判定**；分片包 ID 取 `(status>>4)&0x03`。

| 名称 | 值 / bit | 含义 |
|---|---|---|
| `SUCC` | **0x00**（无位置位） | 成功；也是不回包命令的下行请求 status（代码实证，见 [§10-4](#10-代码实证-vs-文档源码差异汇总)） |
| `FAIL` | **bit0 = 0x01** | 失败（CRC 错被动 NAK）；`isFail = (status & 0x01) != 0` |
| `ACK` | **bit1 = 0x02** | 请求方置位 = **要求应答** |
| `IS_MULTI_PKT` | **bit2 = 0x04** | 多包/分片中 |
| `MULTI_PKT_END` | **bit3 = 0x08** | 多包末片 |
| （多包 ID） | **bit[5:4]** | `multiPktId = (status>>4)&0x03`，同一逻辑事务的各分片须相同 |
| `OTA_PART` | **bit7 = 0x80** | OTA 续传/分片标志 |

> bit6 未定义。解码判定：`multi = status & 0x04`，`end = status & 0x08`（`S7Frame.kt:180-181`）。

---

## 3. 数据组包：逻辑包→分片→重组

### 3.1 逻辑包 → 分片（发送侧）

一个逻辑事务（如一条 GET 应答、一个日志块）超过单帧承载能力时，拆成多帧：

```
逻辑 param（总长 = 首包命令头声明的 paramLen）
        │
        ├─ 首包 index=0：帧头(status 置 IS_MULTI_PKT + 多包ID) + [cmd|key|paramLen(LE16)|param 首段]
        ├─ 续包 index=1：帧头(status 置 IS_MULTI_PKT，同多包ID)  + [param 续段]（无命令头）
        ├─ 续包 index=2：…
        └─ 末包 index=n：帧头(status 置 MULTI_PKT_END[+IS_MULTI_PKT]，同多包ID) + [param 末段]
```

每帧 CRC 独立覆盖各自 payload。控制台**下行**命令 payload ≤ 20B，永不分片（单帧 index=0）；**上行**（表→App）的日志块、GET 长应答、OTA 走多包。

### 3.2 分片 → 重组（接收侧，代码实证 `S7Frame.kt:179-256`）

单次 notification 可含多帧，逐帧切分并重组，容错逻辑：

| 步骤 | 规则 | 代码行 |
|---|---|---|
| SOF 校验 | `payload[pos] != 0xBB` → `frameErrors++`，**丢弃余下**（无转义无法再同步） | `:155-158` |
| 长度越界 | `pos+8+len > size` → `frameErrors++`，丢弃余下 | `:163-166` |
| CRC 校验 | `crc16(payload) != uiCRC` → `crcErrors++`，`continue` 跳过本帧（帧边界仍可信） | `:169-172` |
| 尾部残余 | 剩 <8B → `frameErrors++` | `:175` |
| **单帧特例** | `!multi && !end` → 不动重组缓冲，直接产出（设备主动帧/心跳与多包共用通道，插帧合法） | `:182-185` |
| 多包首片 | `index==0`：读命令头，`pendingDeclaredLen = readLe16(payload,2)`，缓存 cmd/key/多包ID | `:189-200` |
| 多包续片 | **双校验**：`pendingId==id` 且 `index==pendingNextIndex`，否则 `reset()` 整片丢弃 | `:204-208` |
| 多包末片 | 校验**重组总长 == 首片声明 paramLen**，否则丢弃 | `:214-219` |
| 短帧特例 | payload<4B 但 ≥2B（产测握手）：`cmd=payload[0]`、`key=payload[1]`、其余为参数；<2B 丢弃 | `:245-254` |
| 单帧长度自愈 | 单帧 paramLen 与实际不符 → 以实际为准（`take = declared in 0..avail ? declared : avail`） | `:236` |

### 3.3 示例 hex

**① 产测握手短帧**（uiLen=3 < 4，无 paramLen 域，代码实证 golden）：
```
BB 02 03 00 2D 46 00 00 08 01 01
└┘ │  └─┬─┘ └─┬─┘ └─┬─┘ │  │  │
SOF │  Len=3 CRC   Index │  │  └ 选择子 0x01（BLE 模式）
    │        =462D  =0   │  └ key=0x01（ENTER_FACTORY_MODE）
    status=ACK           └ cmd=0x08（TEST）
```
CRC16-CCITT-FALSE(`08 01 01`) = 0x462D 自洽。

**② 健康数据上报单帧**（Cmd=0x86 RPT_DATA2 / Key=0x03 DATA，固件注释 `ble2appWrap.c:6521`）：
```
BB 00 08 00 57 92 00 00 86 03 04 00 00 00 00 00 00
└┘ │  └─┬─┘ └─┬─┘ └─┬─┘ │  │  └─┬─┘ └────┬────┘
SOF │  Len=8 CRC   Idx0 │  │  plen=4    param 4B = RPT_DATA_REQ_PARAM
    st=SUCC              │  └ key=0x03（DATA）
                        cmd=0x86（RPT_DATA2）
```
第二帧同结构，param `0A 00 00 00`（type=0x0A=ACTIVITY）。

**③ 拉算法文件**（Cmd=0x07 DEV_CTRL / Key=0x0A FILE_ALGO，固件注释 `ble2appWrap.c:11828`）：
```
BB 02 06 00 A6 5E 00 00 07 0A 02 00 01 00
                          │  │  └─┬─┘ └─┬─┘
                          │  │  plen=2  param=01 00（RPT_FEA_FILE_SLEEP=1）
                          │  └ key=0x0A
                          cmd=0x07
```

---

## 4. 命令总览与分发

### 4.1 一级命令字（`ENUM_B2A_CMD_TYPE`）

代码实证 `S7Messages.kt:12-21`，对齐固件 `ble2appEx.h:85-104`。分发主体 `B2A_RecvDataHandle` `switch(ucCmd)`（固件 `ble2appWrap.c:11883/12037`）。

| 命令 | 值 | 方向主体 | 说明 |
|---|---|---|---|
| `CMD_BOND` | **0x01** | App→表 | 绑定握手 / 鉴权码 |
| `CMD_GET` | **0x02** | App→表（表应答） | 读设备设置/信息 |
| `CMD_SET` | **0x03** | App→表（1B CommAck） | 写设备设置 |
| `CMD_PUSH` | **0x04** | App→表 | 推送来电/消息/天气 |
| `CMD_IND` | **0x05** | 双向 | 指示/控制（多为表发起） |
| `CMD_RPT_DATA` | **0x06** | 双向 | 健康数据同步 |
| `CMD_DEV_CTRL` | **0x07** | App→表 | 危险命令 / 找表 / 日志 |
| `CMD_TEST` | **0x08** | App→表 | 工厂测试 |
| `CMD_FILE_TRANS` | **0x0F** | 双向 | 文件传输 / OTA |
| `CMD_RPT_DATA2` | **0x86**（=0x80\|0x06） | 双向 | 健康数据同步（变体） |

### 4.2 命令分类实现状态一览

| 分类 | Cmd | 维护控制台实现 | 备注 |
|---|---|---|---|
| BOND | 0x01 | ⬜ 未做（真机实证维护指令无需绑定） | [第 5 章](#5-bond-绑定命令族-cmd0x01) |
| GET | 0x02 | ✅ 8 个 Key 已实现；其余业务类未做 | [第 6 章](#6-get-读命令族-cmd0x02) |
| SET | 0x03 | ✅ 时间/用户已实现；其余业务类未做 | [§7.1](#71-set-写命令族-cmd0x03) |
| PUSH | 0x04 | ⬜ 业务类 | [§7.2](#72-push-推送-cmd0x04) |
| IND | 0x05 | ✅ 心跳被动解析；其余业务类 | [§7.3](#73-ind-指示控制-cmd0x05) |
| RPT_DATA | 0x06/0x86 | ⬜ 业务类（常量定义但控制台未路由） | [§7.4](#74-rpt_data-健康数据同步-cmd0x060x86) |
| DEV_CTRL | 0x07 | ✅ 关机/重启/恢复/找表/日志已实现 | [§8.1](#81-dev_ctrl-设备控制-cmd0x07) |
| TEST | 0x08 | ⬜ 产测专用 | [§8.2](#82-test-工厂测试-cmd0x08) |
| FILE_TRANS | 0x0F | 🅾 OTA 二期 | [§9.1](#91-file_trans--ota-cmd0x0f) |

---

## 5. BOND 绑定命令族（Cmd=0x01）

Key 枚举 `ENUM_B2A_BOND_KEY_TYPE`（`ble2appEx.h:121-133`）。方向：App→表（应答表→App）。分发 `ble2appWrap.c:12040-12238`。
**实现状态：⬜ 全族未实现**——真机实证维护指令无需绑定即可直发（`completeness-audit §7`），KEYCODE 固件不校验。仅当未来固件加门控才需要。

| Key | 名称 | 是否回复 | payload（App→表） |
|---|---|---|---|
| 0x01 | `BOND_REQ` 绑定请求 | 是（`B2A_BondReqAck`，已绑回 EBRT_BOND） | 变长 `BOND_DATA_INFO`：`ucPhoneType(1) + ucPhoneInfoLen(1) + szPhoneInfo[]` |
| 0x02 | `BOND_DEV` 设备绑定 | ACK 位置位才回 | `BOND_DEV_DATA_INFO`：`ulUrlLen(1)+szUrl[]+ulPort(4 LE)+bp_res_len(1)+bp_res[]+ecg_res_len(1)+ecg_res[]+user_id_len(1)+user_id[]+comm_res_len(1)+comm_res[]` |
| 0x03 | `BOND_DEL` 删除绑定 | ACK 位置位才回，随后 `SYS_Restore()` | 无实质 payload |
| 0x04 | `BOND_CANCEL` 取消 | 是（`EBRT_SUCC`） | 无 |
| 0x05 | `BOND_KEYCODE` 鉴权码 | 是（`EBEC_SUCC`；**固件不校验内容**） | **≥20B**：`Code[0..15](16) + ble_heart(1,off16) + daily_ack(1,off17) + pro_id(2 LE,off18-19)` |
| 0x06 | `BOND_REQINFO_GET` | — | 手表侧无独立分支（读取用） |
| 0x07 | `BOND_REQINFO_RESET` | 是（信息变化时 `EBEC_SUCC` 并重启 BLE） | `BOND_DATA_INFO`：`ucPhoneType + ucPhoneInfoLen + szPhoneInfo[]` |

---

## 6. GET 读命令族（Cmd=0x02）

Key 枚举 `ENUM_B2A_GET_KEY_TYPE`（`ble2appEx.h:135-178`）。请求 payload 一般为空；下表列**应答帧的 payload 逐字节**。分发 `ble2appWrap.c:12241-12375`。GET 应答超 MTU 时按 `iMaxParamPktLen` 拆多包，`uiIndex=0..m` 递增。

### 6.1 维护控制台已实现的 GET（代码实证逐字节）

#### 6.1.1 GET DATE_TIME（Key=0x01）— 9B — ✅已实现

代码实证 `S7Messages.kt:58-105`（`S7DateTime`，GET 响应/SET 请求同布局）。

| 偏移 | 字段 | 字节数 | 字节序/编码 | 含义 / 取值 |
|---|---|---|---|---|
| 0 | year | 2 | **LE16** | 年 |
| 2 | month | 1 | u8 | 月 1..12 |
| 3 | day | 1 | u8 | 日 1..31 |
| 4 | hour | 1 | u8 | 时 ≤23 |
| 5 | minute | 1 | u8 | 分 ≤59 |
| 6 | second | 1 | u8 | 秒 ≤59 |
| 7 | week | 1 | u8 | 星期 1=周一…7=周日（Zeller 自算） |
| 8 | timezone | 1 | **有符号 int8** | 时区偏移；SET 时 0=保持设备本地时区不改 |

> `⚠差异`（代码新增，见 [§10-2](#10-代码实证-vs-文档源码差异汇总)）：`S7Messages.kt:98` 对 month/day/hour/min/sec 做**值域校验**，非法即 `parse` 返回 null → 上层报 FORMAT(0x03)（防 RTC 未初始化/恢复出厂回垃圾值）。

示例（无 golden，字段口径示意）：`BB 02 0D 00 <crc> 00 00  02 01 09 00  E9 07 07 03 0A 1E 00 05 08`（year=0x07E9=2025、07-03、10:30:00、周五、tz=+8）。

#### 6.1.2 GET PERSON_DATA（Key=0x04）— 7B — ✅已实现

代码实证 `S7Messages.kt:223-255`（`S7Person`，GET 7B）。

| 偏移 | 字段 | 字节数 | 字节序 | 含义 |
|---|---|---|---|---|
| 0 | heightCm | 1 | u8 | 身高 cm |
| 1 | weightKg | 1 | u8 | 体重 kg |
| 2 | gender | 1 | u8 | 性别 |
| 3 | birthYear | 2 | **LE16** | 出生年 |
| 5 | birthMonth | 1 | u8 | 出生月 |
| 6 | birthDay | 1 | u8 | 出生日 |

#### 6.1.3 GET DEV_INFO（Key=0x21）— 变长 TLV — ✅已实现

代码实证 `S7Messages.kt:177-220`（`S7DeviceInfo`）。布局：`swLen(1)+sw | mdLen(1)+md | secLen(1)+sec | reserved(1B=1) | bpLen(1)+bp`（各段 ASCII，段内 `takeWhile{0x20..0x7E}` 截断非打印尾部；bp 缺失容忍为 `""`）。固件副作用：置 `ucOtaMode=0xFF`。

| 偏移 | 字段 | 字节数 | 含义 |
|---|---|---|---|
| 0 | swLen | 1 | 软件版本串长 |
| 1 | swVer[] | swLen | 软件版本（仅数字与 `.`） |
| … | mdLen(1)+mdVer[] | 1+md | Modem 版本（如 "1.0"） |
| … | secLen(1)+secVer[] | 1+sec | SecBL 版本（如 "1.0"） |
| … | reserved | 1 | 固定 **1** |
| … | bpLen(1)+bpVer[] | 1+bp | 血压算法版本 |

#### 6.1.4 GET DEV_FUNC（Key=0x22）— 4B — ✅已实现

代码实证 `S7Console.kt:245-250`：4B 功能位掩码 **u32 LE**，返回 Long。`⚠` **逐位含义缺失**——固件本目录未定义 bit 语义，真机回 0，UI 仅 hex 展示。要求 ≥4B。

#### 6.1.5 GET DEV_BLE_MAC（Key=0x23）— 6B — ✅已实现（未单列）

6B MAC。控制台未单列（SN_INFO 已含 MAC）。

#### 6.1.6 GET DEV_VOL 电量（Key=0x24）— 10B — ✅已实现

代码实证 `S7Messages.kt:108-126`（`S7Battery`，要求 ≥10B）。

| 偏移 | 字段 | 字节数 | 字节序 | 含义 |
|---|---|---|---|---|
| 0 | capacityMah | 2 | **LE16** | 容量 mAh |
| 2 | voltageMv | 2 | **LE16** | 电压 mV |
| 4 | percent | 1 | u8 | 电量 % |
| 5 | chargeStatus | 1 | u8 | 充电状态 |
| 6 | ulLastTime | 4 | LE32 | 固件恒 0，**代码忽略不解析** |

#### 6.1.7 GET SN_INFO 身份（Key=0x26）— 59B 定长 — ✅已实现

代码实证 `S7Messages.kt:133-174`（`S7SnInfo`，要求 ≥59B，零填充）。布局 `5+12+6+16+20 = 59`。

| 偏移 | 字段 | 字节数 | 编码 | 含义 / 取值 |
|---|---|---|---|---|
| 0 | DevType | 5 | **二进制型号码 → hex 展示**（空格分隔；全 0 则空串） | 真机 `68 39 71 25 81` |
| 5 | SN | 12 | ASCII（遇 0x00 或非打印字节截断） | 序列号 |
| 17 | BleMac | 6 | **LE 反序存储 → 展示时 `.reversed()`**，`:` 分隔大写 hex | 真机线上 `C4:FC:19:48:61:71` = 实际 `71:61:48:19:FC:C4` |
| 23 | IMEI | 16 | ASCII 截断 | — |
| 39 | ICCID | 20 | ASCII 截断 | — |

> `⚠差异`（文档已过时，见 [§10-1](#10-代码实证-vs-文档源码差异汇总)）：源文档 protocol-spec §4.3 曾记「MAC/IMEI/ICCID 字节序待核对，按原序 hex 展示」。**代码实证已推翻**：MAC 确定为 **LE 反序**（展示 `.reversed()`）；SN/IMEI/ICCID 为 **ASCII 非 hex**；DevType 为二进制码走 hex 回退。`asciiTrim`（`:155-161`）只保留连续 0x20..0x7E，遇首个非打印字节即停。

#### 6.1.8 GET BOND 绑定状态（Key=0x28）— 1B — ✅已实现

代码实证 `S7Console.kt:252-256`：1B 绑定状态 `param[0]`（对应 `NVM_ID_BIN_BOND` 低字节）。要求 ≥1B。

### 6.2 其余 GET（业务类·⬜不在维护工具范围）

固件全集（应答 payload 语义摘要，来源②）：

| Key | 名称 | 应答 payload 摘要 |
|---|---|---|
| 0x02 | `ALARM` 闹钟 | 首字节闹钟计数 + `MAX_SDM_ALARM_NUM × ALARM_DATA_INFO` |
| 0x03 | `GOALS` 目标 | `EGOALS_DATA_INFO`（见 SET 0x03 镜像） |
| 0x05 | `WEAR` 佩戴 | 1B 佩戴开关 |
| 0x06 | `UNIT` 单位 | `UNIT_SET_DATA_INFO`：`ucHeight+ucWeight+ucDistance+ucCalorie+…` |
| 0x07 | `SEDENTARY` 久坐 | 久坐提醒结构 |
| 0x08 | `DRINK_WATER` 喝水 | 喝水提醒结构 |
| 0x09 | `QUIET_MODE` 勿扰 | 勿扰结构 |
| 0x0A | `HR_MODE` 心率模式 | 心率模式结构 |
| 0x0B | `SPO2` 血氧 | 血氧结构 |
| 0x0C | `PRESSURE` 压力 | 压力结构 |
| 0x0D | `HRSO_MODE` 心率血氧模式 | 结构 |
| 0x0E | `HR_REMIND` 心率提醒 | 阈值 |
| 0x0F | `SPO2_REMIND` 血氧提醒 | 阈值 |
| 0x10 | `PRESSURE_REMIND` 压力提醒 | 阈值 |
| 0x12 | `MEDICINE_REMIND` 吃药提醒 | 结构 |
| 0x13 | `SLEEP_REMIND` 睡眠提醒 | 结构 |
| 0x15 | `ECG_TEST_SET` ECG 定时测量 | 结构 |
| 0x25 | `DIAL_INFO` 表盘信息 | 可分片 |
| 0x27 | `IOS_MSG` iOS 消息提醒 | 文件内容，多包 END 标志 |
| 0x2A | `BLP_RAW_STATE` BP 原始态 | 5B：`on_utc(4 LE)+on_flag(1)` |

> 各 `*_DATA_INFO` 结构体的精确逐字节布局定义在固件 `product/apollo_eiot/` 的 `sdm_*.h`/`adm_*.h`（非 Ble2App 目录），本合成未逐个展开——属业务类，非维护范围。

---

## 7. SET 写命令族（0x03）· PUSH（0x04）· IND（0x05）· RPT_DATA（0x06/0x86）

### 7.1 SET 写命令族（Cmd=0x03）

Key 枚举 `ENUM_B2A_SET_KEY_TYPE`（`ble2appEx.h:181-223`，与 GET 镜像 + 表盘/iOS 系列）。方向 App→表；**ACK 位置位统一回 1B CommAck（`EBEC_SUCC`）**（`ble2appWrap.c:12924-12929`）。分发 `:12379-12931`。

#### 7.1.1 SET DATE_TIME 对时（Key=0x01）— 9B — ✅已实现

布局与 [GET 0x01](#611-get-date_timekey0x01-9b-已实现) **完全一致**（代码实证 `S7Messages.kt:232` encode）。SET 时 timezone=0 保持设备本地时区不改。响应 = 1B CommAck。

#### 7.1.2 SET PERSON_DATA 用户信息（Key=0x04）— 8B — ✅已实现

代码实证 `S7Messages.kt:232-240`（`encodeSet`）= GET 7B 布局 + 偏移 7 处 `ulReserve(1B)=0`。响应 1B CommAck。控制台**可编辑写入** + 快捷写采集用户。

| 偏移 | 字段 | 字节数 | 字节序 |
|---|---|---|---|
| 0..6 | 同 GET PERSON 7B | 7 | — |
| 7 | ulReserve | 1 | =0 |

#### 7.1.3 其余 SET（业务类·⬜不在维护工具范围）

| Key | 名称 | payload 摘要 |
|---|---|---|
| 0x02 | `ALARM` | `pszParam[0]`=闹钟索引 + `ALARM_DATA_INFO` 数组 |
| 0x03 | `GOALS` | `EGOALS_DATA_INFO`：`ulStep(4)+uiSportTime(2)+ucStandCount(1)…` |
| 0x05..0x15 | 各提醒/模式 | 与 GET 镜像结构。0x15 `ECG_TEST_REMIND`：`hour/minute/on_off/snooze/repeate` |
| 0x25 | `DIAL_CURR` 当前表盘 | `SDM_SetDevDialCurr`（不自动 ACK） |
| 0x26 | `DIAL_SEQ` 表盘序列 | **支持多包**，`ucMultiPktId=(status>>4)&0x03` |
| 0x27 | `IOS_MSG` | **支持多包**，END 帧回 EBEC_SUCC |
| 0x28 | `DIAL_DEL` 删表盘 | 支持多包；表盘开关激活回 NOT_SUPPORT |
| 0x29 | `SCIMEASURE_PARA` 科学测量参数 | `APP_Setting_ScientificMeasure_Extral` |
| 0x2A | `BLP_RAW_MODE` BP 原始模式 | ≥5B：`time_per(4 LE)+on_flag(1)`（纯回显） |

### 7.2 PUSH 推送（Cmd=0x04）

Key 枚举 `ENUM_B2A_PUSH_KEY_TYPE`（`ble2appEx.h:227-239`）。方向 App→表，ACK 位置位回 EBEC_SUCC。**⬜ 全族业务类，不在维护工具范围。**

| Key | 名称 | payload 摘要 |
|---|---|---|
| 0x01 | `PHONE` 来电 | 来电数据 → `SDM_SetIncomingData` |
| 0x02 | `MSG` 消息 | **接收侧特例**：该帧只校验 SOF，长度不符也放行、可超长/分片（`B2A_RecvData` `ble2appEx.c:616-623`） |
| 0x03 | `WEATHER` 天气 | 变长：`weather_ver(1)+city_len(1)+city_name[]+utc(4 LE)+day_count(1)+今日块7B(type/temp/temp_max/temp_min/humidity/UV/PM)+(day_count-1)×未来块3B(type/temp_max/temp_min)` |
| 0x04 | `MUSIC` 音乐 | 固件 `#if 0` 关闭 |
| 0x05 | `CAMERA` 相机 | 固件 `#if 0` 关闭 |
| 0x07 | `MEDICAL_INFO` 医疗信息 | 字符串（≤64B 截断），变化才写 `NVM_SetMedicalInfo` |

### 7.3 IND 指示/控制（Cmd=0x05）

Key 枚举 `ENUM_B2A_IND_KEY_TYPE`（`ble2appEx.h:243-262`）。双向（多数表→App 发起；App→表为 ACK）。分发 `ble2appWrap.c:13003-13074`。

#### 7.3.1 IND HEARTBEAT 心跳（Key=0x0C）— 8B — ✅已实现（被动解析）

代码实证 `S7Messages.kt:258-271`（`S7Heartbeat`，设备→App，要求 ≥8B）。固件发送时置 `EHST_ACK`（`B2A_DevNotifyHeartbeat`）。

| 偏移 | 字段 | 字节数 | 字节序 | 含义 |
|---|---|---|---|---|
| 0 | utcSeconds | 4 | **LE32** | UTC 秒（返回 Long） |
| 4 | seq | 2 | **LE16** | 自增序号 |
| 6 | batteryPercent | 1 | u8 | 电量 % |
| 7 | reserved | 1 | — | 忽略 |

> App 可回 8B（`utc(4)+seq(2)+network_status(1)+reserved(1)`），设备不逐字节解析。被动解析可白捡电量与链路活性。控制台头部显示。

#### 7.3.2 其余 IND（业务类·⬜不在维护工具范围）

| Key | 名称 | 方向 | payload 摘要 |
|---|---|---|---|
| 0x01 | `INCOMING` | 双向 | 空处理 |
| 0x02 | `SCIENTIFICMEASURE` 科学测量 | App→表 | `pszParam[0]==0x00`→测量完成回调 |
| 0x03 | `SPORTS` | 双向 | `ENUM_B2A_IND_SPORT_TYPE`：READY1/START2/PAUSE3/CONT4/STOP5 |
| 0x04 | `MUSIC` | 双向 | `ENUM_B2A_IND_MUSIC_TYPE`：PLAY1/PAUSE2/STOP3/PREV4/NEXT5/VOL_U6/VOL_D7 |
| 0x05 | `CAMERA` | 双向 | `ENUM_B2A_IND_CAMERA_TYPE`：ENTER1/EXIT2/CAP_SINGLE3/CAP_MULTI4 |
| 0x06 | `SPORTS_DATA` | 双向 | 空 |
| 0x07 | `OTHER_CTRL` 其它控制 | 双向 | `pszParam[0]`=`ENUM_B2A_OTHER_CTRL_TYPE`：FIND_PHONE1/FIND_PHONE_END2/WEATHER_REQ3/CHARGE_STATUS4/POWER_OFF5/FIND_WATCH_END6/MEDICAL_INFO_REQ7 |
| 0x0A | `HEALTH_DATA_UPDATE` | App→表 | `pszParam[0]`=索引：BLOOD_PRESSURE(7)/ECG(8) |
| 0x0C | `HEARTBEAT` | 双向 | 见 §7.3.1 |
| 0x0D | `BLP_RAW_STATE` | 双向 | 表→App 上报 5B：`time_per(4 LE)+on_flag(1)` |
| 0x0E | `HEALTH_ALERT` 主动健康告警 | 表→App | 海外版主动告警上报（替代 4G 上传） |

### 7.4 RPT_DATA 健康数据同步（Cmd=0x06/0x86）

Key 枚举 `ENUM_B2A_RPT_DATA_KEY_TYPE`（`ble2appEx.h:410-419`）。双向。分发 `ble2appWrap.c:13078-13101`（0x06 与 0x86 合并 case）。**⬜ 业务类，不在维护工具范围**——代码实证 `CMD_RPT_DATA2=0x86` 常量存在但 `S7Console.route` 未路由，走 else 塞 responses 通道（见 [§10-6](#10-代码实证-vs-文档源码差异汇总)）。

| Key | 名称 | 方向 | payload 摘要 |
|---|---|---|---|
| 0x01 | `START` 请求同步 | App→表（表回摘要） | 请求：`ulMask(4 LE)` 功能位掩码；应答：掩码内每项 `未同步天数(1)+未同步大小(4)` 依索引顺序 |
| 0x02 | `STOP` 停止 | App→表 | — |
| 0x03 | `DATA` 数据体 | 双向 | 头部 `RPT_DATA_REQ_PARAM`=`ucType(1)+ucOperate(1)+ucToday(1)+ucReserve(1)` |
| 0x04 | `ACK` 确认 | App→表 | ≥3B：`type(1)+result(1: 0成功/1失败)+today(1: 0今日/1历史)` |

> 数据索引 `ENUM_B2A_RPT_DATA_IDX_TYPE`：HR0/SPO2 1/PRESS2/ACTIVITY3/SLEEP4/BREATHING5/SPORT6/BLOOD_PRESSURE7/ECG8/BP_TREND9/WEAR10/SSHR11/SHRV12/SRRI13/SDETECT14/BP_TREND_EX15/TEMP_SLEEP16。功能掩码 `RptDataMask = 1<<idx`。同步模式：BLE=0 / 4G=1。

---

## 8. DEV_CTRL 设备控制（0x07）· TEST 工厂（0x08）

### 8.1 DEV_CTRL 设备控制（Cmd=0x07）

Key 枚举 `ENUM_B2A_DEV_CTRL_KEY_TYPE`（`ble2appEx.h:430-446`）。方向 App→表。多数**无 payload**，直接执行系统级动作（**危险命令**）。分发 `ble2appWrap.c:13105-13176`。代码实证子键值 `S7Messages.kt:34-40`。

| Key | 名称 | 危险性 | 实现 | 动作 / payload |
|---|---|---|---|---|
| 0x01 | `POWER_OFF` 关机 | ⚠ | ✅ | `SYS_PowerOff()`，无 payload，**不回包**（固件强制 ucIsOk=0） |
| 0x02 | `RESET` 重启 | ⚠ | ✅ | `SYS_Reset()`，不回包 |
| 0x03 | `RESTORE` 恢复出厂 | ⚠ | ✅ | `SYS_Restore()`，不回包 |
| 0x04 | `FIND` 找表 | — | ✅ | `UI_EVT_FIND_WATCH_START`；带 EHST_ACK 时回 1B |
| 0x05 | `FIND_END` 停止找表 | — | ✅ | `UI_EVT_FIND_WATCH_END` |
| 0x06 | `FIND_PHONE_END` 停止找手机 | — | ⬜（手机侧被找） | `APP_Find_Tip_indAck_proc` + `UI_EVT_FIND_PHONE_END` |
| 0x07 | `FILE_LOG` 拉日志 | — | ✅ | 触发 `B2A_FileLogCtrlAck`，见 [§9.2](#92-日志拉取子协议dev_ctrl-0x070x09) |
| 0x08 | `BLE_LOG` 实时日志 | — | ⛔ 固件 `#if 0` | — |
| 0x09 | `ACK_FILE_LOG` 日志回传块 | — | ✅（接收侧） | 设备发起的日志块用此 Key（表→App） |
| 0x0A | `FILE_ALGO` 拉算法文件 | — | ⬜ 诊断增强，后续 | `pszParam[0]`=`RPT_FEA_FILE_*`（SLEEP=1）；示例帧 `BB 02 06 00 A6 5E 00 00 07 0A 02 00 01 00` |
| 0x0B | `ACK_FILE_ALGO` | — | ⬜ | 应答 Key |

**危险命令（0x01/0x02/0x03）请求帧逐字节**（代码实证 `S7Frame.kt:74/312`，`needAck=false`）：
```
BB 00 04 00 <crc LE16> 00 00  07 <key> 00 00
└┘ │  └─┬┘             └─┬┘   │   │    └─┬┘
SOF │  Len=4            Idx0  │  key    paramLen=0
    status=SUCC(0x00) ⚠      cmd=0x07
```
> `⚠差异`（见 [§10-4](#10-代码实证-vs-文档源码差异汇总)）：代码对不回包命令置 `status=SUCC(0x00)` 而非 ACK；关机/重启/恢复出厂**不置 ACK 位**。以 `disconnectWaitMs`（默认 10s）内断链为成功判据（`S7Console.sendPowerCommand:304-322`）。

### 8.2 TEST 工厂测试（Cmd=0x08）

Key 枚举 `ENUM_B2A_TEST_KEY_TYPE`（`ble2appEx.h:108-117`）。方向 App→表。分发 `ble2appWrap.c:13180-13263`。**⬜ 产测专用，不在维护工具范围。**

| Key | 名称 | payload |
|---|---|---|
| 0x01 | `ENTER_FACTORY_MODE` | `pszParam[0]`=子模式：0x00 退出/0x01 BLE/0x02 UART/0x03 apollo/0x04 ASR/0x05 ECG。ACK 位置位回 `Factory_B2A_CommAck` |
| 0x02 | `MOTO_ON` 马达开 | 按 `pszParam[0]` 细分（固件无 handler，实测未实现） |
| 0x03 | `MOTO_OFF` 马达关 | 同上 |

> **产测握手 golden 帧**：`BB 02 03 00 2D 46 00 00 08 01 01`（Cmd=0x08/Key=0x01/子模式 01=BLE；短帧特例 uiLen=3；CRC=0x462D）。工厂 UART 注入：`factory_uartmode_flg==1` 时 UART 字节直灌 `SYS_BleRecvData`。

---

## 9. FILE_TRANS / OTA（0x0F）· 日志拉取子协议

### 9.1 FILE_TRANS / OTA（Cmd=0x0F）

Key 枚举 `ENUM_B2A_FILE_TRANS_KEY_TYPE`（`ble2appEx.h:451-461`）。双向。分发 `ble2appWrap.c:13267-13340`。**🅾 OTA 二期**——端到端编排（文件清单/类型组合/生效触发）文档缺失，需固件组补升级包规范。OTA 承载于 AMDTP（**不是独立 amota BLE 服务**），`ucModuleId==BMID_FILE_TRANS_OTA`。

| Key | 名称 | 方向 | payload 逐字节 |
|---|---|---|---|
| 0x01 | `START` 开始传输 | App→表（表回） | `CurrFileSize(4 LE)+Offset(4 LE)+SliceSize(4 LE)+Type(1)+ZipFlag(1)+FileNameLen(2 LE)+FileName[]` |
| 0x02 | `TRANS` 数据分片 | App→表 | 裸文件数据（分片走 `EHST_IS_MULTI_PKT`）；首包 index=0 时 `g_ulSliceDeclLen=cmd.uiLen` |
| 0x03 | `STOP` 停止 | App→表 | — |
| 0x04 | `REQ` 传输请求 | App→表（表回） | **请求**：`ModuleId(1)+IsOffset(1)+FileCount(1)+Reserved(1)+TotalSize(4 LE)`（`EHST_OTA_PART` 位→差分模式）。**应答 12B**：`Status(1)+ModuleId(1)+FileCount(1)+CurrFileIdx(1)+SliceMaxSize(4 LE)+Offset(4 LE)` |
| 0x06 | `OFFSET` 断点偏移 | App→表（表回） | 应答：`Offset(4 LE)` |

**枚举**：ModuleId `ble2appEx.h:464-477`：OTA1/GPS_FW2/WATCH_BG3/MODEM_FW4/SECBL_FW5/BP_FW6。FileType `:480-495`：BOOTLOADER1/FW2/RES3/MODEM4/GPS_FW5/GPS_HIS6/FONT7/WATCH_BG8/WATCH_CFG9/BP_FW10。Req 状态 `:498-509`：OK0/DISK_FULL1/BUSY2/MEMORY3。

**OTA 状态机**：`EOTA_IDLE→REQ→READY→START→TRANS_DATA(续片)→END`；出错 `OTAR_ERROR` 回 IDLE。OTA 期间非 FILE_TRANS 帧被丢弃。60 秒超时 `B2A_OtaTimeoutCb`。

```
EOTA_IDLE ──REQ(Key=0x04: ModuleId+IsOffset+FileCount+TotalSize)──▶ EOTA_REQ
    ▲                                                                  │
    │                                         表回 12B: Status+SliceMaxSize+Offset
    │                                                                  ▼
    │                                                             EOTA_READY
    │                                                                  │
    │            START(Key=0x01: CurrFileSize+Offset+SliceSize+Type+ZipFlag+FileName)
    │                                                                  ▼
    │◀── 60s 超时 B2A_OtaTimeoutCb ────────────  EOTA_TRANS_DATA ◀─TRANS(Key=0x02)×N─┐
    │◀── STOP(Key=0x03) / OTAR_ERROR ──────────        │      └───────续片───────────┘
    │                                                  ▼ 传完
    └───────────────────────────────────────────  EOTA_END

断点续传：OFFSET(Key=0x06) 查表侧已收水位 → 从应答 Offset 处续发 TRANS
OTA 期间非 FILE_TRANS 帧被丢弃；差分模式由帧头 EHST_OTA_PART(bit7) 标记
```


> **三套独立 OTA（来源③，供二期参考）**：
> | 路径 | per-packet 校验 | 整体校验 | 复位方式 | 编译状态 |
> |---|---|---|---|---|
> | **A** AMOTA BLE（参考实现） | CRC32（4B 包尾） | fwCrc（44B FW 头，VERIFY 比对累加 CRC） | `am_hal_reset_control(SWPOI)` 200ms | **未编译**（Ambiq 参考） |
> | **B** 资源+SecBL（已编译） | —（文件级） | 字算累加和（写前/写后比对） | `SYS_Reset()` 延时 1s | 已编译。SecBL 基址 `0x001E2000`，MRAM Key `0x12344321`，`MAC_OTA_FLAG=0x01`，回滚阈值 30 |
> | **C** AP_OTA SPI（Modem，已编译） | buf 字节累加和（4B fcs） | **CRC16 init=0xFFFF**（整文件） | reboot_slave（CP 断电重上电） | 已编译。握手 主→从 `0x12345678` / 从→主 `0x87654321`；单包 2000B |
>
> **注**：B2A 的 FILE_TRANS（Cmd=0x0F）是**手表主 MCU 固件/资源**的 OTA 承载通道；上表 A/B/C 是固件内部三套底层刷写实现，与 B2A FILE_TRANS 命令族配合（B 路径）或独立（C=Modem SPI）。

### 9.2 日志拉取子协议（DEV_CTRL 0x07/0x09）

**定位**：日志**非实时逐条**回传。运行期日志先落 eMMC 文件 `0:/sdm/eiotlog.log`（主）+ `eiotlog2.log`（备份，300KB 轮替），App 触发后设备**整文件读出并分块回传**，**无压缩、无应用层 CRC**（完整性依赖 BLE 链路层）。处理函数 `B2A_FileLogCtrlAck`（`ble2appWrap.c:11307-11655`）。**✅ 已实现**（→ Download/BlueTrace/logs/）。

**触发（App→表，Cmd=0x07/Key=0x07）请求 payload**（代码实证 `S7Console.kt:275`）：
```
01 00 00 00 00
└┘ └────┬────┘
ucModel │ szPassthru（4B，透传，语义未定）
=1（==1 拉 now+bak 两文件；!=1 走硬件日志出口）
```

**回传块（表→App，Cmd=0x07/Key=0x09，设备发起）**：
```
[szReqPrefix(=请求 payload 逐字节回显，5B)] + [szLogChunk(裸日志片，~200+B/块)]
```
- 单包缓冲上限 237B；单包真正日志字节 `maxLogChunkLen = 237 - 8 - 入参头长 ≈ 200+B`（`ble2appWrap.c:11479`，字节 memcpy 直传无变换）。
- **无块序号、无 EOF 标志** → App 按到达顺序拼接，剥掉 5B 前缀后拼接（`S7Console.kt:286`）。
- **完成判据 = 空闲超时**（代码实证 `logIdleTimeoutMs=4_000`，`S7Console.kt:55`；文档建议 4s，[§10-5](#10-代码实证-vs-文档源码差异汇总)）。UI 需注明「日志可能不完整」。
- 流控：每块 `vTaskDelay(5)` 节流；`B2A_MakePkt` 返回 `!=EECT_OK` 立即 break。进度 `log_update_progress` 25%→100%。
- 互斥：全局 `filelog_is_sending`，上送期间 `SYS_Log2File` 直接 return（防边传边写）。两文件间无边界分隔。

**日志文件内行格式**（`log.c:78`，仅供解读回传内容）：
```
[%08lu] %c/%s: <正文>\r\n
 tick(8位0填充十进制,FreeRTOS tick 非RTC) 级别(E/W/I/D) 模块名
```
等级枚举：`NONE=0 ERR=1 WRN=2 INF=3 DBG=4`（越小越紧急，编译期默认上限 INF）。崩溃 dump 也追加进 `eiotlog.log`（含 `RESET MARK:` 复位标记行）。

---

## 10. 代码实证 vs 文档（源码）差异汇总

以下 6 处为**第 4 份代码实证与固件源文档（①②③）的差异**，字节布局一律以代码实证为准：

| # | 主题 | 文档口径 | 代码实证 | 结论 |
|---|---|---|---|---|
| **1** | **SN_INFO 字节序** | protocol-spec §4.3：MAC/IMEI/ICCID「字节序待核对，按原序 hex 展示」 | `S7Messages.kt:146-150`（真机 S7-FCC4 2026-07-02）：**MAC 确为 LE 反序**（展示 `.reversed()`）；SN/IMEI/ICCID 为 **ASCII 非 hex**，遇非打印字节截断；DevType 二进制码走 hex 回退 | **文档已过时，采纳代码**。command-status 已同步 |
| **2** | **DateTime 值域校验** | §4.1 未提及 | `S7Messages.kt:98`：month/day/hour/min/sec 合法性校验，非法即 null→上层 FORMAT(0x03) | **代码新增防御**（针对 RTC 未初始化/恢复出厂垃圾值），采纳 |
| **3** | **CommAck 判定** | §2：定义 0/1/5 三值（0成功/1失败/5不支持） | `commAckOk`（`S7Messages.kt:54`）：只判 `param[0]==0`，非 0 一律当失败（错误码原样上抛） | 语义等价；代码未区分 1 与 5，但错误码透传给 DeviceError |
| **4** | **不回包命令请求帧 status** | §4.7 只说「不回包」，未明确请求帧 status | `S7Frame.kt:74,87 / S7Console.kt:312`：`needAck=false` 时 **status=SUCC(0x00)** 而非 ACK | 采纳代码：关机/重启/恢复出厂**不置 ACK 位** |
| **5** | **日志拉取完成判据** | §4.8 建议空闲 4s | `S7Console.kt:55`：`logIdleTimeoutMs=4_000` 落实；请求超时 3s（`:54`）、断链等待 10s（`:56`） | 一致，代码已落实数值 |
| **6** | **RPT_DATA2 (0x86) 路由** | 定义为健康数据同步命令 | `S7Messages.kt:21` 定义 `CMD_RPT_DATA2=0x86`，但 `S7Console.route`（`:104-126`）**未路由**，走 else 塞 responses 通道 | 业务类上报，非维护范围，代码有意不处理 |

**未坐实项（需以固件编译结果核对，来源③标注）**：
- `BKEY_DEV_CTRL_FILE_LOG` 数值：固件源码未见显式赋值，按枚举顺序推断为 0x07（代码实证侧 `CTRL_FILE_LOG=0x07` 已确认，与推断一致）；
- 各 `*_DATA_INFO` 业务结构体的逐字节布局（在 `sdm_*.h`/`adm_*.h`，未展开）；
- `DEV_FUNC`（0x22）功能掩码的逐位含义（固件本目录未定义，真机回 0）；
- AMOTA 状态码枚举中间项完整顺序（来源③仅列首尾锚点）。

---

## 11. 附录：枚举 / 状态码 / CRC / 命令索引

### 11.1 应用层错误码 EBEC（`ENUM_B2A_ERROR_CODE_TYPE`）

代码实证 `S7Messages.kt:46-51`，对齐固件 `ble2appEx.h:47-71`：

| 值 | 名 | 值 | 名 |
|---|---|---|---|
| 0x00 | SUCC 成功 | 0x07 | BUSY 忙 |
| 0x01 | FAIL 失败 | 0x08 | LOW_BAT 低电 |
| 0x02 | TIMEOUT 超时 | 0x09 | NO_DATA 无数据 |
| 0x03 | FORMAT 格式错 | 0x0A | MD5 校验错 |
| 0x04 | MEMORY 内存错 | 0x0B | CRC 校验错 |
| 0x05 | NOT_SUPPORT 不支持 | 0x0C | FATAL 致命错 |
| 0x06 | PARAM 参数错 | 0x0D | FSUM_FAIL 文件累加和错 |

**通用应答 CommAck（1B）**：payload = 1 字节错误码；`commAckOk = param 非空 && param[0]==0`（代码只判是否为 0）。SET 类响应统一 1B CommAck。

### 11.2 帧头 ucStatus 位（`S7Status`）

见 [§2.4](#24-ucstatus-位域逐-bit-展开)：SUCC 0x00 / FAIL bit0 / ACK bit1 / IS_MULTI_PKT bit2 / MULTI_PKT_END bit3 / 多包ID bit[5:4] / OTA_PART bit7。

### 11.3 CRC 算法

**CRC16-CCITT-FALSE**：poly 0x1021 / init 0xFFFF / 不反转 / xorout 0，覆盖仅 payload。golden：`CRC16(08 01 01)=0x462D`。（另：OTA-C 整文件亦用 CRC16 init 0xFFFF；OTA-A 包级用 CRC32。）

### 11.4 全命令索引（cmd/key hex）

| Cmd | Key | 命令 | 方向 | 回复 | 实现 |
|---|---|---|---|---|---|
| 0x01 | 0x01-0x07 | BOND 绑定族 | App→表 | 部分 | ⬜ |
| 0x02 | 0x01 | GET DATE_TIME | App→表 | 是 | ✅ |
| 0x02 | 0x04 | GET PERSON_DATA | App→表 | 是 | ✅ |
| 0x02 | 0x21 | GET DEV_INFO | App→表 | 是 | ✅ |
| 0x02 | 0x22 | GET DEV_FUNC | App→表 | 是 | ✅ |
| 0x02 | 0x23 | GET DEV_BLE_MAC | App→表 | 是 | ✅ |
| 0x02 | 0x24 | GET DEV_VOL 电量 | App→表 | 是 | ✅ |
| 0x02 | 0x26 | GET SN_INFO 身份 | App→表 | 是 | ✅ |
| 0x02 | 0x28 | GET BOND 绑定态 | App→表 | 是 | ✅ |
| 0x02 | 0x02/03/05-15/25/27/2A | GET 业务类 | App→表 | 是 | ⬜ |
| 0x03 | 0x01 | SET DATE_TIME 对时 | App→表 | 1B Ack | ✅ |
| 0x03 | 0x04 | SET PERSON_DATA | App→表 | 1B Ack | ✅ |
| 0x03 | 0x02/03/05-15/25-2A | SET 业务类 | App→表 | 1B Ack | ⬜ |
| 0x04 | 0x01-0x07 | PUSH 推送族 | App→表 | Ack | ⬜ |
| 0x05 | 0x0C | IND HEARTBEAT 心跳 | 表→App | (ACK) | ✅ 被动 |
| 0x05 | 其余 | IND 业务类 | 双向 | 部分 | ⬜ |
| 0x06/0x86 | 0x01-0x04 | RPT_DATA 健康同步 | 双向 | 部分 | ⬜ |
| 0x07 | 0x01 | DEV_CTRL POWER_OFF 关机 | App→表 | 否 | ✅ |
| 0x07 | 0x02 | DEV_CTRL RESET 重启 | App→表 | 否 | ✅ |
| 0x07 | 0x03 | DEV_CTRL RESTORE 恢复出厂 | App→表 | 否 | ✅ |
| 0x07 | 0x04 | DEV_CTRL FIND 找表 | App→表 | 可 | ✅ |
| 0x07 | 0x05 | DEV_CTRL FIND_END 停止找表 | App→表 | 可 | ✅ |
| 0x07 | 0x06 | DEV_CTRL FIND_PHONE_END | App→表 | 可 | ⬜ |
| 0x07 | 0x07 | DEV_CTRL FILE_LOG 拉日志 | App→表 | 块流 | ✅ |
| 0x07 | 0x08 | DEV_CTRL BLE_LOG | — | — | ⛔ #if 0 |
| 0x07 | 0x09 | DEV_CTRL ACK_FILE_LOG 日志块 | 表→App | — | ✅ 接收 |
| 0x07 | 0x0A/0x0B | DEV_CTRL FILE_ALGO 算法文件 | 双向 | — | ⬜ |
| 0x08 | 0x01-0x03 | TEST 工厂 | App→表 | 部分 | ⬜ |
| 0x0F | 0x01-0x06 | FILE_TRANS / OTA | 双向 | 部分 | 🅾 二期 |

**命令总数**：一级命令 **10 个**（0x01-0x08 + 0x0F + 0x86）；Key 级命令条目穷尽后约 **90+ 条**（BOND 7 + GET 28 + SET 29 + PUSH 6 + IND 14 + RPT_DATA 4 + DEV_CTRL 11 + TEST 3 + FILE_TRANS 6）。维护控制台**已实现 15 条**（GET 8 + SET 2 + DEV_CTRL 7 中的核心 + IND 心跳 1，去重计）。

### 11.5 关键源文件路径

**BlueTrace 侧（代码实证，字节布局最高权威）**：
- `E:\BlueTrace\shared\src\commonMain\kotlin\io\bluetrace\shared\s7\S7Frame.kt`（帧编解码 + CRC + 位域）
- `E:\BlueTrace\shared\src\commonMain\kotlin\io\bluetrace\shared\s7\S7Messages.kt`（各消息数据类逐字节）
- `E:\BlueTrace\shared\src\commonMain\kotlin\io\bluetrace\shared\s7\S7Console.kt`（请求编码 + 日志拉取 + 危险命令）
- `E:\BlueTrace\shared\src\commonMain\kotlin\io\bluetrace\shared\s7\B2aDetect.kt`（扫描识别）

**固件侧（语义/命令全集/示例来源）**：
- `E:/1/apollo4_watch_s7/product/apollo_eiot/Ble2App/ble2appEx.h`（枚举/结构 :20,26-43,74-81,85-104,513-518）
- `E:/1/apollo4_watch_s7/product/apollo_eiot/Ble2App/ble2appEx.c`（帧头/CRC/收发 :555,595-611,644）
- `E:/1/apollo4_watch_s7/product/apollo_eiot/Ble2App/ble2appWrap.c`（命令分发 + 各 `B2A_*Ack` payload 构造 + 日志 :11307-11655）
- `E:/1/apollo4_watch_s7/product/apollo_eiot/SDK/ambiq_ble/services/svc_amdtp.h`（UUID/句柄）
- `E:/1/apollo4_watch_s7/product/apollo_eiot/log/log.c`（日志行格式 :78）
- `E:/1/apollo4_watch_s7/product/apollo_eiot/OTA/OTA_Main.c`（OTA-B）、`AMUX/AP_OTA/src/master_ota_spi.c`（OTA-C）

---

> 本文档为合成稿。字节布局以 BlueTrace 代码实证为准，命令全集/示例/语义补自固件源码。差异见 [第 10 章](#10-代码实证-vs-文档源码差异汇总)。相关：[protocol-spec.md](protocol-spec.md)（实现级收敛稿）、[command-status.md](command-status.md)（实现状态清单）。
