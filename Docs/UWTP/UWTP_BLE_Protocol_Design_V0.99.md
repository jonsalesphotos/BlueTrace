# UWTP 统一可穿戴传输协议 · 设计 V0.99

> **UWTP = Unified Wearable Transport Protocol**。前身 [`UHTP_BLE_Protocol_Design_V4.md`](UHTP_BLE_Protocol_Design_V4.md)（UHTP V4）——骨架（5B 小头 + 事务域状态机 + Protobuf 协商 + Report TLV + offset 大对象传输）原样继承；本版补齐 V4 缺失的 Core 章节（GATT 绑定、时间模型、断连语义、并发矩阵、安全姿态、字节序），并按 2026-07-06 审议拍板收敛全部含糊点（决策表见 §0.2）。
> **改名理由**：协议里真正"health"的只有 ALGO 结果一个域，OTA/LOG/FILE/CTRL 全是设备工程能力——按设备形态（Wearable）命名比按数据领域（Health）更贴合。
> **状态**：**V0.99 = 冻结候选**。双端按本文档共识开发（**静态注册表制，不做动态能力解析**）；联调通过后定稿为 V1.0，线上 `protocol_version = 1`。
> 机器可读契约：[`uwtp_v0.99_draft.proto`](uwtp_v0.99_draft.proto)；示例帧全部脚本实算（[`assets/gen_uwtp_examples.py`](assets/gen_uwtp_examples.py)，protobuf wire + CRC32，len 自洽断言）。
> S7 采集剖面：现有 [`../architecture/s7/protocol-zqdata-uhtp-v1.md`](../architecture/s7/protocol-zqdata-uhtp-v1.md) 将按本文改写为「S7 采集 Profile」（§22）。

---

## 0. 总览

### 0.1 一句话

> **5B 头管分流，main_type 管事务域状态机，sub_type 管域内动作，seq 管顺序与响应回显，SEG_MODE 管小消息重组，offset 管大对象进度，Protobuf 管控制语义，TLV 容器管结果打包——控制无生命周期，传输各域自带丢包容忍度与完整性校验，OTA 独占，一切以协议文档静态注册表为共识。**

### 0.2 本版决策记录（2026-07-06 审议，D-1 ~ D-14）

| # | 决策 | 内容 |
| --- | --- | --- |
| D-1 | 命名 | UHTP → **UWTP**；文档版本 V0.99（冻结后 1.0），线上 `protocol_version=1` |
| D-2 | 时间格式 | 完整时间戳 = **u32 unix 秒 + u16 毫秒 + s16 时区分钟**；时区只出现在 TIME 域 |
| D-3 | 数据面时间 | 数据不重复传秒级与时区——**秒级锚点只写在"头"里**（文件头/bundle 头），锚点来自 App 的秒级时间戳；样本/记录只带 ms 偏移 |
| D-4 | 会话生命周期 | **控制类不做生命周期管理**（无状态请求/响应）；传输域各自定义断连语义（§6.2） |
| D-5 | OTA 断连 | 状态随断连结束；**重连重走协商、从 flash 水位断点续传**；升级必经 reset + confirm |
| D-6 | FILE 断连 | 离线数据与日志**断连即作废、重传从头**，不做断点续传 |
| D-7 | 在线断连 | TUNNEL/ALGO 推流**不缓存，丢了就丢**；重连后 App 核对在线会话一致性，不一致即发结束 |
| D-8 | 文件目录 | **多文件名 + LIST 获取**（S7 存储空间足够存多份文件）；**路径为协议隐含约定**——协议只传相对文件名，根目录/命名规则由固件与 App 基于文档共识写死 |
| D-9 | OTA 参照 | 固件 layout 自研锁定，但**语义对齐 Zephyr MCUmgr/SMP img_mgmt**（槽位/状态位/test-confirm-rollback/断点续传，§14.6 映射表） |
| D-10 | 心跳 | **不做**。BLE 链路层 connection supervision timeout 已负责断链检测；业务活性靠请求超时兜底。编号保留 |
| D-11 | 字节序 | **固定二进制字段一律小端**（铁律，写进 Core） |
| D-12 | 能力发现 | **不做动态能力协商/解析**。HELLO 只交换版本与身份；main/sub/kind/algo_id/format 全部为**文档静态注册表**，双端硬编码 |
| D-13 | 安全 | Just Works；不做应用层全加密；SECURITY 保留"连接起始一次性鉴权"位置（后续配对时启用，暂不做） |
| D-14 | 并发 | **OTA 最高优先级、独占**：进入即停止采集/离线传输/日志导出等一切文件类传输；CTRL 查询（电量等）保持可用 |

### 0.3 六域总览

<div class="fig">
<svg viewBox="0 0 840 450" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>UWTP V0.99 六域地图：每域的方向、可靠性策略与完整性校验</title>
<desc>顶部为 5B 帧头与 GATT 绑定，中部六个事务域方块（CTRL/ALGO/LOG/OTA/FILE/TUNNEL），每块标注方向、丢包容忍度与完整性校验方式，底部为并发规则一行。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.bxd{fill:var(--code);stroke:var(--muted);stroke-width:1;stroke-dasharray:4 3;}
</style>
<text x="40" y="22" class="t">UWTP V0.99（前身 UHTP V4）—— GATT: 1 Service + RX(WriteNoRsp) + TX(Notify)；S7 复用 ZQDATA 45121540/41/42</text>
<rect x="40" y="34" width="760" height="34" class="bxa"/>
<text x="420" y="56" text-anchor="middle" class="m a">5B 头：ver_flags | main_type | sub_type | seq | len　·　固定字段全小端　·　NEED_RSP 回显 seq　·　响应 Protobuf 首字段恒 status</text>
<g class="m">
  <rect x="40" y="86" width="243" height="96" class="bx"/>
  <text x="161" y="106" text-anchor="middle" class="a">0x01 CTRL（无状态，恒可用）</text>
  <text x="161" y="124" text-anchor="middle" class="ms">SESSION·TIME·USER_PROFILE</text>
  <text x="161" y="140" text-anchor="middle" class="ms">ALGO_CTRL·STORAGE</text>
  <text x="161" y="158" text-anchor="middle" class="ms">请求/响应, 单飞, 无生命周期</text>
  <text x="161" y="174" text-anchor="middle" class="ms">心跳不做(BLE 链路监督兜底)</text>
  <rect x="299" y="86" width="243" height="96" class="bx"/>
  <text x="420" y="106" text-anchor="middle" class="a">0x02 ALGO（表→App 推流）</text>
  <text x="420" y="124" text-anchor="middle" class="ms">REPORT_TLV：可丢, 不 ACK</text>
  <text x="420" y="140" text-anchor="middle" class="ms">bundle_seq 缺口只做统计</text>
  <text x="420" y="158" text-anchor="middle" class="ms">开关=ALGO_CTRL, 断连自动全关</text>
  <text x="420" y="174" text-anchor="middle" class="ms">RAW_STREAM 保留不启用</text>
  <rect x="558" y="86" width="242" height="96" class="bx"/>
  <text x="679" y="106" text-anchor="middle" class="a">0x06 TUNNEL（双向透传）</text>
  <text x="679" y="124" text-anchor="middle" class="ms">厂商字节原样, 设备不解释</text>
  <text x="679" y="140" text-anchor="middle" class="ms">可丢：不缓存, 离线即丢失</text>
  <text x="679" y="158" text-anchor="middle" class="ms">重连后 App 核对在线会话,</text>
  <text x="679" y="174" text-anchor="middle" class="ms">不一致即发结束</text>
</g>
<g class="m">
  <rect x="40" y="198" width="243" height="110" class="bxa"/>
  <text x="161" y="218" text-anchor="middle" class="a">0x04 OTA（App→表，独占）</text>
  <text x="161" y="236" text-anchor="middle" class="ms">零容忍：窗口 ACK + image_hash</text>
  <text x="161" y="252" text-anchor="middle" class="ms">+ bootloader 签名验证</text>
  <text x="161" y="268" text-anchor="middle" class="ms">断连=状态结束, 重协商续断点</text>
  <text x="161" y="284" text-anchor="middle" class="ms">test→reset→confirm→rollback</text>
  <text x="161" y="300" text-anchor="middle" class="ms">语义对齐 MCUmgr/SMP img_mgmt</text>
  <rect x="299" y="198" width="243" height="110" class="bxa"/>
  <text x="420" y="218" text-anchor="middle" class="a">0x05 FILE（表→App，多文件名）</text>
  <text x="420" y="236" text-anchor="middle" class="ms">LIST/READ/DELETE, 路径隐含约定</text>
  <text x="420" y="252" text-anchor="middle" class="ms">链路可靠 + 整档 CRC32</text>
  <text x="420" y="268" text-anchor="middle" class="ms">断连=作废, 重传从头(不续传)</text>
  <text x="420" y="284" text-anchor="middle" class="ms">离线数据·日志导出 都走这里</text>
  <text x="420" y="300" text-anchor="middle" class="ms">(LOG 域导出编号保留弃用)</text>
  <rect x="558" y="198" width="242" height="110" class="bxd"/>
  <text x="679" y="218" text-anchor="middle" class="s" font-weight="700">0x03 LOG / 0xF0 VENDOR / SECURITY</text>
  <text x="679" y="236" text-anchor="middle" class="ms">LIVE_EVENT 可丢(V1 可选)</text>
  <text x="679" y="252" text-anchor="middle" class="ms">日志文件导出→FILE 域</text>
  <text x="679" y="268" text-anchor="middle" class="ms">SECURITY: Just Works,</text>
  <text x="679" y="284" text-anchor="middle" class="ms">连接起始一次鉴权(留位不做)</text>
  <text x="679" y="300" text-anchor="middle" class="ms">能力发现: 文档静态注册表制</text>
</g>
<rect x="40" y="326" width="760" height="34" class="bx"/>
<text x="420" y="348" text-anchor="middle" class="m">并发铁律：CTRL 恒可用　＞　<tspan class="a">OTA 独占（停一切文件类传输与采集）</tspan>　＞　FILE 单传输　＞　TUNNEL/ALGO 让位</text>
<g class="s">
  <text x="40" y="384">时间模型：设备维护 UTC 系统时钟；TIME/SET = u32 unix 秒 + u16 ms + s16 时区分钟（时区只出现在 TIME 域）；</text>
  <text x="40" y="402">数据面只带 ms 偏移——秒级锚点只写在"头"里（文件头/bundle 头），锚点来自 App 的秒级时间戳。</text>
  <text x="40" y="420">断连语义：控制类无状态；OTA 重协商续断点；FILE 从头重传；在线（TUNNEL/ALGO）丢了就丢。</text>
  <text x="40" y="438">legacy 共存：0xBB(B2A) 与 0x1?(UWTP) 首字节不相交，同特征双栈分流；HELLO 探测回落。</text>
</g>
</svg>
</div>

---

# Part I · Core

## 1. 定位与传输假设

面向 BLE Notify / Write 的可穿戴设备统一传输协议。传输假设（继承 V4）：

```
ATT MTU 目标 247 → ATT Value ≈ 244B；本文取保守值 243
UWTP 帧上限 = min(协商 ATT value, 243)；Header 5B → payload ≤ 238
运行时：max_payload = negotiated_att_value - 5
```

BLE 已提供逐包边界、长度、链路层 CRC 与按序可靠投递 → **帧头不放 magic、不做逐帧 CRC、len 用 u8**。

## 2. GATT 绑定

**Core 规定绑定形状，UUID 归产品族分配**：

| 项 | 规定 |
| --- | --- |
| 服务 | 1 个 Primary Service |
| RX | 1 特征，**Write Without Response**（App→表） |
| TX | 1 特征，**Notify + CCC**（表→App） |
| 连接 | 单连接假设；连接建立后 App/表任一方发起 MTU 协商至 247 |
| 连接参数 | 进入传输类域（OTA / FILE 读）→ 设备切**快速连接参数**；READ_END/UPLOAD_END/ABORT 后恢复省电参数 |
| 订阅 | App 使能 TX CCC 后协议才可用；HELLO 为首个交互 |

**产品绑定表**：

| 产品 | Service / RX / TX | 备注 |
| --- | --- | --- |
| **S7（现有项目）** | `45121540 / 45121541 / 45121542-51F2-406E-927A-3E1E183412E0` | **复用现网 ZQDATA 服务特征**；与 legacy B2A 帧同特征双栈共存（§11.2） |
| 其他项目 | 【占位】 | 由嵌入式规则与产品规划决定 |

## 3. 帧头（5B，与 UHTP V4 二进制兼容）

| Offset | Size | Field | Description |
| --- | --- | --- | --- |
| 0 | 1B | `ver_flags` | bit7-4 `header_ver`=1 · bit3 `EXT_HDR`(恒 0) · bit2 `NEED_RSP` · bit1-0 `SEG_MODE` |
| 1 | 1B | `main_type` | 事务域（§20 注册表） |
| 2 | 1B | `sub_type` | 域内动作 |
| 3 | 1B | `seq` | 本方向递增回绕；分段帧连续；**响应回显请求 seq** |
| 4 | 1B | `len` | payload 长度；强校验 `实长 == 5 + len` |

```
ver_flags bit7 → bit0：
   +------+------+------+------+---------+----------+---------+---------+
   |     header_ver = 0001     | EXT_HDR | NEED_RSP |     SEG_MODE      |
   |                           |  恒 0   |          | 00单包 01起 10中 11止 |
   +------+------+------+------+---------+----------+---------+---------+
首字节速查：0x10 单包 · 0x14 单包+要响应 · 0x11/0x12/0x13 分段起/中/止
```

- **SEG_MODE** 只管"小消息重组"（大 HELLO_RSP、大 LIST 页）；**offset** 管大对象；两者不混用。高吞吐路径（OTA/FILE DATA、TUNNEL）**禁分段**。
- **（D-11 铁律）所有固定二进制字段一律小端**；Protobuf 自带编码。

## 4. 响应模型（铁律）

1. `NEED_RSP=1` 的请求 → 对端以**同 main/sub + 回显 seq** 响应；
2. **响应 payload 若为 Protobuf，首字段恒 `uint32 status = 1`**（0=OK，proto3 缺省不编码 → 成功且无数据时 payload 为空）；固定二进制响应的 status 位置由各消息显式定义；
3. **单飞**：同方向同时最多一个未决请求；数据面帧（DATA/ACK/REPORT/TUNNEL/LIVE_EVENT）**不是请求、不占单飞额度**；
4. App 侧请求超时建议 3s（联调标定）；超时后可重发（seq 递增）；
5. 错误响应 = `CommonRsp{status}`（§10 状态码）。

## 5. 时间模型（D-2/D-3）

- 设备维护 **UTC 系统时钟**（unix 秒 + 毫秒）；App 连接后经 `CTRL/TIME SET` 校时。
- **完整时间戳（T-full8，仅 TIME 域）**：`u32 unix_sec | u16 ms | s16 tz_offset_min`——时区只在这里出现一次，供设备本地显示，协议其余部分一律 UTC。
- **头部锚点（T-head6）**：`u32 unix_sec | u16 ms`——只出现在数据容器的"头"（离线文件头、REPORT bundle 头、文件 mtime 等）。
- **数据面（T-rel）**：样本/记录只带**相对锚点的 ms 偏移**（u32 或更窄的 delta，由各消息定义）——不重复传秒级与时区。锚点秒级时间来源于 App（校时后的设备钟即 App 时间）。

```
TIME SET ──> 设备钟 = App 钟（UTC）
离线文件:  [文件头: anchor = u32 sec + u16 ms] + 帧[u32 rel_ms]...
在线上报:  [bundle 头: u32 sec + u16 ms] + record[u8 delta ×100ms]...
绝对时间:  utc_ms = anchor + rel
```

## 6. 会话与断连语义（D-4~D-7）

### 6.1 会话

- **HELLO 最小化**：交换 `protocol_version` + 固件版本 + 序列号，一轮完成；版本不匹配 → `NOT_SUPPORTED`，App 决定回落或升级提示。
- **无动态能力发现（D-12）**：支持哪些域/子命令/格式/算法，**以本文档注册表为共识**，双端硬编码；文档改版即协议改版（`protocol_version` 递增）。
- 无 BYE 强制要求（断连即会话终）；控制类命令随连随发，无状态。

### 6.2 断连语义表（各传输域自治）

| 域 | 断连时 | 重连后 |
| --- | --- | --- |
| CTRL | 未决请求作废 | 直接重发，无需恢复 |
| **OTA** | 传输状态结束；**已写 flash 的数据保留** | **重走 UPLOAD_BEGIN 协商**，设备在 READY 回 `next_offset`（flash 水位）→ 从断点续传；reset/confirm 流程不变 |
| **FILE** | 传输作废 | **重新 READ_BEGIN 从头传**（D-6，不做断点续传；若未来文件大到疼，再以可选特性加回） |
| ALGO 推流 | 停止；不缓存、丢了就丢 | App 重新 ALGO_CTRL 使能（断连自动全关） |
| TUNNEL | 不缓存、丢了就丢 | App 核对在线会话是否与断前一致；**不一致 → App 发结束命令，会话终止**（D-7） |

## 7. 并发矩阵（D-14）

| 进行中 ↓ \ 想做 → | CTRL | ALGO 推流 | FILE 读 | OTA | TUNNEL |
| --- | --- | --- | --- | --- | --- |
| 空闲 | ✅ | ✅ | ✅ | ✅ | ✅ |
| FILE 读 | ✅ | ⚠ 让位（设备可丢/降频） | ❌ BUSY（单传输） | ✅ 抢占（FILE 被 ABORT） | ⚠ 上行让位 |
| **OTA** | ✅（查电量等） | ❌ 停止 | ❌ 停止 | ❌ BUSY | ❌ 停止 |
| TUNNEL 活跃 | ✅ | ✅ | ✅（TUNNEL 让位） | ✅ 抢占 | — |

铁律：**CTRL 恒可用 ＞ OTA 独占 ＞ FILE 单传输 ＞ TUNNEL/ALGO 让位**。OTA 进入 PREPARED 即执行独占（设备停采集、停一切文件类传输），退出（END/ABORT/reset）后恢复。

## 8. 可靠性与完整性分层（每域自带容忍度）

| 域 / 数据 | 方向 | 丢包容忍 | 逐帧确认 | 完整性校验 | 断连恢复 |
| --- | --- | --- | --- | --- | --- |
| CTRL 请求/响应 | 双向 | 不容忍 | NEED_RSP + 超时重发 | Protobuf 解析 + status | 重发 |
| **OTA 镜像** | App→表 | **零容忍** | **窗口 ACK**（next_offset） | window ACK 连续性 + **image_hash** + **bootloader 签名** | 断点续传（§6.2） |
| **FILE（离线数据/日志导出）** | 表→App | 零容忍（链路可靠保证） | 窗口 ACK（流控+进度） | offset 连续性 + **整档 CRC32(IEEE)** | 从头重传 |
| ALGO REPORT | 表→App | **可丢** | 无 | `bundle_seq` 缺口仅统计 | 无 |
| LOG LIVE_EVENT | 表→App | **可丢** | 无 | 无 | 无 |
| TUNNEL | 双向 | **可丢**（按厂商协议自理） | 无 | 厂商协议自带 | App 核对会话一致性 |

> BLE 链路层对空口位错已有 CRC 且按序可靠——协议层校验解决的是**端到端**问题（flash 写坏、固件逻辑错、截断），不是空口丢包。

## 9. 安全姿态（D-13）

- 配对：**Just Works**（内部采集/工程工具定位）；
- **不做应用层全加密**（太重）；
- 预留：`CTRL/SECURITY`（sub `0x08`）用于**连接起始一次性鉴权**（challenge-response 一轮），后续做正式配对时启用——**V0.99 不实现**；
- 高危命令（OTA 写入、DELETE、恢复出厂类）在无鉴权期依赖"物理接近 + Just Works 配对"这一现实边界，文档明示此假设。

## 10. 状态码（继承 V4 全表）

`0x00 OK · 0x01 INVALID_REQUEST · 0x02 NOT_SUPPORTED · 0x03 INVALID_STATE · 0x04 BUSY · 0x05 INVALID_PARAM · 0x06 NO_MEMORY · 0x07 NO_SPACE · 0x08 CRC_ERROR · 0x09 HASH_MISMATCH · 0x0A AUTH_FAILED · 0x0B TIMEOUT · 0x0C RETRY_LATER · 0x0D SERVICE_REJECTED · 0x0E UNSUPPORTED_SCHEMA · 0x0F UNSUPPORTED_FORMAT · 0x10 TOO_LARGE`

## 11. 版本管理与 legacy 共存

### 11.1 分层版本

`header_ver`（每帧，只管头解析）→ `protocol_version`（HELLO，本文档整体，V1.0 冻结 = 1）→ 静态注册表版本（随文档走，无线上协商）。proto3 演进只增不改 tag。

### 11.2 与 S7 legacy（B2A）共存

legacy B2A 帧首字节恒 `0xBB`；UWTP 帧首字节高半字节恒 `0x1`——**完全不相交**。固件同特征双栈：RX 首字节 `0xBB` → 旧 B2A 解析器，`0x1?` → UWTP，其余丢弃计数。App 侧 HELLO 1s×2 超时 → 判定 legacy 固件回落旧协议。

---

# Part II · Domains

## 12. CTRL 域（`main_type 0x01`，无状态、恒可用）

| sub | 名称 | V0.99 | 说明 |
| --- | --- | --- | --- |
| `0x01` | SESSION | ✅ | HELLO / BYE（可选） |
| `0x02` | DEVICE | ⏸ 保留 | 设备信息扩展位 |
| `0x03` | SENSOR_CTRL | ⏸ 保留 | 在线由 TUNNEL 透传，不启用 |
| `0x04` | ALGO_CTRL | ✅ | 算法结果上传开关 |
| `0x05` | **TIME** | ✅ | SET / GET，固定二进制 |
| `0x06` | HEARTBEAT | ⏸ 保留 | **不做**（D-10，BLE 链路监督兜底） |
| `0x07` | STORAGE | ✅ | 存储占用查询 |
| `0x08` | SECURITY | ⏸ 保留 | 连接起始一次性鉴权（暂不做） |
| `0x09` | USER_PROFILE | ✅ | 个人信息写/读 |

### 12.1 SESSION（HELLO 最小化）

```proto
message Hello    { uint32 protocol_version = 1; string app_version = 2; }        // App→表
message HelloRsp { uint32 status = 1; uint32 protocol_version = 2;
                   string firmware_version = 3; string device_sn = 4; }          // 表→App
```

### 12.2 TIME（固定二进制 8B，双向同布局）

```
SET (App→表, NEED_RSP):  [u32 unix_sec][u16 ms][s16 tz_offset_min]   → CommonRsp
GET (App→表, NEED_RSP):  空 payload                                  → 同 8B 布局回读
```

### 12.3 USER_PROFILE

写：带字段发送（`height_cm / weight_kg_x10 / sex / birth_y/m/d / wear_hand`，Protobuf）→ 落 NVM，回 `CommonRsp`。读：空 payload + NEED_RSP → `UserProfileRsp{status, profile}`。

### 12.4 ALGO_CTRL

`AlgoReportCtrl{ enable_ids[](packed), min_interval_ms }` → `AlgoReportCtrlRsp{ status, active_ids[] }`（实际生效集合回读对账）。空集 = 全关；断连自动全关。`algo_id` 为文档静态注册表（§20）。

### 12.5 STORAGE

`StorageInfoReq{}` → `StorageInfo{ status, total_bytes, used_bytes, file_count }`。

## 13. ALGO 域（`main_type 0x02`）

| sub | 名称 | V0.99 |
| --- | --- | --- |
| `0x01` | RAW_STREAM | ⏸ 保留（在线原始流走 TUNNEL） |
| `0x02` | REPORT_TLV | ✅ |

**REPORT_TLV**（表→App 推流，可丢不 ACK）：

```
bundle 头 (8B):  [bundle_seq u8][unix_sec u32][ms u16][record_count u8]   ← T-head6 锚点
record (4B+N):   [algo_id u8][time_delta u8 ×100ms][flags u8][len u8][value: 该算法 Protobuf]
flags: bit0 VALID · bit1 NEW_RESULT · bit2 WARNING · bit3 ERROR · bit4 HAS_EXT
打包判断: 8 + Σ(4+len) ≤ 238；单 record 超限 → SEG_MODE 或精简 schema
```

`algo_id → 结果 message` 映射 = 静态注册表（§20.3），示例 schema：`HrResultV1 / Spo2ResultV1 / WearResultV1`（见 proto 草案）。

## 14. OTA 域（`main_type 0x04`，独占，语义对齐 MCUmgr/SMP）

| sub | 名称 | | sub | 名称 |
| --- | --- | --- | --- | --- |
| `0x01` | IMG_LIST | | `0x05` | UPLOAD_ACK |
| `0x02` | UPLOAD_BEGIN | | `0x06` | UPLOAD_END |
| `0x03` | UPLOAD_READY | | `0x07` | IMG_STATE |
| `0x04` | UPLOAD_DATA | | `0x08` | RESET · `0x09` ABORT |

### 14.1 分层与状态机

```
Object=整个镜像  Window=一次确认的 chunk 组  Chunk=一次传输块  Frame=一个 UWTP 包

IDLE ──IMG_LIST──▶ LISTED ──UPLOAD_BEGIN/READY──▶ TRANSFERRING（独占开始）
  ▲                                                   │ UPLOAD_END + hash 校验
  │◀── 断连（状态结束, flash 保留）────────────────────┤
  │                                                   ▼
  └── RESET ◀── IMG_STATE(TEST) ◀── VERIFIED     [失败→ERROR→ABORT]
        │ bootloader 试启动新镜像
        ▼
   新固件自检 → IMG_STATE(CONFIRM)；未 confirm 下次复位自动回滚
```

### 14.2 IMG_LIST（Protobuf）

`OtaImgListRsp{ status, slots[] }`；`OtaSlot{ slot, version, state_flags }`，`state_flags`：bit0 BOOTABLE · bit1 PENDING · bit2 CONFIRMED · bit3 ACTIVE · bit4 PERMANENT（**与 MCUmgr image state flags 一一对应**）。

### 14.3 UPLOAD_BEGIN / READY（Protobuf 协商，App 提议、设备定版）

```proto
message OtaUploadBegin { uint32 image_size = 1; bytes image_hash = 2; string version = 3;
                         uint32 target_slot = 4; uint32 preferred_chunk = 5; uint32 preferred_window = 6; }
message OtaUploadReady { uint32 status = 1; uint32 transfer_id = 2; uint32 chunk_bytes = 3;
                         uint32 window_chunks = 4; uint32 next_offset = 5; }
```

`next_offset`：全新传输 = 0；**断连重来 = 设备已落 flash 的连续水位**（同一 image_hash 才允许续，否则从 0）——即 SMP upload 响应 `off` 字段的语义。

### 14.4 UPLOAD_DATA / ACK（固定二进制）

```
DATA (App→表):  [transfer_id u8][offset u32][data ≤232B]
ACK  (表→App):  [transfer_id u8][next_offset u32][status u8]   ← 每 window_chunks 片一次
```

`next_offset` 之前已连续写入 flash；status=CRC_ERROR/NO_SPACE 等时 App 回退到 next_offset 重发。**transfer_id 定为 u8**：并发矩阵保证同时只有一个传输在跑，u8 足够且省 3B/帧（对 V4 的 u32 收窄，记录在案）。

### 14.5 UPLOAD_END / IMG_STATE / RESET

END（App→表，NEED_RSP）→ 设备整镜像 hash 校验 → `CommonRsp`；`IMG_STATE{op: TEST|CONFIRM, slot}`；`RESET{}`。UWTP 只管**协商/传输/续传/窗口 ACK/hash/状态切换请求**；镜像格式解析、签名验证、slot 切换、test boot、confirm、rollback 归 **bootloader**（分工同 V4 §13.8）。

### 14.6 与 MCUmgr/SMP img_mgmt 映射（D-9）

| UWTP | MCUmgr/SMP | 备注 |
| --- | --- | --- |
| IMG_LIST | image state read | 槽位+版本+hash+flags |
| UPLOAD_BEGIN(size+hash) / READY(next_offset) | image upload 首请求（len+sha）/ 响应 `off` | 语义等价；UWTP 多了 chunk/window 协商 |
| UPLOAD_DATA{off,data} + 窗口 ACK | upload 请求/响应（逐请求 ACK） | **差异**：SMP 逐 chunk 应答，UWTP 用窗口 ACK 换吞吐（WriteNoRsp 连发）|
| IMG_STATE(TEST/CONFIRM) | image state write (test/confirm) | 一致 |
| RESET | os mgmt reset | 一致 |
| 未 confirm 回滚 | MCUboot revert | 一致 |

> 固件 layout 虽自研锁定，按此映射实现可与 MCUboot 式双槽管理保持概念一致，后续迁移 Zephyr 生态零概念冲突。

## 15. FILE 域（`main_type 0x05`，多文件名，表→App）

| sub | 名称 | | sub | 名称 |
| --- | --- | --- | --- | --- |
| `0x01` | LIST | | `0x05` | READ_ACK |
| `0x02` | READ_BEGIN | | `0x06` | READ_END |
| `0x03` | READ_READY | | `0x07` | ABORT |
| `0x04` | READ_DATA | | `0x08` | DELETE |

### 15.1 路径隐含约定（D-8）

协议只传**相对文件名**（如 `dc/ecg_0706_1600.uof`、`log/eiotlog.log`）；根目录、目录结构、命名规则是**固件与 App 的文档级共识**（Profile 章节写死），协议本体不解释路径语义。`kind` 枚举用于 LIST 过滤与 App 分流：`0 不过滤 · 1 LOG · 2 CONFIG(保留) · 3 DEBUG_DUMP(保留) · 4 OFFLINE_DATA`。

### 15.2 LIST（Protobuf，大页允许 SEG_MODE）

```proto
message FileListReq { uint32 kind = 1; uint32 page = 2; uint32 page_size = 3; }
message FileListRsp { uint32 status = 1; uint32 total = 2; uint32 page = 3; repeated FileEntry entries = 4; }
message FileEntry   { string name = 1; uint32 size = 2; uint32 mtime_sec = 3; uint32 kind = 4; }
```

### 15.3 READ 传输（断连从头，D-6）

```proto
message ReadBegin { string name = 1; uint32 preferred_chunk = 2; uint32 preferred_window = 3; }
message ReadReady { uint32 status = 1; uint32 transfer_id = 2; uint32 total_bytes = 3;
                    uint32 chunk_bytes = 4; uint32 window_chunks = 5; uint32 file_crc32 = 6; }
```

```
DATA (表→App):  [transfer_id u8][offset u32][data ≤232B]      ← 固定二进制, 禁分段
ACK  (App→表):  [transfer_id u8][next_offset u32][status u8]   ← 每 window 一次滑动授信
END  (表→App):  ReadEnd{ status, transfer_id, sent_bytes }
```

- 窗口 ACK 职责 = **流控 + 进度**（链路层已可靠；不做重传语义）；表最多领先未 ACK 一个窗口；
- App 收完全量 → **CRC32 与 READY.file_crc32 比对**；不符 → 重新 READ_BEGIN 整档重拉（文件仍在）；
- **DELETE{name}**（NEED_RSP）：App 校验通过且确认后显式删除；读传输中拒删（BUSY）。传输本身**永不隐式删数据**。

## 16. LOG 域（`main_type 0x03`）

- `0x01 LIVE_EVENT`：实时日志推流，**可丢不 ACK**（V0.99 可选实现）；二进制 `[rel_ms u32][level u8][module u8][event u16][args...]`，事件字典 = 文档静态注册表。
- `0x02–0x09`（V4 的 EXPORT_* 组）：**编号保留、弃用**——日志文件导出统一走 **FILE 域**（日志就是固定名字的文件，D-8 后无需第二套传输）。

## 17. TUNNEL 域（`main_type 0x06`，本版新增）

`sub_type = tunnel_id`（静态注册表：`0x01 GH3220_EVK`）。payload = 厂商协议字节**原样透传**，设备与 UWTP 均不解释；消息边界不保证（字节流语义，厂商协议自带帧同步）；可丢（§8）；FILE/OTA 传输期间上行让位。在线测量的启停/参数/数据全部由隧道两端厂商协议自理——**UWTP 不给在线路径发明第二套语义**。

## 18. VENDOR（`main_type 0xF0`）

厂商/实验保留。启用前须在文档注册表登记，避免冲突。

---

# Part III · 附录

## 19. Example raw packet + decode（脚本实算）

```
E1  TIME SET (13B):  14 01 05 21 08 | 80 60 4B 6A | 00 00 | E0 01
    sec=1783324800(2026-07-06 08:00Z) ms=0 tz=+480 → rsp: 10 01 05 21 00 (status=0 空)

E2  HELLO (22B):     14 01 01 22 11 | 08 01 | 12 0D "bluetrace-1.0"
    RSP (30B):       10 01 01 22 19 | 10 01 | 1A 0B "s7-fw-1.2.0" | 22 08 "S7A1B2C3"

E3  FILE/LIST req (7B):  14 05 01 40 02 | 18 10                       page_size=16
    rsp (74B):  10 05 01 40 45 | total=2 | entry{name="dc/ecg_0706_1600.uof",
                size=460800, mtime, kind=4} | entry{name="log/eiotlog.log", size=131072, kind=1}

E4  READ_BEGIN (27B): 14 05 02 41 16 | name="log/eiotlog.log" | chunk=230 | win=16
    READ_READY (22B): 10 05 03 41 11 | tid=2 total=131072 chunk=230 win=16 crc32=0xA56BF2B7
    READ_DATA#0(240B): 10 05 04 00 EB | 02 | 00 00 00 00 | data 230B     ← tid u8 + off u32
    READ_ACK (11B):    10 05 05 50 06 | 02 | 60 0E 00 00 | 00            ← next=3680, OK

E5  OTA IMG_LIST rsp (24B): 10 04 01 30 13 | slot0{v"1.2.0", flags=0x0D
        =BOOTABLE|CONFIRMED|ACTIVE} | slot1{flags=0x00 空}
    UPLOAD_BEGIN (29B): 14 04 02 31 18 | size=262144 | hash | v"1.3.0" | slot=1 | chunk=224 | win=32
    UPLOAD_READY (14B): 10 04 03 31 09 | tid=7 chunk=224 win=32 next_offset=0
    UPLOAD_DATA#0 (234B): 10 04 04 00 E5 | 07 | 00 00 00 00 | data 224B
    UPLOAD_ACK (11B):     10 04 05 60 06 | 07 | 00 1C 00 00 | 00          ← next=7168(32 片)

E6  REPORT_TLV (36B): 10 02 02 36 1F | 05 | A0 7C 4B 6A | FA 00 | 02 |
        rec{algo=1(HR) dt=0 flags=03 HrResultV1{72,90,80}} |
        rec{algo=2(SpO2) dt=10(1s) flags=03 Spo2ResultV1{98,72,85,pi1500}}
        bundle 锚点 = sec+7200, 250ms（T-head6）
```

## 20. 静态注册表汇总（D-12：文档即共识，双端硬编码）

**20.1 main_type**：`0x01 CTRL · 0x02 ALGO · 0x03 LOG · 0x04 OTA · 0x05 FILE · 0x06 TUNNEL · 0xF0 VENDOR`（sub 表见各域章节）。

**20.2 FILE kind**：`0 不过滤 · 1 LOG · 2 CONFIG⏸ · 3 DEBUG_DUMP⏸ · 4 OFFLINE_DATA`。

**20.3 algo_id**（Profile 填充）：`1 HR → HrResultV1 · 2 SPO2 → Spo2ResultV1 · 3 WEAR → WearResultV1 · 【S7 Profile 增补】`。

**20.4 tunnel_id**：`0x01 GH3220_EVK`。

**20.5 离线文件内容格式**（FILE 传输对内容不透明，App 按扩展名/Profile 约定选解析器）：`.uof = UOF1 统一离线格式（推荐）· 现网 ECG1_V2 / PPGHR40 / GS18 兼容期保留`。

## 21. 相对 UHTP V4 的差异清单

| 项 | V4 | V0.99 |
| --- | --- | --- |
| 名称 | UHTP | **UWTP**（D-1） |
| GATT 绑定 | 未定义 | §2（形状 + 产品表） |
| 时间模型 | 无 | §5（UTC 钟 + 两级时间制） |
| 断连语义 | 无 | §6.2 分域定义 |
| 并发规则 | 无 | §7（OTA 独占） |
| 字节序 | 未声明 | 固定字段全小端（D-11） |
| 心跳 | CTRL/HEARTBEAT | **不做**，编号保留（D-10） |
| 能力发现 | HELLO 大协商 + Manifest | **静态注册表制**，HELLO 最小化（D-12） |
| LOG 导出 | 独立 EXPORT_* 组 | 并入 FILE 域，编号保留弃用 |
| FILE | 匿名 file read | **多文件名 + LIST + DELETE**，路径隐含约定（D-8） |
| OTA | 自研描述 | **对齐 MCUmgr/SMP 语义**（§14.6 映射表，D-9） |
| TUNNEL | 无 | 新增域（在线透传） |
| transfer_id | u32 | **u8**（单传输并发下收窄省流） |
| REPORT bundle 头 | seq+base_time_ms(4)+count = 6B | seq+**sec(4)+ms(2)**+count = 8B（T-head6 锚点，D-3） |

## 22. V1.0 冻结前待办

1. **S7 采集 Profile**：把 [`../architecture/s7/protocol-zqdata-uhtp-v1.md`](../architecture/s7/protocol-zqdata-uhtp-v1.md) 改写为一页 Profile——选域（CTRL/ALGO/FILE/TUNNEL）、填注册表（algo_id、文件命名约定、tunnel_id）、S7 GATT 绑定、legacy 双栈迁移；其 FILE 断点续传/会话槽等超出本版决策的内容按 D-6/D-8 删改。
2. 固件评审：OTA 窗口 ACK vs SMP 逐片应答的实现取舍确认；文件命名规则（`dc/`、`log/` 目录约定）定稿。
3. 双端金帧联调 → `protocol_version=1` 冻结 → 本文档改版 V1.0。

## 引用

- 前身：[`UHTP_BLE_Protocol_Design_V4.md`](UHTP_BLE_Protocol_Design_V4.md)（骨架来源，本文 §3/§10/§13-14 大量继承）
- 契约草案：[`uwtp_v0.99_draft.proto`](uwtp_v0.99_draft.proto)
- 示例脚本：[`assets/gen_uwtp_examples.py`](assets/gen_uwtp_examples.py)
- 现网协议（迁移对象）：[`../architecture/s7/S7协议共识规格.md`](../architecture/s7/S7协议共识规格.md)；参照系：Zephyr MCUmgr/SMP img_mgmt、MCUboot 双槽模型
