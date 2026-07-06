# BlueTrace BLE 私有协议帧规格 v0.1（开发者版）

> **本文是 [SPEC.md §4](../../SPEC.md) + [`bluetrace_v0.proto`](bluetrace_v0.proto) 的开发者重排版**：同一套事实，重排成"帧布局表 + bit 级内存图 + 实例包逐字节 decode + 状态机"的实现视角。**规格冲突时以 SPEC §4 与 `.proto` 为准**；帧头常量与 [`FrameHeader.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/FrameHeader.kt) 对齐。
>
> **状态**：v0.1 草案，**待与固件端共同冻结**（待冻结项见 SPEC §10.4 / [归档协议文档 §15](../归档/历史规格_v1-v3/BlueTrace_Protocol.md)）。文中示例包为按本规格**实算生成并自解码回验**的合规字节（CRC-8 按 poly `0x07`/init `0x00`、MSB-first 无反射计算，反射约定属待冻结项），非真机抓包——真实 DUT 链路尚未上线（见 §11 代码现状）。
>
> **适用范围**：仅 **DUT（自研设备）↔ App** 私有协议。REFERENCE（标准心率带）走 SIG HRS `0x180D`，不用本协议。

| 项 | 内容 |
| --- | --- |
| 协议版本 | v0.1（草案），协议头 `ver = 0x00` |
| 字节序 | **小端 little-endian**（多字节字段一律 LE，待固件确认） |
| 传输 | BLE GATT：设备→App 走 Notify，App→设备 走 Write |
| payload 编码 | protobuf（proto3，Wire 生成）；高频样本走紧凑定宽 `bytes` |
| 解码层 | KMP `:shared` commonMain，纯 Kotlin、无 Android 依赖 |

---

## 1. Purpose

为算法侧采集 PPG/ECG/ACC 等生理数据：设备按本协议把传感器样本批量推给 App，App 落原始 HEX（source of truth）+ 解码 CSV；App 通过控制通道下发传感器开关/采样率/算法/会话启停，并以 `Ack` 闭环确认。

设计约束（帧设计哲学，SPEC §4 设计目标）：

- **最小开销**：12B 定长头对"一包几十~几百样本"的高频批包是可忽略开销；高频样本不逐样本上 protobuf tag，走紧凑定宽数组（§5.1）。
- **可扩展（TLV）**：外层是 TLV 思路的落地——**T = `msgType`（1B），L = `payloadLen`（u16），V = protobuf payload**；加传感器 = 加消息类型/枚举，旧端必须能跳过未知类型（§4）。
- **可调试**：`pktSeq`（包级）+ `batch_seq`（流级）双序号量化丢包；原始字节全量落 hexlog 可重放。
- **单包装不下就分片**：偶发大消息（如 `DeviceCapability`）按 `msgId` 分片重组（§6.2）。
- **小消息拼包省带宽**：多个非分片帧可拼进一个 BLE notification（§6.1）。

## 2. System Context

```
DUT 设备 (固件)  ──BLE GATT Notify──▶  App (Android, KMP :shared 解码)  ──▶  会话文件夹
             ◀──BLE GATT Write ──                                            raw HEX + CSV + manifest
```

<div class="fig">
<svg viewBox="0 0 840 300" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>协议分层模型：BLE GATT 之上是 12B 标准帧层，再上是 protobuf payload，顶层是强类型样本</title>
<desc>四层横向色带自下而上：BLE GATT 传输层（Notify/Write 双特征）、标准帧层（切帧/分片/CRC）、protobuf payload 层（按 msgType 分发）、强类型样本层（Flow SensorSample）。右侧标注每层职责。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.ln{stroke:var(--muted);stroke-width:1.2;}
</style>
<defs><marker id="ar1" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--muted)"/></marker></defs>
<g class="m">
  <rect x="60" y="20" width="560" height="48" class="bx"/>
  <text x="340" y="40" text-anchor="middle">解码后样本 SensorSample / Flow&lt;T&gt;</text>
  <text x="340" y="58" text-anchor="middle" class="s">上层只见强类型样本</text>
  <rect x="60" y="88" width="560" height="48" class="bx"/>
  <text x="340" y="108" text-anchor="middle">protobuf payload（V）</text>
  <text x="340" y="126" text-anchor="middle" class="s">每类消息一个 message，Wire 解码</text>
  <rect x="60" y="156" width="560" height="48" class="bxa"/>
  <text x="340" y="176" text-anchor="middle" class="a">标准帧头 12B（ver + T + 长度 + 分片 + CRC）</text>
  <text x="340" y="194" text-anchor="middle" class="s">本协议核心：切帧 / msgType 分发 / 分片重组 / hdrCrc8</text>
  <rect x="60" y="224" width="560" height="48" class="bx"/>
  <text x="340" y="244" text-anchor="middle">BLE GATT notification / write</text>
  <text x="340" y="262" text-anchor="middle" class="s">MTU 与连接参数全由设备按功耗定，App 不调</text>
</g>
<line x1="340" y1="88" x2="340" y2="68" class="ln" marker-end="url(#ar1)"/>
<line x1="340" y1="156" x2="340" y2="136" class="ln" marker-end="url(#ar1)"/>
<line x1="340" y1="224" x2="340" y2="204" class="ln" marker-end="url(#ar1)"/>
<g class="s">
  <text x="640" y="46">← 采集页 / CSV 写入器</text>
  <text x="640" y="114">← bluetrace_v0.proto</text>
  <text x="640" y="182" class="a">← 本文 §3–§8</text>
  <text x="640" y="250">← DUT_NOTIFY / DUT_WRITE</text>
</g>
</svg>
</div>

**GATT 通道**（SPEC §4.2，UUID 占位在 `DutUuids`，冻结时替换）：

| 角色 | Characteristic | 方向 | 属性 |
| --- | --- | --- | --- |
| 数据流 | `DUT_NOTIFY_CHAR` | 设备 → App | Notify |
| 控制 | `DUT_WRITE_CHAR` | App → 设备 | Write / WriteWithoutResponse |

- App 作 Central，只订阅 Notify、解帧、解码；**不主动调连接间隔/MTU**。
- **写方向 v0 不做协议级分片**：每条 `Command` 必须装进单次 ATT write；可靠性靠 `cmd_id`+`Ack` 闭环，写方向不使用 `pktSeq`/`NEEDS_ACK`。

## 3. Frame Layout

一个**帧** = 12B 定长头部 + `payloadLen` 字节 payload。一个 BLE notification 可含 1..N 个非分片帧；分片帧独占整包。

### 3.1 Frame Overview（SPEC §4.3）

| Offset | Size | Field | Description |
| --- | --- | --- | --- |
| 0 | 1B | `ver` | 协议版本，v0 = `0x00`；不匹配 → 停止解析该包（§8） |
| 1 | 1B | `msgType` | 消息类型 = TLV 的 **T**，见 §4 注册表 |
| 2 | 1B | `flags` | 位标志，见 §3.3 |
| 3 | 1B | `fragIndex` | 分片序号 0..fragCount-1（非分片 = 0） |
| 4 | 1B | `fragCount` | 分片总数（非分片 = 1）；u8 → 单消息最多 255 片 |
| 5 | 1B | `hdrCrc8` | **强制**头部自检：CRC-8（poly `0x07`、init `0x00`）覆盖头部除本字节外 11 字节（偏移 0–4、6–11） |
| 6 | 2B | `pktSeq` | 包序号（u16 LE）：每个 BLE notification +1，模 2^16 回绕；同包多帧共享同值；每连接重置 |
| 8 | 2B | `msgId` | 逻辑消息 ID（u16 LE）：同一消息的分片共享，用于重组 |
| 10 | 2B | `payloadLen` | 本帧 payload 字节数（u16 LE）= TLV 的 **L** |
| 12 | N B | `payload` | protobuf message（按 `msgType` 分发）= TLV 的 **V** |
| 12+N-2 | (2B) | `payloadCrc16` | **可选**：仅 `HAS_PAYLOAD_CRC=1` 时存在，CRC16/CCITT 覆盖前 `payloadLen-2` 字节；此时 protobuf 实际长度 = `payloadLen-2` |

### 3.2 Bit-level memory map

```
Byte Offset:   0        1        2        3        4        5
              +--------+--------+--------+--------+--------+--------+
              | ver    | msgType| flags  | fragIdx| fragCnt| hdrCrc8|
              | 0x00   |  = T   | 位图↓  | u8     | u8     | 强制   |
              +--------+--------+--------+--------+--------+--------+
Byte Offset:   6        7        8        9        10       11
              +--------+--------+--------+--------+--------+--------+
              |  pktSeq u16 LE  |  msgId u16 LE   | payloadLen u16  |
              |  lo     |  hi   |  lo    |  hi    |  lo=L  |  hi    |
              +--------+--------+--------+--------+--------+--------+
Byte Offset:   12 ...                                12+payloadLen-1
              +--------------------------------------------------+--
              | payload = protobuf message (V)     [| CRC16 末2B]|
              +--------------------------------------------------+--

flags (byte 2)，bit7 → bit0：
              +------+------+------+----------------+-------+-----------+------------+-----------+
         bit  |  7   |  6   |  5   |       4        |   3   |     2     |     1      |     0     |
              +------+------+------+----------------+-------+-----------+------------+-----------+
              |      保留 =0       | HAS_PAYLOAD_CRC| BATCH | NEEDS_ACK | FRAGMENTED | MORE_FRAG |
              |                    |      0x10      | 0x08  | 0x04 保留 |    0x02    |   0x01    |
              +------+------+------+----------------+-------+-----------+------------+-----------+
```

<div class="fig">
<svg viewBox="0 0 840 380" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>BlueTrace v0 标准帧结构：12 字节头部 + payload，flags 逐位展开</title>
<desc>上排为 12 字节头部逐字节布局（ver、msgType、flags、fragIndex、fragCount、hdrCrc8、pktSeq、msgId、payloadLen），中排为 payload 与可选 CRC16，下排把 flags 的 8 个位展开标注。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.ln{stroke:var(--line);stroke-width:1;}
.lna{stroke:var(--accent);stroke-width:1.2;}
</style>
<text x="40" y="20" class="t">标准帧 = 12B 头部 + payload（小端，SPEC §4.3）</text>
<g class="ms">
  <text x="68" y="44" text-anchor="middle">0</text><text x="124" y="44" text-anchor="middle">1</text>
  <text x="180" y="44" text-anchor="middle">2</text><text x="236" y="44" text-anchor="middle">3</text>
  <text x="292" y="44" text-anchor="middle">4</text><text x="348" y="44" text-anchor="middle">5</text>
  <text x="404" y="44" text-anchor="middle">6</text><text x="460" y="44" text-anchor="middle">7</text>
  <text x="516" y="44" text-anchor="middle">8</text><text x="572" y="44" text-anchor="middle">9</text>
  <text x="628" y="44" text-anchor="middle">10</text><text x="684" y="44" text-anchor="middle">11</text>
</g>
<g class="m">
  <rect x="40" y="50" width="56" height="52" class="bx"/>
  <text x="68" y="72" text-anchor="middle">ver</text><text x="68" y="90" text-anchor="middle" class="ms">0x00</text>
  <rect x="96" y="50" width="56" height="52" class="bx"/>
  <text x="124" y="72" text-anchor="middle" class="a">msgType</text><text x="124" y="90" text-anchor="middle" class="ms">T</text>
  <rect x="152" y="50" width="56" height="52" class="bxa"/>
  <text x="180" y="72" text-anchor="middle" class="a">flags</text><text x="180" y="90" text-anchor="middle" class="ms">位图↓</text>
  <rect x="208" y="50" width="56" height="52" class="bx"/>
  <text x="236" y="72" text-anchor="middle">fragIdx</text><text x="236" y="90" text-anchor="middle" class="ms">u8</text>
  <rect x="264" y="50" width="56" height="52" class="bx"/>
  <text x="292" y="72" text-anchor="middle">fragCnt</text><text x="292" y="90" text-anchor="middle" class="ms">u8</text>
  <rect x="320" y="50" width="56" height="52" class="bxa"/>
  <text x="348" y="72" text-anchor="middle" class="a">hdrCrc8</text><text x="348" y="90" text-anchor="middle" class="ms">强制</text>
  <rect x="376" y="50" width="112" height="52" class="bx"/>
  <text x="432" y="72" text-anchor="middle">pktSeq</text><text x="432" y="90" text-anchor="middle" class="ms">u16 LE</text>
  <rect x="488" y="50" width="112" height="52" class="bx"/>
  <text x="544" y="72" text-anchor="middle">msgId</text><text x="544" y="90" text-anchor="middle" class="ms">u16 LE</text>
  <rect x="600" y="50" width="112" height="52" class="bx"/>
  <text x="656" y="72" text-anchor="middle" class="a">payloadLen</text><text x="656" y="90" text-anchor="middle" class="ms">u16 LE = L</text>
</g>
<g class="m">
  <rect x="40" y="130" width="560" height="46" class="bx"/>
  <text x="320" y="150" text-anchor="middle">payload（payloadLen 字节）= protobuf message</text>
  <text x="320" y="166" text-anchor="middle" class="ms">按 msgType 分发 = V</text>
  <rect x="600" y="130" width="112" height="46" class="bx" stroke-dasharray="4 3"/>
  <text x="656" y="150" text-anchor="middle">CRC16 可选</text>
  <text x="656" y="166" text-anchor="middle" class="ms">HAS_PAYLOAD_CRC=1</text>
</g>
<line x1="376" y1="102" x2="376" y2="130" class="ln"/>
<text x="726" y="156" class="s">payload 末2B</text>
<line x1="180" y1="102" x2="180" y2="238" class="lna"/>
<text x="40" y="228" class="t">flags 位图（bit7 → bit0）</text>
<g class="m">
  <rect x="40" y="238" width="168" height="52" class="bx"/>
  <text x="124" y="260" text-anchor="middle">保留=0</text><text x="124" y="278" text-anchor="middle" class="ms">bit 7–5</text>
  <rect x="208" y="238" width="126" height="52" class="bx"/>
  <text x="271" y="260" text-anchor="middle">HAS_PAYLOAD_CRC</text><text x="271" y="278" text-anchor="middle" class="ms">bit4 0x10</text>
  <rect x="334" y="238" width="126" height="52" class="bx"/>
  <text x="397" y="260" text-anchor="middle">BATCH</text><text x="397" y="278" text-anchor="middle" class="ms">bit3 0x08</text>
  <rect x="460" y="238" width="126" height="52" class="bx"/>
  <text x="523" y="260" text-anchor="middle">NEEDS_ACK 保留</text><text x="523" y="278" text-anchor="middle" class="ms">bit2 0x04</text>
  <rect x="586" y="238" width="126" height="52" class="bxa"/>
  <text x="649" y="260" text-anchor="middle" class="a">FRAGMENTED</text><text x="649" y="278" text-anchor="middle" class="ms">bit1 0x02</text>
  <rect x="712" y="238" width="112" height="52" class="bxa"/>
  <text x="768" y="260" text-anchor="middle" class="a">MORE_FRAG</text><text x="768" y="278" text-anchor="middle" class="ms">bit0 0x01</text>
</g>
<text x="40" y="322" class="s">hdrCrc8 = CRC-8(poly 0x07, init 0x00)，覆盖头部除自身外 11 字节；一个 BLE notification 可装 1..N 个非分片帧；</text>
<text x="40" y="340" class="s">分片帧（FRAGMENTED=1）必须独占整包。常量与 FrameHeader.kt 对齐（FLAG_MORE_FRAGMENTS=0x01 … FLAG_HAS_PAYLOAD_CRC=0x10）。</text>
</svg>
</div>

### 3.3 flags 位定义（SPEC §4.3；常量见 [`FrameHeader.kt:25`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/FrameHeader.kt)）

| bit | 掩码 | 名称 | 含义 |
| --- | --- | --- | --- |
| 0 | `0x01` | `MORE_FRAGMENTS` | 还有后续分片（最后一片为 0） |
| 1 | `0x02` | `FRAGMENTED` | 本帧属于一条分片消息（独占整包） |
| 2 | `0x04` | `NEEDS_ACK` | 保留；v0 控制走 `cmd_id`/`Ack` 闭环 |
| 3 | `0x08` | `BATCH` | 1=高频批包 / 0=低频组帧（语义提示，可选） |
| 4 | `0x10` | `HAS_PAYLOAD_CRC` | payload 末尾 2B 为 CRC16/CCITT |
| 5–7 | — | 保留 | 置 0 |

## 4. Payload TLV：msgType 注册表

外层 TLV：**T** = `msgType`（byte 1），**L** = `payloadLen`（byte 10–11），**V** = payload（protobuf message，按 T 查下表分发；protobuf 内不再包 envelope，省 tag 开销）。注册表（SPEC §4.6；常量镜像在 [`FrameHeader.kt:34`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/FrameHeader.kt) `MsgType`）：

| msgType (T) | 名称 | 方向 | payload message (V) | 说明 |
| --- | --- | --- | --- | --- |
| `0x01` | HIGH_FREQ_BATCH | 设备→App | `HighFreqBatch` | PPG/ECG/IMU/ACC 高频批包（§5.1） |
| `0x02` | LOW_FREQ_FRAME | 设备→App | `LowFreqFrame` | 地磁/温度低频组帧（§5.2） |
| `0x03` | GOODIX_PPG_ACC | 设备→App | `GoodixPpgAcc` | 汇顶 PPG+ACC 组合包 → 兼容 CSV（§5.3） |
| `0x10` | DEVICE_EVENT | 设备→App | `DeviceEvent` | 设备事件/告警 |
| `0x11` | DEVICE_CAPABILITY | 设备→App | `DeviceCapability` | 静态能力：传感器集+采样率+算法 |
| `0x12` | DEVICE_STATE | 设备→App | `DeviceState` | 当前生效配置（重连对账） |
| `0x20` | ALGO_RESULT | 设备→App | `AlgoResult` | 设备端算法结果 |
| `0x40` | COMMAND | App→设备 | `Command` | 控制指令（§5.4） |
| `0x80` | ACK | 设备→App | `Ack` | 指令回执 |
| `0x04–0x0F` | 保留·数据 | | | 新增传感器消息 |
| `0x41–0x7F` | 保留·控制 | | | |
| `0xC0–0xFF` | 厂商/实验 | | | 私有扩展 |

> **未知/保留 msgType 强制处理**：不在 dispatch 表中的 T，**必须用 L（`payloadLen`）跳过 V 并继续解析本包余帧**（计数诊断），绝不中止整包——"加传感器不破坏旧端"的前提。

## 5. Payload 定义（以 [`bluetrace_v0.proto`](bluetrace_v0.proto) 为唯一权威）

### 5.1 HighFreqBatch（T=`0x01`）——高频批包

protobuf 字段（[`bluetrace_v0.proto:21`](bluetrace_v0.proto)）：

| tag | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| 1 | `sensor_id` | uint32 | 见 `SensorId` 枚举（§5.6） |
| 2 | `base_device_ts_us` | uint64 | 首样本设备单调时钟（us） |
| 3 | `sample_period_ns` | uint32 | 每样本间隔（ns），精确计时源；展示速率 = 1e9 / period |
| 4 | `sample_count` | uint32 | 本批样本数 |
| 5 | `channel_count` | uint32 | ACC=3、PPG=1..N LED |
| 6 | `format` | `SampleFormat` | **不得为 `SF_UNSPECIFIED`** |
| 7 | `batch_seq` | uint32 | 该 sensor_id 流的批序号，从 0 起，模 2^32（流级丢包检测） |
| 8 | `samples` | bytes | 紧凑定宽样本数组，**长度必须 == `sample_count*channel_count*width(format)`**，否则丢弃该批计错 |

`samples` 字节布局（**行主序、小端、紧密无填充**，SPEC §4.7）：

```
byte:  0        w        2w                (C-1)w    Cw       ...
      +--------+--------+-- ... --+--------+--------+-- ...
      |s[0]ch0 |s[0]ch1 |   ...   |s[0]ch  |s[1]ch0 |  ...     w = width(format)
      |        |        |         |  C-1   |        |          共 sample_count*channel_count 个值
      +--------+--------+-- ... --+--------+--------+-- ...
```

<div class="fig">
<svg viewBox="0 0 840 240" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>samples 行主序布局：示例 1 的 4 样本 × 3 通道 × S16（24 字节）</title>
<desc>网格 4 行 3 列，每格一个 S16 小端值并标注其 2 字节十六进制与字节偏移，行 = 样本（各有内插时间戳），列 = 通道 x/y/z。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
</style>
<text x="40" y="20" class="t">samples = 4 样本 × 3 通道 × S16（w=2B），行主序共 24B（示例 1 实际字节）</text>
<g class="ms">
  <text x="150" y="46" text-anchor="middle">ch0 (x)</text>
  <text x="330" y="46" text-anchor="middle">ch1 (y)</text>
  <text x="510" y="46" text-anchor="middle">ch2 (z)</text>
  <text x="640" y="46">样本时间（内插）</text>
</g>
<g class="m">
  <rect x="60" y="52" width="180" height="36" class="bxa"/><text x="150" y="74" text-anchor="middle" class="a">10 00 = 16</text>
  <rect x="240" y="52" width="180" height="36" class="bxa"/><text x="330" y="74" text-anchor="middle" class="a">E0 FF = -32</text>
  <rect x="420" y="52" width="180" height="36" class="bxa"/><text x="510" y="74" text-anchor="middle" class="a">E8 03 = 1000</text>
  <text x="614" y="74" class="ms">t0 = base+0 = 1000000us</text>
  <rect x="60" y="88" width="180" height="36" class="bx"/><text x="150" y="110" text-anchor="middle">11 00 = 17</text>
  <rect x="240" y="88" width="180" height="36" class="bx"/><text x="330" y="110" text-anchor="middle">E2 FF = -30</text>
  <rect x="420" y="88" width="180" height="36" class="bx"/><text x="510" y="110" text-anchor="middle">EA 03 = 1002</text>
  <text x="614" y="110" class="ms">t1 = base+20000us</text>
  <rect x="60" y="124" width="180" height="36" class="bx"/><text x="150" y="146" text-anchor="middle">12 00 = 18</text>
  <rect x="240" y="124" width="180" height="36" class="bx"/><text x="330" y="146" text-anchor="middle">E4 FF = -28</text>
  <rect x="420" y="124" width="180" height="36" class="bx"/><text x="510" y="146" text-anchor="middle">E7 03 = 999</text>
  <text x="614" y="146" class="ms">t2 = base+40000us</text>
  <rect x="60" y="160" width="180" height="36" class="bx"/><text x="150" y="182" text-anchor="middle">14 00 = 20</text>
  <rect x="240" y="160" width="180" height="36" class="bx"/><text x="330" y="182" text-anchor="middle">E7 FF = -25</text>
  <rect x="420" y="160" width="180" height="36" class="bx"/><text x="510" y="182" text-anchor="middle">E4 03 = 996</text>
  <text x="614" y="182" class="ms">t3 = base+60000us</text>
</g>
<g class="ms">
  <text x="52" y="74" text-anchor="end">s0</text><text x="52" y="110" text-anchor="end">s1</text>
  <text x="52" y="146" text-anchor="end">s2</text><text x="52" y="182" text-anchor="end">s3</text>
</g>
<text x="40" y="222" class="s">字节偏移 = (样本号×3 + 通道号)×2；第 i 样本设备时间 = base_device_ts_us + round(i × sample_period_ns / 1000)</text>
</svg>
</div>

**每值字节宽度与解码（强制表）**：

| SampleFormat | 枚举值 | 宽度 | 解码 |
| --- | --- | --- | --- |
| `SF_UNSPECIFIED` | 0 | — | **非法**：该批按解码错误处理 |
| `SF_U16` / `SF_S16` | 1 / 2 | 2B | 小端；S16 二补码 |
| `SF_U24` / `SF_S24` | 3 / 4 | 3B | 小端，紧密 3B 无对齐填充；**S24 须按 bit23 符号扩展**：`v = b0|b1<<8|b2<<16; signed = (v ^ 0x800000) - 0x800000`（测试向量 `0xFFFFFF → -1`）；U24 零扩展 |
| `SF_S32` / `SF_F32` | 5 / 6 | 4B | 小端；F32 = IEEE754 |

**第 i 个样本设备时间** = `base_device_ts_us + round(i * sample_period_ns / 1000)`——用纳秒周期而非整数 Hz，避免 12.5 Hz 这类小数率长会话累积漂移。

### 5.2 LowFreqFrame（T=`0x02`）——低频组帧

一帧多路低频读数（[`bluetrace_v0.proto:47`](bluetrace_v0.proto)）：`LowFreqFrame{ device_ts_us(1), readings(2) }`，`LowFreqReading{ sensor_id(1), values(2, packed sint32), scale_milli(3) }`。定点缩放：**真实值 = value × scale_milli / 1000；`scale_milli == 0` 表示不缩放**（避免 proto3 零默认把数据清零）。

### 5.3 GoodixPpgAcc（T=`0x03`）——汇顶 PPG+ACC 组合包

PPG 与 ACC **各带独立** `period/count/channel/format` + `bytes`（两流 ODR 通常不同；tag 1–11 见 [`bluetrace_v0.proto:64`](bluetrace_v0.proto)）。各 `bytes` 块内布局同 §5.1 规则；精确布局待与固件/汇顶规格对齐（待冻结项）。落兼容 CSV（F-FILE-7）。

### 5.4 Command（T=`0x40`）与 Ack（T=`0x80`）——控制闭环

`Command{ cmd_id(1), oneof body }`，oneof 成员（tag 2–8）：

| tag | 子消息 | 字段 | 用途 |
| --- | --- | --- | --- |
| 2 | `SetSensorEnable` | `sensor_id, enabled` | 开关传感器 |
| 3 | `SetSampleRate` | `sensor_id, sample_rate_mhz` | 采样率（**毫赫兹**，25000 = 25 Hz） |
| 4 | `SelectAlgorithm` | `algorithm_ids[]`（packed） | 设备端算法 |
| 5 | `SetPacking` | `batch_samples, frame_period_ms` | 应用层打包旋钮（固件调试用） |
| 6 | `SessionControl` | `action`：STOP=0 / START=1 | 会话启停 |
| 7 | `QueryCapability` | （空） | 请求 `DeviceCapability` |
| 8 | `QueryState` | （空） | 请求 `DeviceState`（重连对账） |

`Ack{ cmd_id(1), status(2), detail(3) }`：`status 0 = OK`，其它错误码待固件定义；`cmd_id` 回显对应指令。

### 5.5 事件 / 能力 / 状态 / 算法结果

- `DeviceEvent`（`0x10`）：`code(1), detail(2), device_ts_us(3)`。
- `DeviceCapability`（`0x11`，静态能力=真理源）：`firmware_version(1)`、`sensors(2)=SensorCap{sensor_id, high_freq, supported_rates_mhz[](packed 毫赫兹), channel_count}`、`algorithm_ids(3, packed)`。
- `DeviceState`（`0x12`，当前状态=对账源）：`sensors(1)=SensorState{sensor_id, enabled, current_rate_mhz}`（**含被设备夹紧后的真实生效值**）、`active_algorithm_ids(2, packed)`。
- `AlgoResult`（`0x20`）：`algorithm_id(1), device_ts_us(2), metrics(3)=AlgoMetric{metric_id, value:float}`（每个输出带 metric_id，可命名列）。

### 5.6 SensorId 枚举（待冻结）

| 值 | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 含义 | UNSPECIFIED | PPG | ECG | ACC | GYRO | MAG | TEMP |

## 6. 组包规则：一包多帧 与 分片重组

<div class="fig">
<svg viewBox="0 0 840 330" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>一包多帧（拼接）与大消息分片对比</title>
<desc>上半部分：一个 BLE notification 内并排三个非分片帧共享同一 pktSeq。下半部分：一条大消息按 msgId 切成三个分片帧，各自独占一个 notification，flags 标出 FRAGMENTED 与 MORE_FRAGMENTS。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.wrap{fill:none;stroke:var(--muted);stroke-width:1.2;stroke-dasharray:5 4;}
</style>
<text x="40" y="22" class="t">① 一包多帧：多条小消息拼进一个 notification（仅限非分片帧，共享 pktSeq）</text>
<rect x="40" y="34" width="640" height="64" rx="8" class="wrap"/>
<text x="694" y="60" class="s">1 个 BLE</text>
<text x="694" y="74" class="s">notification</text>
<g class="m">
  <rect x="56" y="46" width="80" height="40" class="bxa"/><text x="96" y="70" text-anchor="middle" class="a">hdr 12B</text>
  <rect x="136" y="46" width="110" height="40" class="bx"/><text x="191" y="70" text-anchor="middle">payload L1</text>
  <rect x="252" y="46" width="80" height="40" class="bxa"/><text x="292" y="70" text-anchor="middle" class="a">hdr 12B</text>
  <rect x="332" y="46" width="110" height="40" class="bx"/><text x="387" y="70" text-anchor="middle">payload L2</text>
  <rect x="448" y="46" width="80" height="40" class="bxa"/><text x="488" y="70" text-anchor="middle" class="a">hdr 12B</text>
  <rect x="528" y="46" width="110" height="40" class="bx"/><text x="583" y="70" text-anchor="middle">payload L3</text>
</g>
<text x="40" y="118" class="s">解析循环：读 12B 头 → 校 hdrCrc8 → 读 payloadLen 字节 → 下一帧，直至包尾（框架错误处理见 §8）</text>
<text x="40" y="158" class="t">② 分片：偶发大消息（payload &gt; MTU-12）切 N 片，每片独占一包，同 msgId 重组</text>
<g class="m">
  <rect x="40" y="172" width="230" height="56" class="bx"/>
  <text x="155" y="196" text-anchor="middle">frag0：hdr + chunk0</text>
  <text x="155" y="214" text-anchor="middle" class="ms">flags=0x03 idx=0/3</text>
  <rect x="290" y="172" width="230" height="56" class="bx"/>
  <text x="405" y="196" text-anchor="middle">frag1：hdr + chunk1</text>
  <text x="405" y="214" text-anchor="middle" class="ms">flags=0x03 idx=1/3</text>
  <rect x="540" y="172" width="230" height="56" class="bxa"/>
  <text x="655" y="196" text-anchor="middle" class="a">frag2：hdr + chunk2</text>
  <text x="655" y="214" text-anchor="middle" class="ms">flags=0x02 idx=2/3（末片 MORE=0）</text>
</g>
<g class="ms">
  <text x="155" y="244" text-anchor="middle">notification #k</text>
  <text x="405" y="244" text-anchor="middle">notification #k+1</text>
  <text x="655" y="244" text-anchor="middle">notification #k+2</text>
</g>
<text x="40" y="274" class="s">三片共享同一 msgId 与相同 msgType/fragCount；App 按 msgId 缓冲、集齐 fragCount 片后按 fragIndex 升序拼接 payload 再解码；</text>
<text x="40" y="292" class="s">乱序允许、重复片忽略；缺片超时 2s 丢弃整条计丢包。高频批包不应分片——用 SetPacking 把单批压进单包。</text>
</svg>
</div>

### 6.1 一包多帧（SPEC §4.5）

一个 notification 含多个**非分片**帧：`[hdr|payload][hdr|payload]...` 按 `payloadLen` 顺序切分至包尾。解析器强制循环：读 12B 头 → 校验 `hdrCrc8` → 读 `payloadLen` 字节 → 下一帧。**分片帧（FRAGMENTED=1）必须独占整包，不参与拼接。**

### 6.2 分片与重组（SPEC §4.4）

- **定位**：只给偶发大消息（如 `DeviceCapability`）；高频批包应通过 `SetPacking` 把单批控制到 ≤ 单包可用空间。
- **触发**：payload > `MTU − 12`（带 payload CRC 再 −2）→ 切 N 片。
- **分片侧**：所有片共享同一 `msgId`，携带相同 `msgType`/`fragCount`；`fragIndex = 0..N-1`；`FRAGMENTED=1`；前 N-1 片 `MORE_FRAGMENTS=1`，末片为 0。
- **重组侧**：按 `msgId` 缓冲，集齐 `fragCount` 片后按 `fragIndex` 升序拼接，再交 protobuf 解码。消歧规则见 §8 状态机。

## 7. Example raw packet + decode

> 以下字节按本规格实算生成（protobuf wire 编码 + CRC-8 实算），并经解析器自回验（CRC 校验、重组一致性、逐字段解码全部断言通过）。生成脚本存档见文末 §12。

### 7.1 示例 1：HighFreqBatch（ACC，50 Hz，4 样本 × 3 通道 S16）

```
Raw notification (57B):
00 01 08 00 01 5B 12 00 09 00 2D 00 08 03 10 C0 84 3D 18 80 DA C4 09 20 04
28 03 30 02 38 07 42 18 10 00 E0 FF E8 03 11 00 E2 FF EA 03 12 00 E4 FF E7
03 14 00 E7 FF E4 03

Header (12B)                          Decoded:
  00                                  ver        = 0x00 (v0)
  01                                  msgType    = 0x01 HIGH_FREQ_BATCH
  08                                  flags      = 0x08 (BATCH)
  00 01                               fragIndex=0, fragCount=1（非分片）
  5B                                  hdrCrc8    = 0x5B ✓（覆盖其余 11B）
  12 00                               pktSeq     = 18 (u16 LE)
  09 00                               msgId      = 9
  2D 00                               payloadLen = 45

Payload = HighFreqBatch (45B, protobuf):
  08 03                               (1) sensor_id        = 3 → SENSOR_ACC
  10 C0 84 3D                         (2) base_device_ts_us= 1000000 us
  18 80 DA C4 09                      (3) sample_period_ns = 20000000 → 50 Hz
  20 04                               (4) sample_count     = 4
  28 03                               (5) channel_count    = 3 (x/y/z)
  30 02                               (6) format           = 2 → SF_S16 (w=2B)
  38 07                               (7) batch_seq        = 7
  42 18                               (8) samples, len = 0x18 = 24B
                                          校验: 4*3*2 = 24 ✓
  10 00 E0 FF E8 03                   s0 = ( 16, -32, 1000) @ t=1000000 us
  11 00 E2 FF EA 03                   s1 = ( 17, -30, 1002) @ t=1020000 us
  12 00 E4 FF E7 03                   s2 = ( 18, -28,  999) @ t=1040000 us
  14 00 E7 FF E4 03                   s3 = ( 20, -25,  996) @ t=1060000 us
```

### 7.2 示例 2：Command/SetSampleRate 下行 + 一包双帧回包（Ack + DeviceState）

**App → 设备**（`DUT_WRITE_CHAR` 单次 ATT write；写方向 `pktSeq`/`msgId` 无意义置 0）：

```
Raw write (22B):
00 40 00 00 01 1B 00 00 00 00 0A 00 08 05 1A 06 08 01 10 A8 C3 01

Header: ver=0 msgType=0x40 COMMAND flags=0 frag=0/1 hdrCrc8=0x1B ✓
        pktSeq=0 msgId=0 payloadLen=10
Payload = Command:
  08 05                               (1) cmd_id = 5
  1A 06                               (3) set_sample_rate, len=6
    08 01                                 (1) sensor_id = 1 → SENSOR_PPG
    10 A8 C3 01                           (2) sample_rate_mhz = 25000 → 25 Hz
```

**设备 → App**（一个 notification 装两个非分片帧，**共享 pktSeq=19**）：

```
Raw notification (46B):
00 80 00 00 01 00 13 00 0A 00 02 00 08 05 00 12 00 00 01 F1 13 00 0B 00 14
00 0A 08 08 01 10 01 18 A8 C3 01 0A 08 08 03 10 01 18 D0 86 03

frame[0] hdr: ver=0 msgType=0x80 ACK flags=0 frag=0/1 hdrCrc8=0x00 ✓
              pktSeq=19 msgId=10 payloadLen=2
  payload: 08 05                      (1) cmd_id = 5（回显）
                                      (2) status 未编码 → proto3 默认 0 = OK
frame[1] hdr: ver=0 msgType=0x12 DEVICE_STATE flags=0 frag=0/1 hdrCrc8=0xF1 ✓
              pktSeq=19（同包共享） msgId=11 payloadLen=20
  payload: 0A 08 [08 01 10 01 18 A8 C3 01]
             → (1) SensorState{ sensor_id=1 PPG, enabled=true, current_rate_mhz=25000 }
           0A 08 [08 03 10 01 18 D0 86 03]
             → (1) SensorState{ sensor_id=3 ACC, enabled=true, current_rate_mhz=50000 }
```

> `hdrCrc8=0x00` 是合法校验值；`Ack.status=0` 因 proto3 默认值不上线而缺省——解码器不得把"字段缺失"当错误。

### 7.3 示例 3：DeviceCapability 分片 ×3 与重组

52B 的 `DeviceCapability` payload，假设可用空间 20B/片（如 ATT_MTU=35 → notify 32B − 12B 头）→ 切 3 片，每片独占一包：

```
frag[0] (32B)  flags=0x03 = FRAGMENTED|MORE_FRAGMENTS   fragIndex=0  fragCount=3
00 11 03 00 03 2D 28 00 2C 01 14 00 | 0A 0B 73 37 2D 66 77 2D 31 2E 30 2E 30 12 11 08 01 10 01 1A
                                      hdrCrc8=0x2D ✓  pktSeq=40  msgId=300(0x012C)  len=20

frag[1] (32B)  flags=0x03                               fragIndex=1  fragCount=3
00 11 03 01 03 17 29 00 2C 01 14 00 | 09 A8 C3 01 D0 86 03 A0 8D 06 20 02 12 0E 08 03 10 01 1A 06
                                      hdrCrc8=0x17 ✓  pktSeq=41  msgId=300  len=20

frag[2] (24B)  flags=0x02 = FRAGMENTED（末片 MORE=0）    fragIndex=2  fragCount=3
00 11 02 02 03 DF 2A 00 2C 01 0C 00 | D0 86 03 A0 8D 06 20 03 1A 02 01 02
                                      hdrCrc8=0xDF ✓  pktSeq=42  msgId=300  len=12

按 fragIndex 升序拼接 → DeviceCapability payload (52B)：
0A 0B 73 37 2D 66 77 2D 31 2E 30 2E 30    (1) firmware_version = "s7-fw-1.0.0"
12 11 [08 01 10 01                         (2) SensorCap: sensor_id=1 PPG, high_freq=true
       1A 09 A8 C3 01 D0 86 03 A0 8D 06        supported_rates_mhz(packed) = [25000, 50000, 100000]
       20 02]                                  channel_count = 2
12 0E [08 03 10 01                         (2) SensorCap: sensor_id=3 ACC, high_freq=true
       1A 06 D0 86 03 A0 8D 06                 supported_rates_mhz(packed) = [50000, 100000]
       20 03]                                  channel_count = 3
1A 02 01 02                                (3) algorithm_ids(packed) = [1, 2]
```

### 7.4 示例 4：当前 Mock 链路的包（**非真实协议**，[`MockPacketCodec.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/MockPacketCodec.kt)）

v1 Mock 链路用的简化单样本格式（布局：`[0]=0x7E sync [1]=streamCode [2..5]=deviceTsUs u32 LE [6]=channelCount [7..]=s16 LE × N`）：

```
Raw (13B):  7E 03 38 1A 08 00 03 10 00 E0 FF E8 03
Decoded:    sync=0x7E  streamCode=3 → ACC  deviceTsUs=531000（38 1A 08 00 LE）
            channelCount=3  ch = [16, -32, 1000]
```

streamCode 注册表（代码 `streamByCode`）：1=PPG_G，2=PPG_IR，3=ACC，4=GYRO，5=MAG，6=TEMP，7=HR。协议冻结后由真实 12B 帧格式取代，接口 `SampleDecoder` 不变。

## 8. State machine

### 8.1 单包解帧循环（FrameReader，纯函数）

```
                    ┌──────────────────────── 继续下一帧 ◀──────────────────┐
                    ▼                                                      │
  包起始 ──▶ [剩余字节?] ──=0──▶ 包解析完成                                  │
                │>0                                                        │
                ├─ 剩余 < 12 ────────▶ 框架错误: 丢尾部残余, 计错, 保留前序帧 │
                ▼                                                          │
          [读 12B 头部]                                                     │
                ├─ ver ≠ 0x00 ──────▶ 版本不匹配: 停止解析整包, 上报         │
                ├─ hdrCrc8 失败 ─────▶ 丢弃本帧起余下(payloadLen 不可信), 计错│
                ├─ payloadLen > 剩余 ▶ 丢弃本帧起余下, 计错, 保留前序帧      │
                ▼                                                          │
          [读 payloadLen 字节 payload]                                      │
                ├─ msgType 未知 ─────▶ 跳过 payload, 计数 ──────────────────┤
                ├─ FRAGMENTED=1 ─────▶ 交 Reassembler（§8.2）───────────────┤
                ▼                                                          │
          [protobuf 解码 → 分发] ───────────────────────────────────────────┘
```

### 8.2 分片重组器（Reassembler，有状态，注入式时间源）

```
        frag(FRAGMENTED=1) 到达
                 │
     ┌───────────▼────────────┐   msgId 未在缓冲 / fragIndex==0 的新起始片
     │  EMPTY（无该 msgId）    ├──────────────────────────────┐
     └───────────┬────────────┘                              │
                 │ 开新缓冲(记 msgType/fragCount)              ▼
     ┌───────────▼──────────────────────────────────────────────────┐
     │  BUFFERING（msgId → 已收分片集合）                             │
     │   · 同 msgId 且 (msgType,fragCount) 一致 → 归入（乱序允许，     │
     │     fragIndex 重复且字节相同 → 忽略）                          │
     │   · (msgType,fragCount) 不一致 或 新 fragIndex==0 起始片        │
     │     → 判新一轮: 丢旧缓冲重开                                   │
     │   · fragIndex ≥ fragCount → 丢弃该片, 记日志                   │
     └────┬────────────────────────────────┬────────────────────────┘
          │ 集齐 fragCount 片                │ 缺片超时 2s（注入时钟）
          ▼                                ▼
   按 fragIndex 升序拼接 payload      丢弃整条消息, 计丢包
   → protobuf 解码分发                （不阻塞后续）
          │
          ▼
        EMPTY（msgId 释放; 固件复用 msgId 前须留 > 超时间隔）
```

### 8.3 控制闭环 Command → Ack（含重连对账）

<div class="fig">
<svg viewBox="0 0 840 300" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>Command/Ack 控制闭环与重连后 QueryState 对账时序</title>
<desc>App 与设备两条泳道：App 写 Command(cmd_id=5)，设备回 Ack(cmd_id=5) 与 DeviceState；断连重连后 App 发 QueryState，设备回 DeviceState，App 与 manifest/UI 对账。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.lane{stroke:var(--line);stroke-width:1.2;}
.fl{stroke:var(--accent);stroke-width:1.6;}
.fl2{stroke:var(--muted);stroke-width:1.4;}
</style>
<defs>
<marker id="ara" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--accent)"/></marker>
<marker id="arm" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--muted)"/></marker>
</defs>
<g class="m">
  <rect x="80" y="16" width="180" height="34" class="bx"/><text x="170" y="38" text-anchor="middle">App (Central)</text>
  <rect x="580" y="16" width="180" height="34" class="bx"/><text x="670" y="38" text-anchor="middle">DUT 设备</text>
</g>
<line x1="170" y1="50" x2="170" y2="286" class="lane"/>
<line x1="670" y1="50" x2="670" y2="286" class="lane"/>
<line x1="170" y1="76" x2="670" y2="76" class="fl" marker-end="url(#ara)"/>
<text x="420" y="68" text-anchor="middle" class="m a">Write: Command{cmd_id=5, SetSampleRate(PPG, 25000mHz)}</text>
<line x1="670" y1="112" x2="170" y2="112" class="fl" marker-end="url(#ara)"/>
<text x="420" y="104" text-anchor="middle" class="m a">Notify: Ack{cmd_id=5, status=0}</text>
<line x1="670" y1="146" x2="170" y2="146" class="fl2" marker-end="url(#arm)"/>
<text x="420" y="138" text-anchor="middle" class="m">Notify: DeviceState（生效值，含被夹紧的速率）</text>
<g class="s">
  <text x="60" y="176">── 断连 / 进程被杀 → 重连（pktSeq 重置，丢包统计 resync）──</text>
</g>
<line x1="60" y1="182" x2="780" y2="182" class="lane" stroke-dasharray="5 4"/>
<line x1="170" y1="212" x2="670" y2="212" class="fl2" marker-end="url(#arm)"/>
<text x="420" y="204" text-anchor="middle" class="m">Write: Command{cmd_id=6, QueryState}</text>
<line x1="670" y1="246" x2="170" y2="246" class="fl2" marker-end="url(#arm)"/>
<text x="420" y="238" text-anchor="middle" class="m">Notify: Ack{cmd_id=6} + DeviceState</text>
<text x="170" y="274" class="s">与本地 manifest/UI 对账——按设备实际生效配置为准，不靠"上次发了什么"猜</text>
</svg>
</div>

- 指令可靠性 = `cmd_id` 回显闭环（写方向不用 `pktSeq`/`NEEDS_ACK`）；指令与 ACK 都进会话 manifest，`→ CMD / ← ACK` 日志即"运行中控制面板"。
- **重连/进程恢复**：重连后发 `QueryState` 读回**实际生效配置**（含被夹紧值）对账。

### 8.4 丢包统计与 pktSeq resync（SPEC §4.9）

```
gap = (cur - prev) & 0xFFFF        （pktSeq 包级；batch_seq 流级同理，模 2^32）
gap == 1        → 正常
1 < gap < 0x8000 → 丢了 gap-1 个包，计入丢包
gap ≥ 0x8000    → 视为重连/重订阅 resync：重建基线，不当作海量丢包
初值 0；(re)subscribe 后首包只建基线不计丢；pktSeq 每连接重置。
```

## 9. Error Handling 汇总

| 错误 | 检测点 | 处理 |
| --- | --- | --- |
| `ver` 不匹配 | 帧头 byte 0 | **停止解析整个包**（头布局都不可信），上报版本不匹配；不得强行误解码 |
| 包尾残余 < 12B（>0） | 解帧循环 | 丢弃残余，计框架错误，保留本包已解析帧 |
| `hdrCrc8` 校验失败 | 帧头 byte 5 | 丢弃**本帧起的整个剩余包**（`payloadLen` 不可信会错位误切），计错，保留前序帧 |
| `payloadLen` > 剩余字节 | 帧头 byte 10–11 | 丢弃本帧起余下，计错，保留前序帧 |
| 未知 `msgType` | dispatch | 按 `payloadLen` **跳过并继续**，计数诊断；绝不中止整包 |
| payload CRC16 失败（可选启用） | payload 末 2B | 丢弃该帧 payload，计错 |
| `samples` 长度 ≠ `count*channel*width` | HighFreqBatch 解码 | 丢弃该批计错，**不抛异常打断管线** |
| `SF_UNSPECIFIED` / 未知 format | 同上 | 该批按解码错误处理 |
| 分片缺片超时（2 s） | Reassembler | 丢弃整条消息计丢包，不阻塞后续 |
| `fragIndex ≥ fragCount` | Reassembler | 丢弃该片，记日志 |
| `pktSeq` / `batch_seq` 缺口 | 统计层 | 模运算计丢包；gap ≥ 0x8000 按 resync 处理（§8.4） |

## 10. Version Compatibility

- **v0.1（当前，`ver=0x00`）**：草案基线，本文全部内容；冻结为 v1 时再定 `ver` 值。
- 同一 major `ver` 内 **12B 头布局冻结**；payload 仅允许 proto3 增量演进（只增不改 tag、删字段用 `reserved`、未知字段忽略）。
- **加一个传感器**：分配 `SensorId` + 复用 `HighFreqBatch`/`LowFreqFrame`；或用保留区新增 `msgType` + 新 message + 解码器注册一行。旧端靠"未知 msgType 跳过"保持兼容。
- 破坏性变更（改 12B 头布局）必须换 `ver`；接收方 `ver` 不匹配即停止解析（§9）。
- **待冻结清单**（动工前与固件逐项拍板）：字节序、`ver` 终值、CRC-8 参数（含反射约定）、S24 是否紧密 3B、汇顶 bytes 块内布局、`SensorId`/`algorithm_id`/`metric_id` 枚举、GATT UUID、Notify vs Indicate、批是否保证单包、`pktSeq` 重置语义、时钟偏移建立方式——全表见 [归档协议文档 §15](../归档/历史规格_v1-v3/BlueTrace_Protocol.md) 与 SPEC §10.4。

## 11. 代码实现现状（2026-07-06，main）

| 组件 | 文件 | 状态 |
| --- | --- | --- |
| 帧头模型 + `MsgType` 注册表 + flags 常量 | [`FrameHeader.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/FrameHeader.kt) | **v1 桩**：类型与常量已对齐本规格；解析/CRC/重组待协议冻结后实现 |
| 解码器接口 | [`SampleDecoder.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/SampleDecoder.kt) | 接口已定型；上层只依赖它，冻结后换 `WireSampleDecoder` 上层不动 |
| Mock 编解码（当前实际链路） | [`MockPacketCodec.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/MockPacketCodec.kt) | 已实现 + 单测；格式见 §7.4，**只属 Mock** |
| payload schema | [`bluetrace_v0.proto`](bluetrace_v0.proto) | v0.1 草案，单一事实源（D-10） |

协议冻结后的目标解码管线（SPEC / 归档 §14）：

```
BLE bytes → FrameReader（纯函数, §8.1）→ Reassembler（有状态, §8.2）
          → dispatch(msgType) → Wire 解码 message → toSamples()（§5.1 长度校验）
          → OutputRouter（1 消息类型 → 1..N CSV Sink）
```

全程纯 Kotlin commonMain（KMP-ready）；`FrameReader` JVM 单测全覆盖，`Reassembler` 注入时间源以便确定性测试超时。

## 12. 引用

- 规格真源：[SPEC.md §4 通信协议](../../SPEC.md)；payload 权威：[`bluetrace_v0.proto`](bluetrace_v0.proto)
- 历史全文（含 §15 待冻结清单、§16 评审修订记录）：[`Do../归档/历史规格_v1-v3/BlueTrace_Protocol.md`](../归档/历史规格_v1-v3/BlueTrace_Protocol.md)
- 时间戳/对齐与 hexlog 重放：SPEC §6.1/§6.3（原始 HEX 为 source of truth，逐行 `<epochMs>: <HEX>` 可重放）
- 示例包生成脚本（protobuf wire 编码 + CRC-8 实算 + 自回验）：[`assets/gen_frame_examples.py`](assets/gen_frame_examples.py)
