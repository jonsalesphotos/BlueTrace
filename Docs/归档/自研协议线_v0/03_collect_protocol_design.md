# 03 · 采集链路协议 BTCP/1（在线流式 + 离线回传）

> 状态：设计稿 v1（2026-07-02），**与固件端评审后并入 `bluetrace_v0.proto` 冻结为 v1**。
> **2026-07-06 更新**：M7 冻结路径改为**二选一**——本稿（自研 BTCP/1 线）vs [UWTP V0.99](../UWTP/UWTP_BLE_Protocol_Design_V0.99.md)（当前冻结候选基线）；固件评审拍板前本稿保留为自研线权威，勿据此生成代码。
> 配套 schema 草案：[btcp1_draft.proto](btcp1_draft.proto)（本文所有新消息的机器可读定义）。
> 信封（TLV 12B 头 + 分片 + CRC）**完全复用 SPEC §4.3–4.9**，本文不重复链路层细节。

## 0. 范围与立场

用户可见的两种采集在协议上是**同一套帧流的两种投递方式**：

| 模式 | 投递方式 | 现状 |
|------|----------|------|
| **在线采集** | 设备实时 生成 → 打包 → Notify 推送，App 边收边落盘 | v0.1 已覆盖（HighFreqBatch/LowFreqFrame），本文补会话绑定与对时 |
| **离线采集** | 设备把**同样的帧流**写进自身 Flash，之后 App 分块拉回 | **协议空白**（实机为占位 Toast），本文主体 |

**核心设计决策 P-1：离线会话文件 = 在线帧流的落盘镜像。**
设备存储的会话文件格式就是「12B 头 + payload 的帧序列」（与在线 Notify 内容同构）。
收益：App 侧**一个解析器（02 号文档的 BlueTraceV0Parser）同时服务在线流与离线导入**——离线导入 = 把文件字节按帧喂同一个 parser；固件侧同理复用打包器。双端都没有第二套编解码。

## 1. TLV 信封回顾（继承，不改）

```
一帧 = [12B 头] + [payloadLen 字节 payload(protobuf)]
头 = ver(1) msgType(1) flags(1) fragIndex(1) fragCount(1) hdrCrc8(1) pktSeq(2LE) msgId(2LE) payloadLen(2LE)
```

- T = `msgType`，L = `payloadLen`，V = protobuf —— TLV 语义已内建；
- 分片（FRAGMENTED/MORE_FRAGMENTS + msgId 组包，缺片 2s 丢弃）给**偶发大消息**；
- 高吞吐路径（高频批包、离线块）**必须自控大小避免分片**（决策 D-5：App 不配 MTU）。

## 2. msgType 注册表增量

| msgType | 名称 | 方向 | 说明 |
|---------|------|------|------|
| 0x01/0x02/0x03 | HighFreqBatch / LowFreqFrame / GoodixPpgAcc | 设→App | 既有（在线数据面，IMU 走 0x01） |
| 0x10/0x11/0x12 | DeviceEvent / Capability / DeviceState | 设→App | 既有（控制面） |
| **0x13** | **TimeSyncPong** | 设→App | 新增：对时应答（§4） |
| 0x20 | AlgoResult | 设→App | 既有 |
| 0x40 / 0x80 | Command / Ack | App→设 / 设→App | 既有；Command 的 oneof **新增 8 个条目**（§3/§5） |
| **0x50** | **SessionCatalog** | 设→App | 新增：设备端已存会话目录（分页） |
| **0x51** | **TransferStart** | 设→App | 新增：块传输开始（元数据+总 CRC） |
| **0x52** | **TransferChunk** | 设→App | 新增：数据块（高吞吐路径，禁分片） |
| **0x53** | **TransferEnd** | 设→App | 新增：块传输完成/中止 |
| **0x54** | **StorageInfo** | 设→App | 新增：设备存储占用 |
| **0x55** | **OfflineSessionHeader** | 仅文件内 | 新增：离线会话文件首帧（不走空口） |

> 0x50–0x5F 段整体划给「离线回传」，0x56–0x5F 保留。未知 msgType 跳帧规则（SPEC §4.6）保证老 App 遇到新消息不断流。

## 3. 在线采集补强（对 v0.1 的三个增量）

**A. 会话绑定**：`SessionControl` 增加字段（pre-freeze 可加）：

```protobuf
message SessionControl {
  enum Action { STOP = 0; START = 1; }
  Action action = 1;
  uint32 app_session_id = 2;   // App 生成的会话号，设备回填进后续 DeviceEvent，杜绝跨会话串包
  uint64 wall_clock_ms  = 3;   // START 附带手机 Unix 毫秒，设备据此建立「设备时钟↔UTC」锚点
}
```

**B. IMU 适配确认**（不加新消息，用足既有设计）：
- IMU 三路（ACC=3/GYRO=4/MAG=5）各自一条 `HighFreqBatch` 流，`channel_count=3`、典型 `SF_S16`，`batch_seq` 独立计数；
- 6 轴同采不合流——两条流各带 `sample_period_ns`，时间轴天然对齐（同一设备时钟），App 侧按需 join；避免重蹈 GoodixPpgAcc 双流塞一包的复杂度（那是外购模组的兼容特例）；
- 打包建议：`SetPacking(batch_samples=25, frame_period_ms=250)` → 100 Hz ACC 一帧 ≈ 12+~20+150 B，单包不分片；带宽 100 Hz×6ch×2B ≈ 1.2 kB/s，链路余量充足。

**C. 对时（TimeSync）**：见 §4，在线/离线共用。

## 4. 对时协议（NTP 式四时间戳）

离线数据只有设备单调时钟，导入后必须能换算 UTC；在线会话跨小时也需要漂移校正。

```
App --Command{TimeSyncPing(seq, t0=app发送时刻us)}--> 设备
App <--0x13 TimeSyncPong(seq, t0回显, t1=设收us, t2=设回us)-- 设备      App 记 t3=收到时刻
offset = ((t1-t0)+(t2-t3))/2      rtt = (t3-t0)-(t2-t1)
```

- 连接建立后立刻做 3 次取 rtt 最小者为锚点，写进 `session_manifest.json`（`deviceClockOffsetUs` + `rttUs`）；
- 设备收到 `TimeSyncPing` 或 `SessionControl.wall_clock_ms` 后，把「设备时钟↔UTC 锚点」**持久化到 Flash**——离线会话文件头（§6）回带该锚点，App 导入时即可恢复绝对时间，误差 = rtt/2 + 设备晶振漂移（20 ppm × 24h ≈ 1.7s，可接受；要求更高时导入后用最近一次对时二次校正）。

## 5. 离线回传（本文主体）

### 5.1 命令增量（`Command.oneof` 新增，tag 9–16）

```protobuf
ListSessions    { uint32 page = 1; uint32 page_size = 2; }              // → 0x50 SessionCatalog
OpenTransfer    { uint32 session_id = 1; uint64 resume_offset = 2;      // → 0x51 TransferStart + 0x52 块流
                  uint32 max_chunk_bytes = 3; }                          // App 声明单块上限(按当前MTU算)
TransferCredit  { uint32 transfer_id = 1; uint32 credits = 2; }         // 流控：允许再发 N 块
CloseTransfer   { uint32 transfer_id = 1; bool abort = 2; }             // 正常关/中止
DeleteSession   { uint32 session_id = 1; }                              // 确认校验通过后显式删
QueryStorage    {}                                                      // → 0x54 StorageInfo
TimeSyncPing    { uint32 seq = 1; uint64 app_tx_us = 2; }               // → 0x13 TimeSyncPong
FormatStorage   { bool confirm = 1; }                                   // 后期·设备维护用
```

### 5.2 设备 → App 消息

```protobuf
// 0x50 会话目录（一页；超 MTU 由标准分片兜底——目录属"偶发大消息"，允许分片）
message SessionCatalog {
  uint32 total = 1;  uint32 page = 2;
  repeated OfflineSessionMeta sessions = 3;
}
message OfflineSessionMeta {
  uint32 session_id = 1;          // 设备本地自增
  uint64 start_device_ts_us = 2;
  uint64 anchor_utc_ms = 3;       // 最近对时锚点换算的开始 UTC（0=从未对时）
  uint32 duration_s = 4;
  uint64 size_bytes = 5;
  repeated uint32 sensor_ids = 6 [packed = true];
  uint32 app_session_id = 7;      // 若由 App 触发离线采集则回填
}

// 0x51 传输开始
message TransferStart {
  uint32 transfer_id = 1;         // 本次传输句柄（设备自增）
  uint32 session_id = 2;
  uint64 total_bytes = 3;
  uint64 resume_offset = 4;       // 设备确认的起始偏移（断点续传）
  uint32 chunk_bytes = 5;         // 实际块大小 = min(App声明, 设备能力, 单包不分片上限)
  uint32 file_crc32 = 6;          // 全文件 CRC32（IEEE），App 收完整后校验
}

// 0x52 数据块 —— 高吞吐路径：单块必须装进单个 notification（禁分片）
message TransferChunk {
  uint32 transfer_id = 1;
  uint64 offset = 2;
  bytes  data = 3;                // 长度 ≤ TransferStart.chunk_bytes
}

// 0x53 传输结束
message TransferEnd {
  uint32 transfer_id = 1;
  enum Reason { COMPLETE = 0; ABORTED = 1; DEVICE_ERROR = 2; }
  Reason reason = 2;
  uint64 sent_bytes = 3;
}

// 0x54 存储占用
message StorageInfo { uint64 total_bytes = 1; uint64 used_bytes = 2; uint32 session_count = 3; }
```

### 5.3 流控与吞吐（credit 窗口）

D-5（App 不配链路参数）下，吞吐靠协议级流控：

```
App: OpenTransfer(max_chunk_bytes=201)          ← 201 = 当前ATT_MTU-3 -12头 -~18 protobuf开销
设:  Ack + TransferStart(chunk=201, crc, total)
App: TransferCredit(credits=16)                 ← 授信 16 块
设:  Chunk(off=0) Chunk(off=201) ... ×16        ← 连发到授信耗尽
App: 每消费 8 块补授信 TransferCredit(8)         ← 滑动窗口，流水线不断
...
设:  TransferEnd(COMPLETE)
App: CRC32 校验 → 通过 → (用户确认后) DeleteSession
```

- **完整性三层**：BLE 链路层 CRC（控制器）→ 帧头 hdrCrc8 → 全文件 CRC32；块内不再加 CRC（链路层已保证按序可靠，credit 只防缓冲溢出不防丢包——GATT Notify 在链路层要么到要么断连）；
- **断点续传**：App 侧记 `(session_id, 已收 offset)`，重连后 `OpenTransfer(resume_offset)`；
- **吞吐估算**：MTU 247、CI 30ms、每 CI 4 包 → ~27 kB/s；理想（2M PHY、DLE、CI 15ms）可达 60–100 kB/s。**1h IMU 6 轴@100Hz(S16) ≈ 4.3 MB → 1.5–3 分钟拉完**；原型「离线B 导入中」的进度条语义据此成立；
- 传输期间设备**暂停在线数据面**（或降速），避免同信道竞争——由固件端确认。

### 5.4 App 侧状态机（对应原型 离线A/B/C 三屏）

```
Idle → Cataloging(ListSessions) → 离线A[会话列表]
     → Transferring(credit loop, 进度=offset/total) → 离线B[导入中·可取消(CloseTransfer abort)]
     → Verifying(CRC32) → Imported(帧流重放进 BlueTraceV0Parser → 现有 SessionRecorder 落盘)
     → Assigning → 离线C[分配场景/用户 → 写 manifest] → Done[进数据 Tab]
失败路径：断连→断点续传；CRC失败→整段重拉(保留 resume 文件)；存储不足→复用导出预检 UI
```

导入产物与在线会话**同构**（同一套 D-6 文件夹 + manifest），数据 Tab 无差别展示——manifest 增加 `"origin": "offline"` 与 `session_id` 字段。

## 6. 离线会话文件格式（设备侧，P-1 的落地）

```
[帧0] 0x55 OfflineSessionHeader   ← 唯一的"文件专属"帧
[帧1..N] 与在线完全一致的 0x01/0x02/0x10/0x20 帧序列（pktSeq 从 0 递增）
```

```protobuf
// 0x55 仅存在于文件首；空口不发
message OfflineSessionHeader {
  uint32 format_version = 1;      // 文件格式版本，独立于协议 ver
  OfflineSessionMeta meta = 2;
  uint64 anchor_device_ts_us = 3; // 对时锚点：设备时刻
  uint64 anchor_utc_ms = 4;       //           对应 UTC（0=无锚点，导入时须现场对时估算）
}
```

- App 导入 = 逐帧过一遍 `BlueTraceV0Parser`（跳过 0x55 由导入器消费）；hexlog 照常落盘（`raw/offline_<session_id>.hexlog`），**source of truth 纪律对离线数据同样成立**；
- 固件写入即打包好的帧 → 掉电最多损失尾部一帧（append-only），App 解析天然容忍截断（框架错误处理保留前序帧）。

## 7. 开放问题（冻结前须与固件端拍板）

| # | 问题 | 倾向 |
|---|------|------|
| O-1 | 传输期间在线数据面是否暂停 | 暂停（简单、独占带宽） |
| O-2 | `session_id` 掉电持久性与回绕 | Flash 持久自增 u32，不回绕 |
| O-3 | 设备存储满策略 | 环形覆盖最旧 + `DeviceEvent(code=STORAGE_WRAPPED)` 上报 |
| O-4 | 离线采集的启动方式 | 两者都要：App 下发 `SessionControl(START, offline=true)`（需加字段）+ 设备侧按键自启 |
| O-5 | 是否需要链路加密/绑定 | 一期 Just Works 配对，不做应用层加密（内部采集工具定位） |
| O-6 | TransferChunk 是否要块级 CRC16 | 不加（三层完整性已够）；固件若有 DMA 校验诉求再议 |

## 8. 与 02 号架构的接线

- `BlueTraceV0Parser` 的 dispatch 表加 0x13/0x50–0x54 → 产出 `ProtocolEvent.FileChunk` 等事件；
- 离线导入器 `OfflineImporter`（shared commonMain）消费事件驱动 §5.4 状态机——**S7 等其它 Profile 完全不感知离线机制**（各协议自治）；
- 手表控制台（04）复用 `QueryStorage`/`FormatStorage`/`TimeSyncPing` 同一命令面。
