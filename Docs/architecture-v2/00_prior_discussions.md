# 00 · 历史讨论盘点（截至 2026-07-02）

> 目的：回答「这个项目有没有原先的讨论」。逐主题给出**已有结论的出处**与**空白点**，
> 新设计（02/03/04）在此基础上演进，不推翻已评审结论。

## 一句话总览

**协议信封（TLV 12B 头 + protobuf payload）与在线流式采集消息已有 v0.1 草案且过了一轮多视角评审；
「离线采集回传」「S7 等外部协议接入」「手表控制界面」三个主题没有任何既有设计，属本轮新增。**

## 逐主题盘点

### 1. S7 项目 / 质量通信协议 —— ❌ 未找到

全仓库（代码、Docs 全目录、git log）无「S7」相关任何讨论。S7 协议接入是**全新需求**，由 [02_parser_registry_design.md](02_parser_registry_design.md) 承接。

### 2. IMU / 传感器采集 —— ✅ 已有大量设计（仅在线方向）

| 出处 | 结论 |
|------|------|
| `SPEC.md §4` | 自研 DUT 私有协议分层：BLE GATT ← 标准 12B 头部 ← protobuf payload；高频批包 `HighFreqBatch`（紧凑 bytes）+ 低频组帧 `LowFreqFrame`（结构化 protobuf）双编码（决策 D-8） |
| `Docs/architecture/bluetrace_v0.proto` | 权威 schema（D-10 单一事实源）：`HighFreqBatch / LowFreqFrame / GoodixPpgAcc / DeviceEvent / DeviceCapability / DeviceState / AlgoResult / Command / Ack`；`SensorId` 含 ACC/GYRO/MAG（IMU 三件套）；速率单位毫赫兹支持小数率 |
| `Docs/legacy/BlueTrace_Protocol.md` | v0.1 完整协议规范（已过评审待冻结）：采样周期纳秒精度防漂移、S24 符号扩展、时间戳对齐规则 |
| `Docs/MILESTONES.md` | M7 唯一硬缺口 =「真实 DUT 协议解码」，被协议冻结阻塞 |

**空白**：`SensorId` 只有 6 路枚举，无 IMU 组合流；无会话绑定（session_id 不进协议）；无对时消息。

### 3. protobuf / TLV / 分包 —— ✅ 链路层设计完整

| 出处 | 结论 |
|------|------|
| `SPEC.md §4.3` | 12B 标准头：`ver/msgType/flags/fragIndex/fragCount/hdrCrc8/pktSeq(u16)/msgId(u16)/payloadLen(u16)`，小端；TLV 语义 = T:`msgType`、L:`payloadLen`、V:protobuf |
| `SPEC.md §4.4` | 分片：同 `msgId` 共 `fragCount` 片、`fragIndex` 升序拼接、乱序允许、缺片 2s 超时丢整条、`fragIndex≥fragCount` 丢弃；**分片独占 BLE 包** |
| `SPEC.md §4.5` | 一包多帧拼接 + 框架错误处理（剩余 <12B / payloadLen 超界 / hdrCrc8 失败均保留前序帧） |
| `SPEC.md §4.9` | `hdrCrc8`（CRC-8 poly 0x07）强制；payload CRC16/CCITT 可选（`HAS_PAYLOAD_CRC`）；`pktSeq` 包级 + `batch_seq` 流级双层丢包检测 |
| 决策 D-5 | **App 不配置 BLE 链路参数**（MTU/连接间隔设备自定）→ 高频批包须用 `SetPacking` 控制大小避免分片 |
| 决策 D-4/D-10 | 协议与固件端共同设计；Wire 生成 Kotlin；`.proto` 双端同源 |
| 写方向约束 | v0 App→设备**不做协议级分片**，Command 必须装进单次 ATT write（`SPEC.md §4.2`） |

**空白**：大块数据（离线文件）从设备→App 的批量回传流程完全没有——分片机制是为「偶发大消息」设计的，不是文件传输通道。

### 4. 协议解析器 / 可注册架构 —— ⚠️ 有分层思想，无注册机制

| 出处 | 结论 |
|------|------|
| `Docs/legacy/BlueTrace_Architecture.md §14` | 解码流水线四层：`FrameReader`（纯函数切帧）→ `Reassembler`（按 msgId 缓冲分片）→ `dispatch(msgType)` → `OutputRouter`（1 消息类型 → 1..N Sink） |
| 同上 §8 | `SensorProfile<TSample>` 泛型 Profile 抽象、按角色错误隔离 |
| `shared/.../protocol/SampleDecoder.kt` | 现行接口：`decode(kind, notification): List<DecodedSample>`，v1 为 `MockSampleDecoder`，注释明确「协议冻结后换 WireSampleDecoder，接口不变」 |
| `shared/.../protocol/FrameHeader.kt` | 12B 头数据类 + `MsgType` 注册表已就位（v1 桩） |
| `SPEC.md §4.6` | 未知 msgType **强制跳过**（用 payloadLen 跳帧继续），为扩展预留 |

**空白**：`SampleDecoder` 是**单一全局解码器**（Koin 单例），没有「按设备/项目选择解析器」的机制；也没有通道（Service/Characteristic UUID）维度的抽象——多项目多协议无处注册。由 02 号文档承接。

### 5. 离线采集 / 在线采集 —— ⚠️ 有 UI 入口，无协议

- V4 精简时「双采集模式」曾移入二期，但当前实现已把入口做回主界面：采集 Tab 底部 = 「离线采集」小入口 + 「在线采集」主按钮（`CollectHomeScreen.kt:160`，离线点击 → Toast「离线采集待协议支持」，实机已验证）。
- 原型 `v4_android.html` 已画出离线三屏：**离线A** 读取 DUT 存储·已存会话 / **离线B** 导入中（DUT→本机）/ **离线C** 分配场景与用户。
- **协议层面（设备侧怎么存、App 怎么列目录、怎么断点续传）完全空白**。由 [03_collect_protocol_design.md](03_collect_protocol_design.md) 承接。

### 6. 手表控制 —— ⚠️ 仅命令原语 + 设置占位

- `bluetrace_v0.proto` 已有控制原语：`Command(cmd_id, oneof{SetSensorEnable/SetSampleRate/SelectAlgorithm/SetPacking/SessionControl/QueryCapability/QueryState})` + `Ack(cmd_id,status)`（`SPEC.md §4.8` 双向控制与 ACK）。
- 设置 Tab「设备维护（DUT）」入口已占位（实机可见，副标题「对时/用户信息/固件日志/OTA · 后期」），`SPEC.md` §7.2 明确「入口预留、内容后期」。
- **没有任何控制界面/交互设计**。由 [04_watch_control_plan.md](04_watch_control_plan.md) 承接。

## 对新设计的三条硬约束（从历史结论继承）

1. **信封不动**：12B 头 + 分片 + CRC 规则已过评审，02/03 的一切设计都复用该信封，不另起帧格式；
2. **D-5 继续有效**：App 不配链路参数 → 高吞吐场景（离线回传）靠协议级流控（credit 窗口），不是靠调 MTU；
3. **冻结纪律**：`bluetrace_v0.proto` 冻结后只增不改 tag —— 03 号文档的新消息全部走**新增 msgType + 新增 message**，不改动既有定义。
