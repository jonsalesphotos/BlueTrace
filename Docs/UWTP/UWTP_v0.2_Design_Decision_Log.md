# UWTP v0.2 设计思维链与决策记录

> 版本：v0.2-draft  
> 日期：2026-07-12  
> 性质：可公开、可审计的设计论证与决策链  
> 说明：本文记录讨论中的问题、判断依据、方案取舍与结论，供维护者理解“为什么这样设计”。它不是任何参与者的私有隐藏推理逐字稿。

---

# 0. 为什么需要这份文档

协议正文回答：

```text
线上字节是什么
状态机是什么
哪些命令合法
```

维护者还会问：

```text
为什么不再使用第二个 GATT 服务？
为什么所有 App 命令都必须回应？
为什么响应不再单独占一个 opcode？
为什么文件和 OTA 要合并？
为什么只有一个传输还保留 transfer_id？
为什么 LIVE 时大量命令返回 BUSY？
为什么 ABORT 已有状态查询还要响应？
```

本文回答这些“为什么”。

---

# 1. 从 Collect Link 回到 UWTP

## 问题

早期 UWTP 同时覆盖 CTRL、ALGO、LOG、OTA、FILE、TUNNEL，设计完整但心智负担较重。S7 采集落地时又产生 Collect Link，形成两套文档与两套版本。

## 观察

Collect Link 的真正价值不是发明另一套协议，而是验证了 UWTP Core 的正确方向：

- 5B 小头可落地；
- main_type 应按事务域分；
- 在线采集与离线对象传输必须解耦；
- 固定上下文与高频数据要分离；
- 文件传输要协商 chunk/window；
- 产品接线和 wire contract 必须分文档；
- 嵌入式实现需要明确的互斥模型。

## 决策

Collect Link 合并回 UWTP：

```text
UWTP Core v0.2
S7 Collect Profile v0.2
```

原 UWTP v0.99-r2 作为历史冻结候选归档。

## 影响

后续只维护一套：

- Core Header；
- main/opcode 注册表；
- 状态码；
- Controller–Device 规则；
- Profile 绑定；
- golden packet。

---

# 2. 为什么版本统一为 v0.2，但 header_ver 仍为 1

## 问题

旧 UWTP 使用 v0.99-r2，Collect Link 使用 v1.1-draft/架构 v0.1，版本含义混杂。

## 观察

至少存在三个不同概念：

```text
文档设计版本
产品 Profile 版本
帧头解析版本
```

把它们都叫“V1”会产生误解。

## 决策

```text
UWTP 文档          v0.2-draft
S7 Collect Profile v0.2-draft
header_ver         1
```

## 原则

- 文档可以 v0.2 → v0.3；
- opcode、record、object type 可继续追加；
- 只要 5B Header 兼容，header_ver 不变；
- 只有包头本身不兼容才升级 header_ver。

---

# 3. 为什么保留 5B Core Header

## 候选

1. 4B：删除 len 或压缩 type；
2. 5B：ver_flags/main/sub/seq/len；
3. 6～8B：增加 magic、u16 长度或逐帧 CRC。

## 判断

4B 只省 1B，却牺牲边界校验或类型空间。

6～8B 对 BLE 单 ATT value 场景收益有限：

- ATT 已提供帧边界；
- payload 当前小于 255B；
- BLE 链路已有 CRC 与重传；
- 文件有对象级 CRC/Hash。

## 决策

```text
ver_flags | main_type | sub_type | seq | len
```

五个字段职责独立，保持固定 5B。

---

# 4. SEG_MODE 为什么保留但不急于设计完

## 初始分歧

一度考虑宣布 v1 完全不启用 SEG_MODE，因为 LIVE RAW 和 FILE DATA 都有自己的分块方式。

随后发现：

> 若未来出现非文件的大型配置快照或查询响应，完全禁用 SEG_MODE 会迫使协议升级。

## 进一步判断

也没有必要在未出现真实需求时提前锁死：

- 重组总长；
- fragment offset；
- timeout；
- 并发上下文；
- ACK；
- 最大逻辑消息；
- FIRST/MIDDLE/LAST 异常规则。

## 决策

冻结 bit 位置与职责边界：

```text
SEG_MODE 管逻辑小消息重组
offset 管大对象进度
```

`00=SINGLE` 当前使用；非零组合后续按需求补充，不升级 header_ver。

---

# 5. main_type 为什么按事务域

## 选择

可按具体包格式划分：

```text
RAW / RESULT / FILE_DATA
```

也可按事务域划分：

```text
CTRL / LIVE / TRANSFER
```

## 判断

事务域不仅决定 payload，还决定：

1. 生命周期；
2. 允许命令；
3. 并发与资源占用；
4. 错误返回；
5. 断连清理；
6. ACK/可靠性。

## 决策

```text
main_type 选择事务域
opcode 选择域内动作
payload 表达具体数据
```

旧 ALGO 合入 LIVE；FILE、OTA、LOG EXPORT 合入 TRANSFER。

---

# 6. 为什么采用 Controller–Device 非对称模型

## 最初盲点

早期容易追求形式对称：

```text
App 能查询 Device，Device 是否也要查询 App？
每个命令是否要设计反向版本？
每个数据是否要配业务响应？
```

真实产品并不对称。

## 事实

App 是业务控制者：

```text
查询
配置
开始
停止
删除
发起传输
```

Device 负责：

```text
响应
提供或接收数据
上报事件
```

## 决策

```text
Controller = App
Device = 被控端 + 数据提供端
```

## 收益

设计新消息时只问：

```text
App 业务命令？    → 必须响应
Device 数据/事件？ → 不要求响应
接收方流控反馈？  → ACK，不回应 ACK
```

---

# 7. 为什么响应使用 sub_type.bit7

## 旧方案

```text
0x01 CONFIG_REQ
0x02 CONFIG_RSP
0x03 START_REQ
0x04 START_RSP
```

## 问题

- 请求与响应占两份编号；
- 注册表容易错配；
- 新命令必须同时登记两个值；
- 响应角色依赖名称或方向推断。

## 新方案

```text
bit7    = IS_RESPONSE
bit6..0 = opcode
```

例如：

```text
LIVE_CONFIG     0x01
LIVE_CONFIG_RSP 0x81
```

## 为什么还要 seq

响应位只说明“这是响应”，不能说明“响应哪一次请求”。

事务配对必须使用：

```text
main_type + opcode + seq
```

## 决策

响应位与相同 seq 同时使用，各司其职。

---

# 8. 为什么所有 App 业务命令必须响应

## 旧版裸推问题

- 没有明确开始确认；
- 没有结束确认；
- 不知道设备是否真正停止；
- 无法判断删除是否完成；
- 异常后双方状态容易失步。

## 判断

增加少量控制响应会牺牲极小吞吐，却显著提升：

- 可控性；
- 状态确定性；
- 错误诊断；
- 断线恢复；
- 扩展能力。

## 决策

所有 App 业务命令必须响应。

例外不是“无响应业务命令”，而是不同角色：

- DATA；
- ACK/credit；
- EVENT；
- 未启用 heartbeat/keepalive。

---

# 9. LIVE 域为什么这样分

## 目标

LIVE 需要区分：

```text
控制面
固定上下文
高频原始数据
低频算法结果
异步事件
```

## 决策

控制命令：

```text
0x01 CONFIG / 0x81 RSP
0x02 START  / 0x82 RSP
0x03 STOP   / 0x83 RSP
```

Device 主动上报：

```text
0x10 CONTEXT
0x11 RAW_CHUNK
0x12 RESULT_BUNDLE
0x13 LIVE_EVENT
```

## 关键边界

- CONFIG/START 只控制 UWTP 回传，不启动底层算法；
- CONTEXT 不是 START_RSP，而是数据流的一部分；
- RAW 可丢，使用 sample_index 和 seq 诊断；
- RESULT 与 EVENT 不混入 RAW；
- STOP 响应代表真正停止。

---

# 10. 为什么 FILE 演进成双向 TRANSFER

## 初始范围

Collect Link FILE 最初只面向：

```text
Device → App 离线文件上传
```

## 新需求

随后出现：

```text
App → Device 固件 OTA
资源 OTA
算法包
配置文件
```

如果 OTA 再复制一套 BEGIN/DATA/ACK/END/ABORT，会重复实现相同传输内核。

## 决策

FILE 提升为 TRANSFER：

```text
direction = DEVICE_TO_APP / APP_TO_DEVICE
object_type = OFFLINE / LOG / FIRMWARE / RESOURCE / ...
```

BEGIN、FINISH、ABORT、STATUS 仍由 App 发起；DATA 和 ACK 方向由 BEGIN 协商。

## 收益

- 文件与 OTA 共享 chunk/window/offset/transfer_id；
- 不复制状态机；
- 新对象类型只登记枚举；
- 可靠性可按对象选择。

---

# 11. 为什么在 BEGIN 协商 ACK 间隔

## 两个极端

每包 ACK：

- 最稳定；
- 吞吐低；
- 控制帧多。

完全不 ACK：

- 吞吐高；
- 接收方可能被淹没；
- 停止和异常难确定。

## 决策

BEGIN 协商：

```text
ack_every_n
```

含义：

> 发送方最多连续发送 N 个 DATA 帧，然后等待一次 ACK/credit。

这样可适应：

- 小缓冲设备；
- Android 快速落盘；
- 高速 OTA；
- 可容忍缺失的日志/离线数据。

---

# 12. 为什么可靠性分档

## 事实

```text
调试日志丢少量包仍有价值
离线采集可能容忍局部缺失
固件镜像绝不能缺一字节
```

统一严格重传会牺牲速度；统一允许缺失又无法支持 OTA。

## 决策

```text
BEST_EFFORT
RESUMABLE
VERIFIED
```

## 底线

BEST_EFFORT 不等于静默假装完整：

- 缺口必须记录；
- 文件标记 PARTIAL；
- 不能通过完整性校验时不得标记完整。

---

# 13. 为什么传输必须串行

## 理论

协议可以支持多个并发 transfer_id。

## 实际约束

S7 当前：

- 一条共享 BLE 数据管道；
- FatFS/句柄与锁有限；
- 缓冲和线程有限；
- OTA 写 Flash 独占；
- 并发时带宽、ACK、失败语义复杂。

## 决策

同一时刻最多一个 active transfer：

```text
TRANSFER_ACTIVE 时新 BEGIN → BUSY
```

不自动中止，不排队第二笔事务。

---

# 14. 只有一个传输，为什么保留 transfer_id

## 反例

```text
transfer 7 正在发送
App 发 ABORT
设备停止
App 开始 transfer 8
旧 TX 队列迟到一帧 transfer 7 DATA
```

没有 transfer_id，App 无法判断迟到帧属于旧事务。

## 决策

transfer_id 是事务世代号：

```text
用于拒绝迟到帧
不用于支持并发通道
```

---

# 15. 为什么统一 OperationState

## 原问题

只有 LiveState、FileState、CollectState 时，维护者会问：

```text
为什么 LIVE 时 FILE_LIST 返回 BUSY？
为什么传输时不能 DELETE？
为什么 OTA 时不能开始 LIVE？
```

若只在每个命令下写错误码，无法形成统一心智模型。

## 决策

顶层状态：

```text
IDLE
OFFLINE_COLLECTING
LIVE_ACTIVE
TRANSFER_ACTIVE
```

每个状态有命令白名单。

## 判错

```text
其他排他业务占用 → BUSY
本域调用顺序错误 → INVALID_STATE
参数错误         → INVALID_PARAM
能力不存在       → NOT_SUPPORTED
```

App 恢复策略：

```text
BUSY → 查询状态/等待后重试
INVALID_STATE → 修正调用顺序
```

---

# 16. 为什么 STOP/ABORT 要真正停止后再响应

## 问题

若设备一收到命令就立即响应，但旧 DATA 仍在队列：

```text
App 收到 ABORT_RSP
App 开始下一事务
旧 DATA 又回来
```

状态边界失去意义。

## 决策

STOP_RSP / ABORT_RSP 是 quiescence barrier：

1. 停止生产新数据；
2. 清理或失效旧队列；
3. 关闭句柄；
4. 释放缓存；
5. 完成状态切换；
6. 最后发送响应。

## 状态查询的定位

状态查询不是正常确认的替代品，而用于：

- 响应丢失后的恢复；
- 重连后同步；
- 调试与展示。

---

# 17. 为什么区分 Core、Profile、实现与决策日志

## 旧问题

一个文档混入：

- wire contract；
- S7 句柄；
- 代码行号；
- 风险；
- 内存预算；
- 实施日志。

维护者无法判断什么是跨端契约，什么只是当前产品实现。

## 决策

```text
UWTP_Protocol_v0.2.md             Core/域/注册表
S7_UWTP_Profile_v0.2.md           GATT/数据源/产品约束
UWTP_v0.2_Implementation_Plan.md  代码阶段/内存/测试
UWTP_v0.2_Design_Decision_Log.md  为什么
```

---

# 18. 仍未解决的问题

v0.2 有意不提前锁死：

1. LIVE_CONFIG 详细字段；
2. RAW_CHUNK 的 24bit/32bit 表达；
3. ACC、AGC、timestamp 可选布局；
4. RESULT_BUNDLE 的 TLV/Protobuf 组合；
5. STATE_QUERY 是 Core 状态还是 S7 扩展；
6. OBJECT_LIST 分页与对象描述；
7. TRANSFER_BEGIN 最终字段；
8. ACK 只返回 next_offset 还是同时 credit；
9. BEST_EFFORT 缺口描述方式；
10. SEG_MODE 非零语义；
11. CTRL/HELLO 是否使用 registry_hash；
12. 高危命令安全边界；
13. 断连后各 object_type 恢复规则；
14. v0.2 registry 最终 hash。

这些必须逐条评审，不应由实现者在代码里自行猜测。

---

# 19. 可反哺其他协议的原则

## 19.1 先定义角色，再设计命令

先回答：

```text
谁控制谁？
谁主动发起业务？
谁只提供数据？
```

再决定请求、响应和事件。

## 19.2 先定义资源模型，再写错误码

设备资源本质串行，就明确单排他操作，不让各 handler 自行猜测并发。

## 19.3 小消息分段与大对象传输分层

```text
SEG_MODE = 逻辑消息重组
offset   = 大对象进度
```

不要形成重复分包。

## 19.4 控制面可牺牲少量带宽换确定性

BEGIN/STOP/ABORT/FINISH 的明确响应，可大幅降低状态失步成本。

## 19.5 版本演进稳定编号，不冻结所有未来功能

冻结：

```text
包头
编号
已有语义
解析规则
```

未来消息按需追加。

## 19.6 状态查询是恢复工具

正常命令仍应响应；状态查询用于重连、超时和异常重新同步。

---

# 20. 当前决策索引

| ID | 决策 |
|---|---|
| D-001 | Collect Link 合并回 UWTP，不再独立演进 |
| D-002 | UWTP 与 S7 Profile 统一为 v0.2-draft |
| D-003 | Core Header 保持 5B，header_ver=1 |
| D-004 | main_type 按事务域定义 |
| D-005 | sub_type.bit7 为 IS_RESPONSE，bit6..0 为 opcode |
| D-006 | 响应回显同 seq，事务键为 main+opcode+seq |
| D-007 | App 是唯一业务 Controller |
| D-008 | 所有 App 业务命令必须响应 |
| D-009 | DATA/ACK/EVENT 不形成普通业务响应 |
| D-010 | LIVE 采用 CONFIG/START/STOP + CONTEXT/RAW/RESULT/EVENT |
| D-011 | FILE/OTA/资源统一为 TRANSFER |
| D-012 | BEGIN 协商 direction、chunk、ack_every_n、reliability |
| D-013 | 可靠性分 BEST_EFFORT/RESUMABLE/VERIFIED |
| D-014 | 同一时刻最多一个 active transfer |
| D-015 | transfer_id 是事务世代号 |
| D-016 | 顶层 OperationState 采用单排他模型 |
| D-017 | BUSY 表示其他业务占用，INVALID_STATE 表示域内顺序错误 |
| D-018 | STOP_RSP/ABORT_RSP 是真正静止后的屏障 |
| D-019 | SEG_MODE bit 保留，完整语义按真实需求补齐 |
| D-020 | 已分配编号只增不改，废弃不复用 |
