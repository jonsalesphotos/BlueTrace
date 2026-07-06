# BlueTrace 设备通信协议 v0.1（草案）

> **这份文档定义 DUT（自研设备）与 app 之间的私有通信协议**：BLE GATT 之上的标准头部、分片/重组、消息类型注册表、protobuf payload 编码。
> 决策依据见 [REQUIREMENTS.md](../../REQUIREMENTS.md) §10 与 D-4/D-5/D-8/D-9/D-10；Android 架构见 [BlueTrace_Architecture.md](BlueTrace_Architecture.md)；权威 schema 见同目录 [`bluetrace_v0.proto`](bluetrace_v0.proto)。
>
> **适用范围**：仅 DUT 这套自研协议。REFERENCE（标准心率带）走 SIG Heart Rate Service（0x180D），**不**用本协议。
>
> **状态**：v0.1 草案（已过一轮多视角评审，修订见 §16），待与固件端共同冻结（双端同团队）。待对齐项汇总在 §15。

| 项 | 内容 |
| --- | --- |
| 文档版本 | v0.1（草案） |
| 协议头 `ver` | `0x00`（冻结为 v1 时再定值） |
| 字节序 | **小端（little-endian）** ⚠️ 待固件确认 |
| 传输 | BLE GATT，app 作 Central；设备→app 走 Notify，app→设备 走 Write |
| payload 编码 | protobuf（proto3，Wire 生成）；高频样本走紧凑 `bytes` |
| 解码层 | KMP commonMain，纯 Kotlin（[REQUIREMENTS](../../REQUIREMENTS.md) KMP-ready 约束） |

---

## 0. 设计目标（为什么这样设计）

1. **标准头部 protobuf 给不了** —— protobuf 没有"包类型/长度/分片/校验"这种框架信息，所以最外层必须有一个自定义二进制头部做类型分发、长度界定、分片重组、框架自检。这是 TLV 思路的落地（T=msgType，L=payloadLen，V=protobuf）。
2. **BLE 带宽有限** —— 高频海量样本（PPG/ECG/IMU 上百 Hz）不逐样本上 protobuf tag，必须紧凑打包（§7）。
3. **单包装不下大消息** —— 大消息超过单包（MTU 由设备按功耗定，app 不可控）必须支持**分包/组包**（§4）。
4. **小消息要省包** —— 多条小消息可拼进一个 BLE 包（§5）。
5. **可扩展** —— 加传感器 = 加一个消息类型 + 一个 message，解码器注册一行；旧端遇到新类型须能安全跳过（§6、§13）。
6. **可回溯** —— 原始字节落 hexlog，schema 变了也能重放（§12）。

## 1. 分层模型

```text
解码后样本 (SensorSample / Flow<T>)        ← 上层只见强类型样本
        ▲  Wire 解码
protobuf payload (V)                       ← 每类消息一个 message
        ▲  按 msgType 分发 + 组包还原
标准头部 (ver + T + 长度 + 分片 + CRC)      ← 本协议核心，二进制
        ▲  帧拼接 / 分片
BLE GATT notification / write              ← 传输，MTU 由设备定
```

一个 **帧 (Frame)** = `标准头部(12B) + payload(payloadLen 字节)`。一个 BLE 包（notification）内可含 1..N 个非分片帧；一条大消息拆成多个分片帧跨多个 BLE 包。

## 2. GATT 传输层

| 角色 | Characteristic | 方向 | 属性 |
| --- | --- | --- | --- |
| 数据流 | `DUT_NOTIFY_CHAR` | 设备→app | Notify |
| 控制 | `DUT_WRITE_CHAR` | app→设备 | Write / WriteWithoutResponse |

- UUID 占位在架构的 `DutUuids`，冻结时替换。
- app **不主动调链路参数**（连接间隔、MTU 全由设备按功耗申请，见 D-5）；app 只订阅 Notify、解帧、解码。
- **写方向（app→设备）v0 不做协议级分片**：每条 `Command` 必须能装进单次 ATT write（受 `peripheral.maximumWriteValueLength(type)` 约束）；固件收到的是 GATT 层重组后的**单个完整 ATT value**，不含 §4 的 msgId/fragIndex 分片头。`Command` 的可靠性靠 `cmd_id` + `Ack` 闭环，写方向不使用 `pktSeq`/`NEEDS_ACK`。控制指令体很小，此约束当前无碍；若将来出现大指令，再对称引入写方向分片（版本升级）。

## 3. 标准头部（固定 12 字节，小端）

每个帧以 12 字节头部起始：

| 偏移 | size | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 1 | `ver` | 协议版本，v0 = `0x00`。**不匹配时按 §13 处理（停止解析该包）** |
| 1 | 1 | `msgType` | 消息类型，见 §6 注册表 |
| 2 | 1 | `flags` | 位标志，见下 |
| 3 | 1 | `fragIndex` | 分片序号 0..fragCount-1（非分片=0） |
| 4 | 1 | `fragCount` | 分片总数（非分片=1） |
| 5 | 1 | `hdrCrc8` | **强制**：头部 CRC8（覆盖头部除本字节外 11 字节），见 §11 |
| 6 | 2 | `pktSeq` | **包序号**（小端 uint16），每个 BLE notification +1，模 2^16 回绕；同一包内多帧携带**相同** pktSeq |
| 8 | 2 | `msgId` | 逻辑消息 ID（小端 uint16），同一消息的分片共享，用于组包 |
| 10 | 2 | `payloadLen` | 本帧 payload 字节数（小端 uint16） |

**flags 位定义**：

| bit | 名称 | 含义 |
| --- | --- | --- |
| 0 | `MORE_FRAGMENTS` | 还有后续分片（最后一片为 0） |
| 1 | `FRAGMENTED` | 本帧属于一条分片消息 |
| 2 | `NEEDS_ACK` | 需要对端回 ACK（保留；v0 控制走 cmd_id/Ack，见 §2） |
| 3 | `BATCH` | 1=高频批包 / 0=低频组帧（语义提示，可选） |
| 4 | `HAS_PAYLOAD_CRC` | payload 末尾 2 字节为 CRC16（见 §11） |
| 5–7 | 保留 | 置 0 |

> 头部 12 字节对高频批包（一包携带几十~几百样本）是可忽略开销。`fragIndex/fragCount` 为 uint8 → 单消息最多 255 片。

## 4. 分片与重组（分包 / 组包）

**前提**：分片是给**偶发大消息**（如 `DeviceCapability`）用的，**不是给稳态高频批包用的**。高频批包应通过 `SetPacking` 把 `batch_samples` 选到**单批 ≤ 单包可用空间**（见 §7），避免每批都分片导致"所有传感器排在一个大批后面串行、抬高延迟"。若某批确实超包，可分片，但需接受延迟代价。

当一条逻辑消息的 payload 超过 `MTU − 12（− 2 若带 payload CRC）` 时，固件切成 N 片：

- 所有分片共享同一 `msgId`，且**必须携带相同的 `msgType` 与 `fragCount`**；`fragIndex = 0..N-1`，`FRAGMENTED=1`；
- 前 N-1 片 `MORE_FRAGMENTS=1`，最后一片 `MORE_FRAGMENTS=0`；
- **一个分片独占一个 BLE 包**（分片帧不与其它帧拼接，简化重组）；
- app 侧按 `msgId` 缓冲，集齐 `fragCount` 片后**按 fragIndex 升序拼接 payload**，再交 protobuf 解码。

**重组规则（消除歧义）**：
- 收到带在缓冲中 `msgId` 的分片：若其 `(msgType, fragCount)` 与缓冲一致 → 归入当前消息（**乱序允许**；`fragIndex` 已存在且字节相同的**重复片忽略**）；
- 若 `(msgType, fragCount)` 与缓冲**不一致**，或收到 `fragIndex==0` 的新起始片 → **判定为新一轮**：丢弃旧缓冲、以新片重新开始；
- 某片缺失超时（建议 **2 s**）→ 丢弃整条消息，计入丢包（F-COL-3），不阻塞后续；
- `fragIndex ≥ fragCount` → 丢弃并记日志；
- `msgId` 仅需在重组窗口内唯一；固件复用 `msgId` 前应留足时间间隔（> 重组超时），避免迟到分片串到错误消息。

## 5. 一包多帧（拼接）规则

一个 BLE notification 可含多个**非分片**帧，把多条小消息合进一包省带宽：

```
[hdr|payload][hdr|payload][hdr|payload] ...   （按 payloadLen 顺序切分，直到包尾）
```

**解析器规则（强制，消除歧义）**：在一个包内循环——读 12B 头 → 校验 `hdrCrc8`（失败按下条）→ 读 `payloadLen` 字节 payload → 下一帧，直至耗尽。其中：
- 若**剩余字节 < 12**（读不出完整头）且剩余 > 0 → **框架错误**：丢弃尾部残余、计错误（F-COL-3），**保留**本包内已成功解析的前序帧；
- 若 `payloadLen` **大于头之后剩余字节** → **框架错误**：丢弃本包从该帧起的剩余、计错误，保留前序帧；
- 若 `hdrCrc8` 校验失败 → 该帧不可信，**丢弃本包从该帧起的剩余**（无法相信其 payloadLen）、计错误，保留前序帧；
- **分片帧（FRAGMENTED=1）必须独占整包**，不参与拼接；拼接帧与分片帧不混在同一包。

> 强制 `hdrCrc8` + 长度自检：防止一个坏 `payloadLen` 把整包后续帧全部错位误切（错 msgType 分发、错重组）。

## 6. 消息类型注册表（header `msgType`）

`msgType` 决定 payload 用哪个 protobuf message 解（dispatch 表），protobuf 内**不再包 envelope**（省 tag 开销）。

| msgType | 名称 | 方向 | message | 说明 |
| --- | --- | --- | --- | --- |
| `0x01` | HIGH_FREQ_BATCH | 设备→app | `HighFreqBatch` | PPG/ECG/IMU/ACC 高频批包 |
| `0x02` | LOW_FREQ_FRAME | 设备→app | `LowFreqFrame` | 地磁/温度低频组帧 |
| `0x03` | GOODIX_PPG_ACC | 设备→app | `GoodixPpgAcc` | 汇顶 PPG+ACC 组合包（→ 兼容 CSV F-FILE-7） |
| `0x10` | DEVICE_EVENT | 设备→app | `DeviceEvent` | 设备事件/告警 |
| `0x11` | DEVICE_CAPABILITY | 设备→app | `DeviceCapability` | 静态能力：传感器集 + 采样率 + 算法（F-CTRL-1/2 真理源） |
| `0x12` | DEVICE_STATE | 设备→app | `DeviceState` | 当前状态：启用项/生效采样率/活跃算法（重连对账，F-BG-4） |
| `0x20` | ALGO_RESULT | 设备→app | `AlgoResult` | 设备端算法结果（F-CTRL-3） |
| `0x40` | COMMAND | app→设备 | `Command` | 控制指令 |
| `0x80` | ACK | 设备→app | `Ack` | 指令回执 |
| `0x04–0x0F` | 保留·数据 | | | 新增传感器消息 |
| `0x41–0x7F` | 保留·控制 | | | |
| `0xC0–0xFF` | 厂商/实验 | | | 私有扩展 |

**未知 / 保留 msgType 的强制处理**：接收方遇到不在自己 dispatch 表中的 `msgType`，**必须用 `payloadLen` 跳过该帧 payload 并继续解析本包余下帧**（同时计数用于诊断），**绝不**因此中止整包。这是"加传感器不破坏旧端"的前提。

## 7. payload 编码策略（混合，D-8）

**高频批包**（`HighFreqBatch` / `GoodixPpgAcc`）：protobuf 头（sensorId / 设备时间戳 / 采样周期 / count / 通道数 / 格式 / 批序号）+ 一个 `bytes` 字段装**紧凑定宽样本数组**——避免逐样本 protobuf tag 开销。

**`samples` 字节布局**（行主序，小端，紧密无填充）：
```
sample[0]ch[0], sample[0]ch[1], ..., sample[0]ch[C-1],
sample[1]ch[0], ...                                     共 sample_count * channel_count 个值
```

**每值字节宽度（强制表）**：

| SampleFormat | 字节 | 解码 |
| --- | --- | --- |
| SF_U16 / SF_S16 | 2 | 小端；S16 二补码 |
| SF_U24 / SF_S24 | 3 | 小端；**S24 须按 bit23 符号扩展**：`v = b0|b1<<8|b2<<16; signed = (v ^ 0x800000) - 0x800000`（测试向量：`0xFFFFFF → -1`）。U24 零扩展 |
| SF_S32 / SF_F32 | 4 | 小端；F32 = IEEE754 |

- **长度校验（强制）**：`samples.length` 必须 == `sample_count * channel_count * width(format)`，否则**丢弃该批并计错误**（不向上抛异常打断管线）。
- `SF_UNSPECIFIED` 与未知 format 值 → 该批按解码错误处理。
- **第 i 个样本设备时间** = `base_device_ts_us + round(i * sample_period_ns / 1000)`（用纳秒周期而非整数 Hz，避免 12.5Hz 这类小数率长会话累积漂移）。

**低频 / 控制 / 事件 / 能力 / 状态**：常规结构化 protobuf message；低频标量数组用 `packed` repeated。

## 8. protobuf 定义

> 权威副本：[`bluetrace_v0.proto`](bluetrace_v0.proto)（D-10 单一事实源）。本节不再内联整份 schema，避免与权威文件漂移——**以 `.proto` 为准**。关键消息一览：
>
> - **数据**：`HighFreqBatch`(0x01) · `LowFreqFrame`/`LowFreqReading`(0x02) · `GoodixPpgAcc`(0x03，PPG/ACC 各带独立 period/count/channel/format) · `DeviceEvent`(0x10) · `DeviceCapability`/`SensorCap`(0x11) · `DeviceState`/`SensorState`(0x12) · `AlgoResult`/`AlgoMetric`(0x20)
> - **控制**：`Command`(0x40) = oneof{ SetSensorEnable, SetSampleRate, SelectAlgorithm, SetPacking, SessionControl, QueryCapability, QueryState }
> - **回执**：`Ack`(0x80)
> - **枚举**：`SampleFormat`、`SensorId`
>
> 速率统一用**毫赫兹**（`*_mhz`）以支持小数率；高频计时用 `sample_period_ns`。

## 9. 时间戳与对齐

| 时间 | 来源 | 用途 |
| --- | --- | --- |
| 设备时间 `*_device_ts_us` | 设备单调时钟（payload 内） | 批内/帧内样本精确间隔 |
| 样本周期 `sample_period_ns` | payload 内 | 每样本时间内插（§7） |
| `receivedAtEpochMillis` | app 收到包墙钟（ms） | 落 CSV / hexlog 行时间 |
| `monotonicNanos` | app `System.nanoTime()` | 跨路/跨设备对齐 |
| `startMonotonicNanos` | 会话 manifest 锚点 | 共同时间轴：`(mono − t0)/1e9` 秒 |

会话开始用首包估计设备时钟与 app 时钟偏移，后续用设备时间补内插、app monotonic 锚点做多源对齐。hexlog 行时间用**毫秒 epoch**（D-6）。

## 10. 双向控制与 ACK

- app 下发 `COMMAND`（带 `cmd_id`），设备执行后回 `ACK(cmd_id, status)`。覆盖：开关传感器（F-CTRL-1）、采样率（F-CTRL-2）、设备端算法（F-CTRL-3）、应用层打包旋钮（F-CTRL-4/D-9）、会话启停、能力查询、**当前状态查询**（重连对账）。
- **重连/进程恢复**：app 重连后发 `QueryState` 读回设备**实际生效配置**（含被夹紧值）→ 与本地 manifest/UI 对账（F-BG-4），不靠"自己上次发了什么"猜。
- 指令日志（→ CMD / ← ACK）即 UX"运行中控制面板"（F-CTRL-5）；指令与 ACK 都进会话 manifest（关键规则 6）。

## 11. 完整性与丢包

- **头部框架自检（强制）**：`hdrCrc8` = CRC-8（poly `0x07`、init `0x00`）覆盖头部 11 字节（偏移 0–4、6–11，跳过 hdrCrc8 自身）。失败按 §5 丢弃。
- **payload 完整性（可选）**：`flags.HAS_PAYLOAD_CRC=1` 时，payload 末尾 2 字节为 CRC16/CCITT（覆盖前 `payloadLen-2` 字节），protobuf 实际长度 = `payloadLen-2`。BLE 链路层已对**空口位错**做 CRC（控制器内消费、app 读不到），故 payload CRC 仅在需要端到端校验/排错时开启。
- **F-COL-3 的"CRC"指标**：展示 **hdrCrc8 通过率**（恒可得）；开启 payload CRC 时叠加其通过率。
- **丢包检测**：
  - `pktSeq`（**包级**，统计 `DUT_NOTIFY_CHAR` 上所有 notification）：缺口 = BLE 包丢失；
  - `HighFreqBatch.batch_seq`（**流级**，按 `sensor_id`）：缺口 = 该传感器批丢失；
  - 两者**初值 0**，(re)subscribe 后**首包建立基线**（首包不计丢）；
  - 缺口判定用**模运算**：`gap = (cur - prev) & 0xFFFF`（pktSeq）；`gap` 超过阈值（建议 `0x8000`）视为**重连/重订阅 resync**，不当作海量丢包；
  - **reconnect 语义**：`pktSeq` 为**每连接**计数，重连后重置 → app 见到大跳变按 resync 处理，重建基线。
- **分片缺失**：超时丢弃整条消息并计丢包（§4）。

## 12. 原始日志（hexlog）与重放

- 每来源一份 `raw_<role>.hexlog`，每行：`<epochMs>: <HEX>\n`，HEX 是该 BLE notification 的**原始字节**（含头部，含可能拼接的多帧）。
- **可重放**：把 hexlog 逐行喂回"帧拼接 + 分片重组 + 解码"管线，schema/解码逻辑变更后可重新生成 CSV——这是选 hexlog 作主体的核心价值（D-6）。
- 后期接流式压缩（zstd），格式不变。

## 13. 版本与演进

- **`ver` 不匹配处理（强制）**：接收方比对来包 `ver` 与自身实现；**不一致则停止对该包的帧级解析**（破坏性变更会改 12B 头布局，连 `msgType/payloadLen` 偏移都不能信），上报"协议版本不匹配"错误，**不得**强行误解码。
- 同一 major `ver` 内 **12B 头布局冻结**，仅允许 proto3 增量演进（只增不改 tag、删除字段 `reserved`、未知字段忽略）。
- **加一个传感器**：分配 `SensorId` + 复用 `HighFreqBatch`/`LowFreqFrame`，或新增 `msgType`（保留区）+ 新 message + 解码器注册一行；旧端按 §6 跳过未知 msgType。

## 14. 解码器实现要点（commonMain / Wire）

```text
BLE bytes
  → FrameReader      （纯函数）单包按 payloadLen + hdrCrc8 切帧，含 §5 框架错误处理
  → Reassembler      （有状态）按 msgId 缓冲分片，集齐还原（§4）
  → dispatch(msgType)→ Wire decode 对应 message（§6/§8），未知类型跳过
  → toSamples()      protobuf → List<SensorSample>（高频 bytes 按 §7 解包 + 长度校验）
  → OutputRouter     1 消息类型 → 1..N Sink（普通模块 CSV + 组合兼容 CSV，F-FILE-7）
```

- **FrameReader**：纯/无状态字节函数，输入一个 notification 字节，输出帧列表 + 错误计数；JVM 单测全覆盖。
- **Reassembler**：**有状态**状态机，持 `msgId → 部分缓冲`，按 §4 规则增删；**注入式时间源**（`tick(nowMs)` 或传入 monotonic now）以便 2 s 超时/LRU 可被确定性单测——**不是无状态**。
- **toSamples**：高频批严格做 `samples.length == count*channel*width` 校验，失败计错误、不抛穿管线；`SF_UNSPECIFIED`/未知 format 视为该批解码错误。
- 全程纯 Kotlin、无 Android 依赖（KMP-ready）。

## 15. 与固件待冻结清单（动工前对齐）

| 项 | 默认/草案 | 需固件确认 |
| --- | --- | --- |
| 字节序 | 小端 | ✅ |
| `ver` 值、头部 12B 布局 | 如 §3 | ✅ |
| `hdrCrc8` 多项式 | CRC-8 poly 0x07 / init 0x00 | ✅ |
| `fragIndex/fragCount` 宽度 | uint8（≤255 片） | 是否够 |
| 各传感器 `SampleFormat` + 通道数 + 典型采样率 | 占位 | ✅ 依真实传感器 |
| S24/U24 是否 4 字节对齐 | **否**（紧密 3 字节） | ✅ 核对硬件 FIFO 约定 |
| 汇顶 PPG+ACC 各 `bytes` 块内布局 | 占位 | ✅ |
| `SensorId` / `algorithm_id` / `metric_id` 枚举值 | 草案 | ✅ |
| GATT UUID（service / notify / write） | `DutUuids` 占位 | ✅ |
| Notify vs Indicate | Notify | ✅ |
| 批是否保证单包内（`batch_samples` 上限） | 建议保证 | ✅ |
| `pktSeq` 是否每连接重置 | 是 | ✅ |
| 设备时钟与对齐偏移建立方式 | 首包估计 | ✅ |

## 16. 评审修订记录

**v0.1（基于 v0 草案的多视角评审，2026-06-15）**：
- 头部 `seq` → `pktSeq`，明确**每 BLE 包**计数、同包多帧共享、模 2^16、每连接重置 + resync 阈值；`HighFreqBatch.seq` → `batch_seq`（流级），定义两计数器各自域与丢包语义（F-COL-3 可量化）。
- §5 增**强制框架错误处理**（残余<12、payloadLen 超界、hdrCrc8 失败）；`hdrCrc8` 由可选改**强制**，给 F-COL-3 一个可显示的 CRC 来源；新增可选 payload CRC16。
- §7 增 `SampleFormat` **字节宽度表** + S24/U24 **符号扩展规则**（含测试向量）+ `samples` **长度校验**；`SF_UNSPECIFIED` 非法。
- 高频计时由整数 Hz 改 `sample_period_ns`（消除小数率漂移）；config 速率统一**毫赫兹** `*_mhz`。
- `GoodixPpgAcc` 给 PPG/ACC **各自独立** period/count/channel/format（F-FILE-7 可解）。
- 新增 `DEVICE_STATE`/`DeviceState` + `QueryState`：重连/恢复读回**当前生效配置**（F-BG-4、关键规则 6）。
- `AlgoResult` 裸 `float[]` 改 `repeated AlgoMetric{metric_id,value}`（F-CTRL-3 可命名列）。
- `LowFreqReading.scale_milli`：定义 **==0 表示不缩放**（避免 proto3 零默认清零）。
- §13 定义 **`ver` 不匹配**与**未知 `msgType` 跳过**的强制语义（扩展性闭环）。
- §14 纠正：`Reassembler` 为**有状态**状态机（注入式时间源），仅 `FrameReader` 为纯函数。
- §2 明确**写方向不做协议级分片**（Command 须单写装下，靠 cmd_id/Ack）。
- §8 不再内联整份 schema，以 `.proto` 为唯一权威（消除 doc/.proto `packed` 等漂移）。

---

> **下一步**：固件与 app 双方对 §15 逐项拍板 → 冻结为 v1 → 锁定 [`bluetrace_v0.proto`](bluetrace_v0.proto) → P2 据此实现解码器、固件据此出帧。
