# UWTP 统一可穿戴传输协议 v0.2-draft

> **UWTP = Unified Wearable Transport Protocol**  
> 文档版本：**v0.2-draft**  
> S7 Collect Profile 版本：**v0.2-draft**  
> Core Header 版本：**header_ver = 1**  
> 日期：2026-07-12  
> 状态：架构与注册表草案；详细 payload 尚待逐包评审  
> 合并来源：`UWTP_BLE_Protocol_Design_V0.99-r2`、`Collect_Link_Protocol_SPEC v1.1-draft` 及 2026-07-11～12 协议评审结论

---

## 0. 本版定位

本版将 Collect Link 的轻量化成果合并回 UWTP，不再把 Collect Link 作为独立协议演进。

```text
UWTP Core v0.2
├── 固定 5B Core Header
├── Controller–Device 非对称模型
├── 稳定 main_type / opcode 注册规则
├── 单排他操作与串行事务模型
├── LIVE 域
├── TRANSFER 域
├── CTRL / TUNNEL / VENDOR 等扩展域
└── S7 Collect Profile v0.2
```

### 0.1 本版冻结

1. 5B Core Header 的字段位置与基本含义；
2. `main_type` 表示事务域，`sub_type` 表示响应位与域内 opcode；
3. `sub_type.bit7 = IS_RESPONSE`；
4. App 为 Controller，Device 为被控端与数据提供端；
5. 所有 App 业务命令必须获得明确响应；
6. LIVE / TRANSFER 域的 opcode 注册表；
7. 单排他操作、单传输事务与状态命令白名单；
8. `BUSY`、`INVALID_STATE` 与停止屏障；
9. 已分配编号只增不改，废弃不复用。

### 0.2 本版暂不冻结

- 非零 `SEG_MODE` 的完整重组协议；
- LIVE 各 payload 的最终字节布局；
- TRANSFER_BEGIN / ACK / DATA 的最终字段表；
- 对象类型的附加元数据；
- timeout、窗口默认值与产品资源上限；
- CTRL、TUNNEL 的完整 opcode 表；
- 每条消息最终采用 Protobuf、TLV 或固定二进制。

### 0.3 演进原则

```text
已分配编号：语义稳定，只增不改
已发布字段：不改变原含义
废弃编号：永久保留，不重新使用
未知 main_type/opcode：安全忽略或返回 NOT_SUPPORTED
新增域/命令：不要求升级 header_ver
包头解释不兼容：才升级 header_ver
```

---

# 1. 版本模型

| 层次 | 当前值 | 含义 |
|---|---:|---|
| 文档/协议发布版本 | `v0.2-draft` | 设计、注册表与行为规则 |
| Product Profile | `s7-collect/v0.2-draft` | S7 产品绑定、能力与数据格式 |
| Core Header | `header_ver=1` | 只决定如何解释每帧固定包头 |

规则：

- v0.2 → v0.3 可以追加 main_type、opcode、record type 与 optional 字段；
- 只要 5B Header 兼容，`header_ver` 仍为 1；
- `header_ver=2` 仅用于改变固定头长度、字段顺序或既有 bit 含义；
- 原 `UWTP v0.99-r2` 归档为旧冻结候选；
- Collect Link 不再单独编号。

建议标识：

```text
registry_id = "UWTP-REG/0.2-draft"
profile_id  = "s7-collect/0.2-draft"
```

---

# 2. Core Header

| Offset | Size | Field | Type | 说明 |
|---:|---:|---|---|---|
| 0 | 1 | `ver_flags` | u8 | 包头版本与帧级 flags |
| 1 | 1 | `main_type` | u8 | 事务域 |
| 2 | 1 | `sub_type` | u8 | 响应位 + 域内 opcode |
| 3 | 1 | `seq` | u8 | 请求配对或数据序列诊断 |
| 4 | 1 | `len` | u8 | 当前 payload 字节数 |

```text
frame_size = 5 + len
```

所有固定多字节整数使用 little-endian。

## 2.1 ver_flags

```text
bit7..4 = header_ver
bit3    = EXT_HDR
bit2    = NEED_RSP
bit1..0 = SEG_MODE
```

| 字段 | v0.2 语义 |
|---|---|
| `header_ver` | 固定为 1 |
| `EXT_HDR` | 当前为 0，bit 位置保留 |
| `NEED_RSP` | 1 表示必须获得业务响应的命令 |
| `SEG_MODE=00` | 当前消息为单帧 |
| `SEG_MODE=01/10/11` | 为 v0.2 后续分段语义预留 |

约束：

- App 业务命令：`NEED_RSP=1`；
- Device 响应：`NEED_RSP=0`；
- DATA、ACK、EVENT、未启用心跳：`NEED_RSP=0`；
- 响应不得再次要求响应。

## 2.2 SEG_MODE 与 offset

```text
SEG_MODE：逻辑消息超过单帧时的小消息重组
offset：文件、镜像、资源包等大对象传输进度
```

不得用两层机制表达同一层分块。`RAW_CHUNK` 与 `TRANSFER_DATA` 使用自身组包/offset，不依赖 SEG_MODE。未来大型查询响应或配置快照可在 v0.2.x 补充 SEG_MODE 语义，不升级 `header_ver`。

---

# 3. main_type 与 sub_type

## 3.1 main_type 注册表

| main_type | 名称 | 状态 | 说明 |
|---:|---|---|---|
| `0x00` | RESERVED | 保留 | 不得在线上传输 |
| `0x01` | CTRL | 待细化 | Session、状态、时间、能力和全局控制 |
| `0x10` | LIVE | 已分配 | 在线采集配置、会话、数据、结果与事件 |
| `0x11` | TRANSFER | 已分配 | 双向对象传输、离线文件、日志、OTA、资源包 |
| `0x12` | TUNNEL | 预留 | 厂商协议或调试字节流 |
| `0xF0` | VENDOR | 保留 | 厂商/实验扩展 |
| 其他 | — | 未分配 | 后续只增不改 |

旧 UWTP 域合并关系：

| 旧域 | v0.2 归属 |
|---|---|
| ALGO | LIVE |
| LOG LIVE_EVENT | LIVE |
| LOG EXPORT | TRANSFER，object_type=LOG |
| FILE | TRANSFER |
| OTA | TRANSFER，object_type=FIRMWARE/RESOURCE |
| CTRL | 保留 |
| TUNNEL | 保留 |

## 3.2 sub_type

```text
bit7    = IS_RESPONSE
bit6..0 = opcode
```

```text
请求/命令：sub_type = opcode
响应：    sub_type = opcode | 0x80
```

响应与请求必须满足：

```text
main_type 相同
opcode 相同
seq 相同
IS_RESPONSE = 1
NEED_RSP = 0
```

事务匹配键：

```text
(main_type, opcode, seq)
```

## 3.3 合法角色组合

| IS_RESPONSE | NEED_RSP | 语义 |
|---:|---:|---|
| 0 | 1 | App 发起的业务命令 |
| 1 | 0 | Device 对业务命令的响应 |
| 0 | 0 | DATA、ACK、EVENT、心跳 |
| 1 | 1 | 非法 |

## 3.4 统一响应

除传输反馈类帧外，所有 App 业务命令必须返回一次响应。响应 payload 第一个字段统一为：

```text
status u8
```

---

# 4. Controller–Device 非对称模型

```text
Controller = App
Device     = 被控端 + 数据提供端
```

App 发送：

- 查询、配置、START/STOP、DELETE；
- TRANSFER_BEGIN/FINISH/ABORT；
- DATA（APP_TO_DEVICE）；
- ACK/credit（App 为接收端）。

Device 发送：

- 命令响应；
- LIVE CONTEXT/RAW/RESULT/EVENT；
- DATA（DEVICE_TO_APP）；
- ACK（Device 为接收端）；
- TRANSFER_EVENT。

Device 不主动向 App 发起需要业务响应的查询或控制。

判断新消息的规则：

```text
App 业务命令？    → 必须响应
Device 数据/事件？ → 不要求业务响应
接收方流控反馈？  → ACK，不回应 ACK
```

---

# 5. 单排他操作与串行事务

## 5.1 OperationState

```text
0 = IDLE
1 = OFFLINE_COLLECTING
2 = LIVE_ACTIVE
3 = TRANSFER_ACTIVE
```

以下业务互斥：

- 离线采集；
- LIVE 在线回传；
- Device → App 对象上传；
- App → Device 对象下载；
- 固件 OTA；
- 资源、算法、配置包传输。

跨域操作必须先回到 `IDLE`。

## 5.2 单传输事务

`TRANSFER_ACTIVE` 内最多一个 active transfer：

```text
不支持多个对象并行
不支持上传与下载并行
不支持 OTA 与离线文件上传并行
不隐式排队第二个 TRANSFER_BEGIN
```

活动传输存在时，任何新 `TRANSFER_BEGIN` 返回 `BUSY`。

## 5.3 transfer_id

transfer_id 是**事务世代号**，用于拒绝前一事务迟到的 DATA/ACK/EVENT，不是并发通道号。每个新事务使用新的 transfer_id。

## 5.4 状态白名单

| OperationState | 允许的核心消息 |
|---|---|
| IDLE | 状态查询、列表、LIVE_CONFIG/START、TRANSFER_BEGIN、DELETE |
| OFFLINE_COLLECTING | 状态查询、当前采集相关控制 |
| LIVE_ACTIVE | LIVE_STOP、当前 LIVE 会话命令、必要状态查询 |
| TRANSFER_ACTIVE | 当前 transfer 的 DATA、ACK、FINISH、ABORT、STATUS、必要状态查询 |

不在白名单内的业务命令不得隐式排队或自动中止当前业务。

## 5.5 BUSY 与 INVALID_STATE

`BUSY`：命令本身在空闲时合法，但当前被另一排他业务占用。

`INVALID_STATE`：命令违反本域流程顺序，原样重试仍会失败。

---

# 6. LIVE 域（main_type=0x10）

详细 payload 后续逐包评审，本版先冻结 opcode 与角色。

| Opcode | 名称 | 方向 | 类型 | Wire sub_type | NEED_RSP |
|---:|---|---|---|---:|---:|
| `0x01` | LIVE_CONFIG | App → Device | 命令 | `0x01` | 1 |
| `0x01` | LIVE_CONFIG_RSP | Device → App | 响应 | `0x81` | 0 |
| `0x02` | LIVE_START | App → Device | 命令 | `0x02` | 1 |
| `0x02` | LIVE_START_RSP | Device → App | 响应 | `0x82` | 0 |
| `0x03` | LIVE_STOP | App → Device | 命令 | `0x03` | 1 |
| `0x03` | LIVE_STOP_RSP | Device → App | 响应 | `0x83` | 0 |
| `0x10` | LIVE_CONTEXT | Device → App | 上下文 | `0x10` | 0 |
| `0x11` | RAW_CHUNK | Device → App | 数据 | `0x11` | 0 |
| `0x12` | RESULT_BUNDLE | Device → App | 结果 | `0x12` | 0 |
| `0x13` | LIVE_EVENT | Device → App | 事件 | `0x13` | 0 |

编号区间：

```text
0x01–0x0F  App 发起的 LIVE 命令/查询
0x10–0x3F  Device 主动上报
0x40–0x7F  后续扩展
```

## 6.1 生命周期

```text
UNCONFIGURED
    │ CONFIG/RSP(OK)
    ▼
CONFIGURED
    │ START/RSP(OK)
    ▼
STREAMING
    │ STOP/RSP
    ▼
CONFIGURED
```

- CONFIG/START 只控制 UWTP 回传，不启动底层算法；
- 数据源未运行时 START 返回 `INVALID_STATE`；
- STREAMING 时拒绝无关 TRANSFER/DELETE/另一个 START；
- STOP 建议幂等；
- STOP 后保留配置，可再次 START。

## 6.2 LIVE_STOP_RSP 屏障

Device 只有在以下动作完成后返回：

1. 禁止产生新的 LIVE 数据；
2. 已排队旧帧发送完、清除或失效；
3. 释放 LIVE 资源；
4. 清除 live_active；
5. 完成 OperationState 切换。

---

# 7. TRANSFER 域（main_type=0x11）

TRANSFER 是双向通用对象传输域。

| Opcode | 名称 | 方向 | 类型 | Wire sub_type | 响应 |
|---:|---|---|---|---:|---:|
| `0x01` | STATE_QUERY | App → Device | 查询 | `0x01` | `0x81` |
| `0x02` | OBJECT_LIST | App → Device | 查询 | `0x02` | `0x82` |
| `0x03` | TRANSFER_BEGIN | App → Device | 控制 | `0x03` | `0x83` |
| `0x04` | TRANSFER_DATA | 协商方向 | 数据 | `0x04` | 无 |
| `0x05` | TRANSFER_ACK | DATA 反方向 | 反馈 | `0x05` | 无 |
| `0x06` | TRANSFER_FINISH | App → Device | 控制 | `0x06` | `0x86` |
| `0x07` | TRANSFER_ABORT | App → Device | 控制 | `0x07` | `0x87` |
| `0x08` | TRANSFER_STATUS | App → Device | 查询 | `0x08` | `0x88` |
| `0x09` | DELETE_OBJECT | App → Device | 控制 | `0x09` | `0x89` |
| `0x10` | TRANSFER_EVENT | Device → App | 事件 | `0x10` | 无 |

S7 Profile 中：

- STATE_QUERY 响应包含离线采集状态、collect_type、session_id、elapsed_s 与错误信息；
- OBJECT_LIST 初期列出离线 COL2 文件和日志；
- 对象内容对 TRANSFER 域不透明。

## 7.1 方向

```text
0 = DEVICE_TO_APP
1 = APP_TO_DEVICE
```

不使用 upload/download，避免端点视角歧义。

## 7.2 object_type

| object_type | 名称 | 典型方向 |
|---:|---|---|
| `0x01` | OFFLINE_COLLECTION | DEVICE_TO_APP |
| `0x02` | LOG_FILE | DEVICE_TO_APP |
| `0x03` | FIRMWARE_IMAGE | APP_TO_DEVICE |
| `0x04` | RESOURCE_PACKAGE | APP_TO_DEVICE |
| `0x05` | ALGORITHM_PACKAGE | APP_TO_DEVICE |
| `0x06` | CONFIG_PACKAGE | 双向 |
| `0x07` | GENERIC_FILE | 双向 |

## 7.3 BEGIN 协商

至少需要：

- direction；
- object_type/object_id；
- total_size 或读取范围；
- requested/accepted chunk size；
- requested/accepted `ack_every_n`；
- reliability mode；
- start_offset；
- CRC32/Hash 能力；
- transfer_id。

详细布局后续评审。

## 7.4 窗口 ACK

`ack_every_n`：

> 发送方最多连续发送 N 个 DATA 帧，然后必须等待一次 ACK/credit。

```text
1   每包确认
4   小窗口
16  速度与稳定平衡
32  高吞吐、高缓存压力
```

请求 0 可表示由 Device 选择；响应返回实际值。ACK 用于流控、连续进度确认以及必要的 `next_expected_offset`，ACK 不再要求响应。

## 7.5 reliability_mode

| mode | 名称 | 语义 | 适用 |
|---:|---|---|---|
| `0` | BEST_EFFORT | 周期 ACK 流控；允许缺口，不强制重传；标记 PARTIAL | 调试日志、可容忍缺失的离线采集 |
| `1` | RESUMABLE | next_offset 驱动回退/续传；最终校验大小/CRC | 普通离线文件、资源 |
| `2` | VERIFIED | 不允许缺口；CRC/Hash/对象校验通过才成功 | 固件、关键资源、算法包 |

BEST_EFFORT 不得静默伪装成完整文件。

## 7.6 FINISH 与 ABORT

BEGIN、FINISH、ABORT、STATUS 均由 App 发起。

`TRANSFER_FINISH_RSP` 在长度确认、CRC/Hash/对象校验、句柄关闭、提交与资源释放完成后返回。

`TRANSFER_ABORT_RSP` 是数据面停止屏障。Device 返回前必须：

1. 停止该 transfer 的生产者/消费者；
2. 禁止产生新 DATA/ACK；
3. 清理或失效已排队旧帧；
4. 关闭文件/Flash/资源句柄；
5. 释放窗口和缓存；
6. 将 transfer 标为非活动；
7. 完成 OperationState 切换。

收到 ABORT_RSP 后，旧 transfer_id 的迟到帧直接丢弃。

---

# 8. 状态码

| status | 名称 | 含义 |
|---:|---|---|
| `0x00` | OK | 成功 |
| `0x01` | INVALID_REQUEST | 帧角色或命令形式错误 |
| `0x02` | NOT_SUPPORTED | 域、opcode、对象或能力不支持 |
| `0x03` | INVALID_STATE | 域内调用顺序错误 |
| `0x04` | BUSY | 其他排他业务占用 |
| `0x05` | INVALID_PARAM | 参数非法 |
| `0x06` | NO_MEMORY | 无足够缓存/内存 |
| `0x07` | NO_SPACE | 存储空间不足 |
| `0x08` | CRC_ERROR | CRC 失败 |
| `0x09` | HASH_MISMATCH | Hash 不匹配 |
| `0x0A` | AUTH_FAILED | 鉴权失败 |
| `0x0B` | TIMEOUT | 超时 |
| `0x0C` | RETRY_LATER | 临时资源不足 |
| `0x0D` | SERVICE_REJECTED | 产品策略拒绝 |
| `0x0E` | UNSUPPORTED_SCHEMA | Schema 不支持 |
| `0x0F` | UNSUPPORTED_FORMAT | 格式不支持 |
| `0x10` | TOO_LARGE | 超过实现上限 |

---

# 9. 前向兼容

未知 main_type：

- 不进入已知域；
- 不修改业务状态；
- 计数并限频日志；
- 请求可返回 NOT_SUPPORTED，无法安全回应时丢弃。

未知 opcode：

- 不按相邻编号推测；
- 不执行；
- App 命令可返回 NOT_SUPPORTED；
- Device 主动上报由旧 App 忽略。

未知 record/event：

- 有长度时跳过；
- 无法确定长度时丢弃当前容器；
- 不因未知扩展断链。

reserved 字段：

- 发送端填 0；
- 接收端不依赖其恒为 0；
- 未来定义不改变既有字段语义。

---

# 10. S7 Collect Profile v0.2

```text
RX  0x0802
TX  0x0804
CCC 0x0805
```

入口：

```text
0xBB              → legacy B2A
(first & 0xF0)==1 → UWTP
其他              → 工厂 AT 兜底
```

当前实现边界：

- M0：入口分流、5B Header、最小状态查询、TX 串行化；
- M1：真实离线采集状态适配；
- 新 opcode、响应位和双向 TRANSFER 尚未全部落代码；
- 旧 REQ/RSP 成对 sub_type 需迁移为 `IS_RESPONSE` 模型；
- 详细字节表和 golden packet 在逐包评审后更新。

---

# 11. 主要合并变化

| 项 | 旧 UWTP v0.99-r2 | 旧 Collect Link | UWTP v0.2 |
|---|---|---|---|
| 协议身份 | UWTP | Collect Link | 统一 UWTP |
| 版本 | v0.99-r2 | v1.1/架构v0.1 | v0.2-draft |
| 在线域 | ALGO + LOG | LIVE | LIVE |
| 文件/OTA | FILE 与 OTA 分域 | FILE 单向读取 | TRANSFER 双向对象域 |
| 响应编号 | 混合 | REQ/RSP 各占编号 | bit7 响应，opcode 相同 |
| 控制角色 | 部分对称 | App 控制为主 | 明确非对称 |
| 并发 | 域矩阵 | LIVE/FILE 状态 | 单 OperationState |
| ACK | 固定窗口 | 协商方向形成中 | BEGIN 协商 ack_every_n |
| 可靠性 | 域固定 | 离线可容忍丢失 | 三档可靠性 |

---

# 12. 后续逐包评审

1. LIVE_CONFIG；
2. LIVE_START / STOP；
3. LIVE_CONTEXT；
4. RAW_CHUNK；
5. RESULT_BUNDLE；
6. LIVE_EVENT；
7. STATE_QUERY；
8. OBJECT_LIST；
9. TRANSFER_BEGIN；
10. TRANSFER_DATA；
11. TRANSFER_ACK；
12. TRANSFER_FINISH；
13. TRANSFER_ABORT；
14. TRANSFER_STATUS；
15. DELETE_OBJECT；
16. TRANSFER_EVENT；
17. SEG_MODE；
18. CTRL/HELLO 与 registry；
19. golden packet 与 parser contract；
20. S7 绑定和代码迁移。

---

# 13. 当前冻结摘要

```text
协议名称          UWTP
文档版本          v0.2-draft
S7 Profile        s7-collect/v0.2-draft
Core Header       5B，header_ver=1
交互角色          App Controller / Device Server
响应模型          sub_type.bit7=IS_RESPONSE
事务配对          main_type + opcode + seq
业务命令          App 发起，必须响应
数据/事件/ACK     不要求普通业务响应
顶层并发          单排他操作
文件事务          单 active transfer
传输方向          BEGIN 协商，双向
窗口确认          BEGIN 协商 ack_every_n
可靠性            BEST_EFFORT / RESUMABLE / VERIFIED
STOP/ABORT 响应   真正静止后的屏障
版本演进          已分配编号只增不改
```
