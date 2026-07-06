# UWTP 统一可穿戴传输协议 · 设计 V0.99-r2（V1.0 RC 候选）

> **UWTP = Unified Wearable Transport Protocol**。前身 [`UHTP_BLE_Protocol_Design_V4.md`](UHTP_BLE_Protocol_Design_V4.md)（UHTP V4）——骨架（5B 小头 + 事务域状态机 + Protobuf 协商 + Report TLV + offset 大对象传输）原样继承；V0.99 补齐 V4 缺失的 Core 章节并收敛全部含糊点（决策表 §0.2）。
> **r2（2026-07-06）**：按完整审查意见修订，十项修改全部采纳（修订记录 §23）——核心是**时间戳收敛到只属于在线数据**、FILE 域去采样时间语义、HELLO 加 registry_hash 一致性校验、REPORT record 改 u24 rel_ms、OTA 补 WriteNoRsp 节流。
> **状态**：**V1.0 RC 候选**。双端按本文档共识开发（**静态注册表制，不做动态能力解析**）；联调通过后定稿 V1.0，线上 `protocol_version = 1`。
> 机器可读契约：[`uwtp_v0.99_draft.proto`](uwtp_v0.99_draft.proto)；示例帧全部脚本实算（[`assets/gen_uwtp_examples.py`](assets/gen_uwtp_examples.py)，protobuf wire + CRC-32/SHA-256，len 自洽断言）。
> S7 采集剖面：现有 [`../architecture/s7/protocol-zqdata-uhtp-v1.md`](../architecture/s7/protocol-zqdata-uhtp-v1.md) 将按本文改写为「S7 采集 Profile」（§22）。

---

## 0. 总览

### 0.1 一句话

> **UWTP Core 只管在线数据、控制、传输、OTA、文件搬运；在线数据可以有协议层 timestamp，离线数据的 timestamp 属于文件格式，不属于 UWTP FILE 传输层。**
> 5B 头管分流，main_type 管事务域状态机，sub_type 管域内动作，seq 管顺序与响应回显，SEG_MODE 管小消息重组，offset 管大对象进度，Protobuf 管控制语义，TLV 容器管在线结果打包——控制无生命周期，传输各域自带丢包容忍度与完整性校验，OTA 独占。

### 0.2 决策表（2026-07-06 审议 + r2 审查修订）

| # | 决策 | 内容 |
| --- | --- | --- |
| D-1 | 命名 | UHTP → **UWTP**；文档 V0.99-r2（冻结后 1.0），线上 `protocol_version=1` |
| D-2 | **TIME 域时间格式** | CTRL/TIME 使用完整时间 `u32 unix_sec + u16 ms + s16 tz_offset_min`——**仅用于设备校时与回读，不进入 FILE / OTA / 普通传输帧** |
| D-3 | **在线数据时间** | **协议层 timestamp 只用于在线数据**：REPORT_TLV / LOG_LIVE 可在 bundle/event 头携带在线时间锚点；**FILE / OTA 传输层不携带采样 timestamp** |
| D-3a | **离线文件时间** | 离线采集时间/样本时间/段落时间**属于文件内容格式**（如 UOF1 文件头与记录），不属于 UWTP FILE 域 |
| D-3b | **REPORT_TLV 时间** | bundle 头 = `u32 unix_sec + u16 ms`；record 用 **u24 rel_ms（LE）** 毫秒偏移（≈4.66h 量程） |
| D-4 | 会话生命周期 | 控制类不做生命周期管理（无状态请求/响应）；传输域各自定义断连语义（§6.2） |
| D-5 | OTA 断连 | 状态随断连结束；重连重走协商、从 flash 水位断点续传；升级必经 reset + confirm |
| D-6 | FILE 断连 | 断连即作废、**重传从头**；**V1.0 不支持断点续传**——未来文件明显增大时可在 READ_BEGIN 增加 optional offset，V1 语义固定 offset=0 |
| D-7 | 在线断连 | TUNNEL/ALGO 推流不缓存，丢了就丢；重连后 App 核对在线会话一致性，不一致即发结束 |
| D-8 | 文件目录 | 多文件名 + LIST；**路径为协议隐含约定**（协议只传相对文件名） |
| D-9 | OTA 参照 | 语义对齐 Zephyr MCUmgr/SMP img_mgmt（§14.6 映射表） |
| D-10 | 心跳 | 不做。BLE connection supervision timeout 负责断链；业务活性靠请求超时（参数见 D-15）。编号保留 |
| D-11 | 字节序 | 固定二进制字段一律小端（铁律） |
| D-12 | 能力发现 | 静态注册表制、HELLO 最小化；**但 HELLO 必须带 `profile_id + registry_hash` 做同表一致性校验**（§12.1） |
| D-13 | 安全 | Just Works，不做应用层全加密；SECURITY 留一次性鉴权位；**量产边界见 §9** |
| D-14 | 并发 | OTA 最高优先级、独占；CTRL 恒可用 |
| D-15 | **超时参数** | CTRL 请求超时 **3s、重试 2 次**；OTA / FILE 窗口 ACK 超时 **5s**（联调可标定，写死默认值） |
| D-16 | **WriteNoRsp 节流** | App→表连续写（OTA DATA）必须按 `max_in_flight_chunks` 小批发送 + 平台写回调/延迟节流（§14.4） |
| D-17 | **transfer_id 规则** | u8；**0 保留为无效**，1–255 有效回绕；设备在 READY 中生成；断连 / END / ABORT 后作废 |
| D-18 | **校验算法写死** | 文件校验 = **CRC-32/IEEE 802.3**（poly `0x04C11DB7`、init `0xFFFFFFFF`、refin/refout true、xorout `0xFFFFFFFF`）；OTA `image_hash` = **SHA-256** |

### 0.3 六域总览

<div class="fig">
<svg viewBox="0 0 840 450" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>UWTP V0.99-r2 六域地图：每域的方向、可靠性策略与完整性校验</title>
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
<text x="40" y="22" class="t">UWTP V0.99-r2 —— GATT: 1 Service + RX(WriteNoRsp) + TX(Notify)；S7 复用 ZQDATA 45121540/41/42</text>
<rect x="40" y="34" width="760" height="34" class="bxa"/>
<text x="420" y="56" text-anchor="middle" class="m a">5B 头：ver_flags | main_type | sub_type | seq | len　·　固定字段全小端　·　NEED_RSP 回显 seq　·　响应 Protobuf 首字段恒 status</text>
<g class="m">
  <rect x="40" y="86" width="243" height="96" class="bx"/>
  <text x="161" y="106" text-anchor="middle" class="a">0x01 CTRL（无状态，恒可用）</text>
  <text x="161" y="124" text-anchor="middle" class="ms">SESSION·TIME·USER_PROFILE</text>
  <text x="161" y="140" text-anchor="middle" class="ms">ALGO_CTRL·STORAGE</text>
  <text x="161" y="158" text-anchor="middle" class="ms">请求/响应, 单飞, 超时3s×2</text>
  <text x="161" y="174" text-anchor="middle" class="ms">心跳不做(BLE 链路监督兜底)</text>
  <rect x="299" y="86" width="243" height="96" class="bx"/>
  <text x="420" y="106" text-anchor="middle" class="a">0x02 ALGO（表→App 推流）</text>
  <text x="420" y="124" text-anchor="middle" class="ms">REPORT_TLV：可丢, 不 ACK</text>
  <text x="420" y="140" text-anchor="middle" class="ms">在线锚点: bundle 头 sec+ms</text>
  <text x="420" y="158" text-anchor="middle" class="ms">record 带 u24 rel_ms 偏移</text>
  <text x="420" y="174" text-anchor="middle" class="ms">开关=ALGO_CTRL, 断连全关</text>
  <rect x="558" y="86" width="242" height="96" class="bx"/>
  <text x="679" y="106" text-anchor="middle" class="a">0x06 TUNNEL（双向透传）</text>
  <text x="679" y="124" text-anchor="middle" class="ms">厂商字节原样, 设备不解释</text>
  <text x="679" y="140" text-anchor="middle" class="a" font-size="10">UWTP 不为 TUNNEL 提供可靠性</text>
  <text x="679" y="158" text-anchor="middle" class="ms">可靠性需厂商协议自带 seq/ack</text>
  <text x="679" y="174" text-anchor="middle" class="ms">重连后 App 核对会话一致性</text>
</g>
<g class="m">
  <rect x="40" y="198" width="243" height="110" class="bxa"/>
  <text x="161" y="218" text-anchor="middle" class="a">0x04 OTA（App→表，独占）</text>
  <text x="161" y="236" text-anchor="middle" class="ms">零容忍：窗口 ACK + SHA-256</text>
  <text x="161" y="252" text-anchor="middle" class="ms">+ bootloader 签名验证</text>
  <text x="161" y="268" text-anchor="middle" class="ms">断连=状态结束, 重协商续断点</text>
  <text x="161" y="284" text-anchor="middle" class="ms">WriteNoRsp 按 in_flight 节流</text>
  <text x="161" y="300" text-anchor="middle" class="ms">语义对齐 MCUmgr/SMP img_mgmt</text>
  <rect x="299" y="198" width="243" height="110" class="bxa"/>
  <text x="420" y="218" text-anchor="middle" class="a">0x05 FILE（表→App，纯搬运）</text>
  <text x="420" y="236" text-anchor="middle" class="ms">LIST/READ/DELETE, 路径隐含约定</text>
  <text x="420" y="252" text-anchor="middle" class="ms">链路可靠 + 整档 CRC-32/IEEE</text>
  <text x="420" y="268" text-anchor="middle" class="ms">断连=作废, 重传从头(不续传)</text>
  <text x="420" y="284" text-anchor="middle" class="a" font-size="10">不解释文件内容·不带采样时间</text>
  <text x="420" y="300" text-anchor="middle" class="ms">离线时间归文件格式(UOF1 等)</text>
  <rect x="558" y="198" width="242" height="110" class="bxd"/>
  <text x="679" y="218" text-anchor="middle" class="s" font-weight="700">0x03 LOG / 0xF0 VENDOR / SECURITY</text>
  <text x="679" y="236" text-anchor="middle" class="ms">LIVE_EVENT 可丢(V1 可选)</text>
  <text x="679" y="252" text-anchor="middle" class="ms">日志文件导出→FILE 域</text>
  <text x="679" y="268" text-anchor="middle" class="ms">SECURITY: Just Works, 量产开放</text>
  <text x="679" y="284" text-anchor="middle" class="ms">OTA/FILE/DELETE 须启用鉴权</text>
  <text x="679" y="300" text-anchor="middle" class="ms">静态注册表 + registry_hash 校验</text>
</g>
<rect x="40" y="326" width="760" height="34" class="bx"/>
<text x="420" y="348" text-anchor="middle" class="m">并发铁律：CTRL 恒可用　＞　<tspan class="a">OTA 独占（停一切文件类传输与采集）</tspan>　＞　FILE 单传输　＞　TUNNEL/ALGO 让位</text>
<g class="s">
  <text x="40" y="384">时间三层：CTRL/TIME 负责校时（唯一出现时区处）；在线数据（REPORT/LOG_LIVE）可带在线锚点；</text>
  <text x="40" y="402">FILE 只传文件名/大小/CRC/数据块——离线文件内部时间由文件格式（UOF1 等）自行定义，UWTP Core 不解释。</text>
  <text x="40" y="420">断连语义：控制类无状态；OTA 重协商续断点；FILE 从头重传；在线（TUNNEL/ALGO）丢了就丢。</text>
  <text x="40" y="438">legacy 共存：0xBB(B2A) 与 0x1?(UWTP) 首字节分流；0x1? 但校验失败 = UWTP_BAD_FRAME，不回落 legacy。</text>
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
| 连接 | 单连接假设；连接建立后协商 MTU 至 247 |
| 连接参数 | 进入传输类域（OTA / FILE 读）→ 设备切快速连接参数；END/ABORT 后恢复省电参数 |
| 订阅 | App 使能 TX CCC 后协议才可用；HELLO 为首个交互 |

**产品绑定表**：

| 产品 | Service / RX / TX | 备注 |
| --- | --- | --- |
| **S7（现有项目）** | `45121540 / 45121541 / 45121542-51F2-406E-927A-3E1E183412E0` | 复用现网 ZQDATA 服务特征；与 legacy B2A 帧同特征双栈共存（§11.2） |
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

## 4. 响应模型与超时（铁律）

1. `NEED_RSP=1` 的请求 → 对端以**同 main/sub + 回显 seq** 响应；
2. **响应 payload 若为 Protobuf，首字段恒 `uint32 status = 1`**（0=OK，proto3 缺省不编码 → 成功且无数据时 payload 为空）；固定二进制响应的 status 位置由各消息显式定义；
3. **单飞**：同方向同时最多一个未决请求；数据面帧（DATA/ACK/REPORT/TUNNEL/LIVE_EVENT）不是请求、不占单飞额度；
4. **超时参数（D-15）**：CTRL 请求超时 **3s、重试 2 次**（seq 递增）；OTA 窗口 ACK 超时 **5s**；FILE 窗口 ACK 超时 **5s**——超时即判传输失败（按 §6.2 断连语义处理）；
5. 错误响应 = `CommonRsp{status}`（§10 状态码）。

## 5. 时间模型（D-2/D-3/D-3a/D-3b：时间戳只属于在线数据）

分成三层，**UWTP Core 不负责解释离线文件内部时间**：

```
CTRL/TIME:      设备校时。唯一使用完整时间(u32 unix_sec + u16 ms + s16 tz_offset_min)、
                唯一出现时区的地方。仅用于校时与回读，不进入 FILE / OTA / 普通传输帧。

ONLINE DATA:    在线 ALGO REPORT_TLV / LOG LIVE_EVENT（/ TUNNEL 若厂商协议需要）
                可携带在线时间锚点：bundle/event 头 = u32 unix_sec + u16 ms（设备 UTC 钟），
                记录级 = u24 rel_ms 毫秒偏移。

FILE:           只传文件名、大小、kind、transfer_id、offset、data、CRC32、DELETE。
                不定义采样时间戳。文件内容的采集时间/样本时间/段落时间
                由文件格式自行定义（如 UOF1 文件头与记录格式）。
```

> **UWTP FILE 域是文件搬运协议，不解释文件内容；离线采集时间、样本时间、段落时间由文件格式定义，不属于 UWTP Core。** 若 App 需要展示"文件时间"，从文件名约定解析（如 `dc/ecg_0706_1600.uof`）或读文件头。

## 6. 会话与断连语义（D-4~D-7）

### 6.1 会话

- **HELLO 最小化 + 同表校验（D-12）**：交换 `protocol_version`、身份、**`profile_id` + `registry_hash`**，一轮完成（§12.1）；
- **无动态能力发现**：支持哪些域/子命令/格式/算法，以本文档注册表为共识，双端硬编码；文档改版即协议改版；
- 无 BYE 强制要求（断连即会话终）；控制类命令随连随发，无状态。

### 6.2 断连语义表（各传输域自治）

| 域 | 断连时 | 重连后 |
| --- | --- | --- |
| CTRL | 未决请求作废 | 直接重发 |
| **OTA** | 传输状态结束；已写 flash 数据保留；transfer_id 作废 | 重走 UPLOAD_BEGIN 协商，READY 回 `next_offset`（flash 水位，同 image_hash 才续）→ 断点续传 |
| **FILE** | 传输作废；transfer_id 作废 | 重新 READ_BEGIN **从头传**（D-6） |
| ALGO 推流 | 停止；不缓存 | App 重新 ALGO_CTRL 使能（断连自动全关） |
| TUNNEL | 不缓存、丢了就丢 | App 核对在线会话一致性；不一致 → App 发结束，会话终止 |

## 7. 并发矩阵（D-14）

| 进行中 ↓ \ 想做 → | CTRL | ALGO 推流 | FILE 读 | OTA | TUNNEL |
| --- | --- | --- | --- | --- | --- |
| 空闲 | ✅ | ✅ | ✅ | ✅ | ✅ |
| FILE 读 | ✅ | ⚠ 让位（可丢/降频） | ❌ BUSY（单传输） | ✅ 抢占（FILE 被 ABORT） | ⚠ 上行让位 |
| **OTA** | ✅（查电量等） | ❌ 停止 | ❌ 停止 | ❌ BUSY | ❌ 停止 |
| TUNNEL 活跃 | ✅ | ✅ | ✅（TUNNEL 让位） | ✅ 抢占 | — |

铁律：**CTRL 恒可用 ＞ OTA 独占 ＞ FILE 单传输 ＞ TUNNEL/ALGO 让位**。

## 8. 可靠性与完整性分层（每域自带容忍度）

| 域 / 数据 | 方向 | 丢包容忍 | 逐帧确认 | 完整性校验 | 断连恢复 | 超时 |
| --- | --- | --- | --- | --- | --- | --- |
| CTRL 请求/响应 | 双向 | 不容忍 | NEED_RSP + 重发 | Protobuf 解析 + status | 重发 | 3s ×2 |
| **OTA 镜像** | App→表 | 零容忍 | 窗口 ACK | ACK 连续性 + **SHA-256** + bootloader 签名 | 断点续传 | ACK 5s |
| **FILE** | 表→App | 零容忍（链路可靠） | 窗口 ACK（流控+进度） | offset 连续性 + **CRC-32/IEEE** | 从头重传 | ACK 5s |
| ALGO REPORT | 表→App | **可丢** | 无 | `bundle_seq` 缺口仅统计 | 无 | — |
| LOG LIVE_EVENT | 表→App | **可丢** | 无 | 无 | 无 | — |
| TUNNEL | 双向 | **可丢** | 无 | 厂商协议自带 | App 核对会话 | — |

> BLE 链路层已保证空口按序可靠——协议层校验解决**端到端**问题（flash 写坏、固件逻辑错、截断），不是空口丢包。

## 9. 安全姿态（D-13 + 量产边界）

- 配对：**Just Works**；不做应用层全加密（太重）；
- 预留：`CTRL/SECURITY`（sub `0x08`）= 连接起始一次性鉴权（challenge-response 一轮），V0.99 不实现；
- **量产边界（必须遵守）**：**V1.0 内部调试版允许 SECURITY 不实现；量产版若开放 OTA / FILE READ / DELETE / USER_PROFILE，必须启用 SECURITY 或系统级绑定鉴权。** 无鉴权期的现实边界 = 物理接近 + Just Works 配对，文档明示此假设。

## 10. 状态码（继承 V4 全表）

`0x00 OK · 0x01 INVALID_REQUEST · 0x02 NOT_SUPPORTED · 0x03 INVALID_STATE · 0x04 BUSY · 0x05 INVALID_PARAM · 0x06 NO_MEMORY · 0x07 NO_SPACE · 0x08 CRC_ERROR · 0x09 HASH_MISMATCH · 0x0A AUTH_FAILED · 0x0B TIMEOUT · 0x0C RETRY_LATER · 0x0D SERVICE_REJECTED · 0x0E UNSUPPORTED_SCHEMA · 0x0F UNSUPPORTED_FORMAT · 0x10 TOO_LARGE`

## 11. 版本管理与 legacy 共存

### 11.1 分层版本

`header_ver`（每帧，只管头解析）→ `protocol_version`（HELLO，V1.0 冻结 = 1）→ **注册表标识 `profile_id` + `registry_hash`**（HELLO 校验，§12.1/§20）。proto3 演进只增不改 tag。

### 11.2 与 S7 legacy（B2A）共存 + 误判保护

- legacy B2A 帧首字节恒 `0xBB`；UWTP 帧首字节高半字节恒 `0x1`——完全不相交。固件同特征双栈：首字节 `0xBB` → 旧 B2A 解析器，`0x1?` → UWTP，其余丢弃计数。
- **误判保护**：**首字节高半字节为 `0x1` 但 `len` 校验失败（实长 ≠ 5+len）或字段非法 → 计为 `UWTP_BAD_FRAME` 丢弃，绝不回落 legacy 解析器**——防随机数据进错解析器。
- App 侧 HELLO 1s×2 超时 → 判定 legacy 固件回落旧协议。

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
| `0x06` | HEARTBEAT | ⏸ 保留 | 不做（D-10） |
| `0x07` | STORAGE | ✅ | 存储占用查询 |
| `0x08` | SECURITY | ⏸ 保留 | 一次性鉴权（量产边界 §9） |
| `0x09` | USER_PROFILE | ✅ | 个人信息写/读 |

### 12.1 SESSION（HELLO + 同表一致性校验，D-12）

```proto
message Hello    { uint32 protocol_version = 1; string app_version = 2;
                   string profile_id = 3; uint32 registry_hash = 4; }           // App→表
message HelloRsp { uint32 status = 1; uint32 protocol_version = 2;
                   string firmware_version = 3; string device_sn = 4;
                   string profile_id = 5; uint32 registry_hash = 6; }           // 表→App
```

**校验规则**：`protocol_version` 或 `registry_hash` 不一致 → 表回 `status = UNSUPPORTED_SCHEMA(0x0E)`，双方**只保留 CTRL 域可用**（可查版本/电量，便于诊断），业务域（ALGO/FILE/OTA/TUNNEL）拒绝服务。这防住静态注册表制的唯一风险：*App 认为 algo_id=2 是 SpO2、固件认为是 Wear*——同表校验保证双方拿的是同一张表。`registry_hash` 定义见 §20.0。

### 12.2 TIME（固定二进制 8B，双向同布局；仅校时用，D-2）

```
SET (App→表, NEED_RSP):  [u32 unix_sec][u16 ms][s16 tz_offset_min]   → CommonRsp
GET (App→表, NEED_RSP):  空 payload                                  → 同 8B 布局回读
```

### 12.3 USER_PROFILE

写：Protobuf `UserProfile{height_cm / weight_kg_x10 / sex / birth_y/m/d / wear_hand}` → 落 NVM，回 `CommonRsp`。读：空 payload + NEED_RSP → `UserProfileRsp{status, profile}`。

### 12.4 ALGO_CTRL

`AlgoReportCtrl{ enable_ids[](packed), min_interval_ms }` → `AlgoReportCtrlRsp{ status, active_ids[] }`（生效集合回读对账）。空集 = 全关；断连自动全关。

### 12.5 STORAGE

`StorageInfoReq{}` → `StorageInfo{ status, total_bytes, used_bytes, file_count }`。

## 13. ALGO 域（`main_type 0x02`）

| sub | 名称 | V0.99 |
| --- | --- | --- |
| `0x01` | RAW_STREAM | ⏸ 保留（在线原始流走 TUNNEL） |
| `0x02` | REPORT_TLV | ✅ |

### 13.1 REPORT_TLV 布局（D-3b：record 用 u24 rel_ms）

```
bundle 头 (8B):   [bundle_seq u8][unix_sec u32][ms u16][record_count u8]  ← 在线锚点(设备 UTC 钟)
record (6B+N):    [algo_id u8][rel_ms u24 LE][flags u8][len u8][value: 该算法 Protobuf]
                   rel_ms = 相对 bundle 锚点的毫秒偏移, 量程 ≈4.66h
flags: bit0 VALID · bit1 NEW_RESULT · bit2 WARNING · bit3 ERROR · bit4 HAS_EXT
打包判断: 8 + Σ(6 + len) ≤ 238；单 record 超限 → SEG_MODE 或精简 schema
```

表→App 推流，可丢不 ACK；`algo_id → 结果 message` 映射 = 静态注册表（§20.3）。

### 13.2 异常解析规则（App 侧强制）

1. `record_count` 与实际解出的 record 数不一致 → **丢弃整个 bundle**；
2. record `len` 超出剩余 payload → **丢弃整个 bundle**；
3. 未知 `algo_id` → 丢弃该 record，计数；
4. inner Protobuf 解析失败 → 丢弃该 record，计数；
5. `flags.ERROR` 置位 → 可展示错误，**不得按正常结果使用**；
6. `bundle_seq` 缺口 → 只统计，不重传；
7. `bundle_seq` u8 回绕按 modulo 256 处理。

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
  ▲                                                   │ UPLOAD_END + SHA-256 校验
  │◀── 断连（状态结束, flash 保留）────────────────────┤
  │                                                   ▼
  └── RESET ◀── IMG_STATE(TEST) ◀── VERIFIED     [失败→ERROR→ABORT]
        │ bootloader 试启动新镜像
        ▼
   新固件自检 → IMG_STATE(CONFIRM)；未 confirm 下次复位自动回滚
```

### 14.2 IMG_LIST

`OtaImgListRsp{ status, slots[] }`；`OtaSlot{ slot, version, state_flags }`——`state_flags`：bit0 BOOTABLE · bit1 PENDING · bit2 CONFIRMED · bit3 ACTIVE · bit4 PERMANENT（与 MCUmgr image state flags 一一对应）。

### 14.3 UPLOAD_BEGIN / READY（App 提议、设备定版）

```proto
message OtaUploadBegin { uint32 image_size = 1; bytes image_hash = 2;   // SHA-256, 32B（D-18）
                         string version = 3; uint32 target_slot = 4;
                         uint32 preferred_chunk = 5; uint32 preferred_window = 6; }
message OtaUploadReady { uint32 status = 1; uint32 transfer_id = 2; uint32 chunk_bytes = 3;
                         uint32 window_chunks = 4; uint32 next_offset = 5;
                         uint32 max_in_flight_chunks = 6;               // WriteNoRsp 节流（D-16）
                         uint32 inter_chunk_delay_us = 7; }             // 0 = 仅按平台写回调节流
```

`next_offset`：全新 = 0；断连重来 = 设备已落 flash 的连续水位（**同 image_hash 才允许续**，否则从 0）。

### 14.4 UPLOAD_DATA / ACK 与 WriteNoRsp 节流（D-16）

```
DATA (App→表):  [transfer_id u8][offset u32][data ≤232B]
ACK  (表→App):  [transfer_id u8][next_offset u32][status u8]   ← 每 window_chunks 片一次
```

- **节流铁律**：RX 是 Write Without Response——**App 不得无节制连续写完整个 window**。App 以 `max_in_flight_chunks` 为小批次发送；每批之间等待平台 BLE 写队列回调（Android `onCharacteristicWrite` / iOS `canSendWriteWithoutResponse`），或至少延迟 `inter_chunk_delay_us`。否则协议无错、手机 BLE 栈被写爆。
- `next_offset` 之前已连续写入 flash；status 非 0（CRC_ERROR/NO_SPACE 等）→ App 回退到 next_offset 重发；窗口 ACK 超时 5s → 传输失败。
- **transfer_id 规则（D-17）**：u8；`0` 保留为无效；`1–255` 有效回绕；由设备在 READY 生成；**断连 / END / ABORT 后作废**，携带已作废 id 的帧回 `INVALID_STATE`。

### 14.5 UPLOAD_END / IMG_STATE / RESET

END（NEED_RSP）→ 设备整镜像 **SHA-256** 校验 → `CommonRsp`；`OtaImgState{op: 1 TEST / 2 CONFIRM, slot}`；`OtaReset{}`。UWTP 只管协商/传输/续传/窗口 ACK/hash/状态切换请求；镜像格式解析、签名验证、slot 切换、test boot、confirm、rollback 归 bootloader。

### 14.6 与 MCUmgr/SMP img_mgmt 映射（D-9）

| UWTP | MCUmgr/SMP | 备注 |
| --- | --- | --- |
| IMG_LIST | image state read | 槽位+版本+hash+flags |
| UPLOAD_BEGIN(size+sha256) / READY(next_offset) | image upload 首请求（len+sha）/ 响应 `off` | 语义等价；UWTP 多 chunk/window/in_flight 协商 |
| UPLOAD_DATA{off,data} + 窗口 ACK | upload 请求/响应（逐请求 ACK） | 差异：SMP 逐 chunk 应答，UWTP 窗口 ACK + 节流换吞吐 |
| IMG_STATE(TEST/CONFIRM) | image state write (test/confirm) | 一致 |
| RESET | os mgmt reset | 一致 |
| 未 confirm 回滚 | MCUboot revert | 一致 |

## 15. FILE 域（`main_type 0x05`，纯文件搬运，表→App）

| sub | 名称 | | sub | 名称 |
| --- | --- | --- | --- | --- |
| `0x01` | LIST | | `0x05` | READ_ACK |
| `0x02` | READ_BEGIN | | `0x06` | READ_END |
| `0x03` | READ_READY | | `0x07` | ABORT |
| `0x04` | READ_DATA | | `0x08` | DELETE |

**FILE 域只关心：文件名、大小、kind、transfer_id、offset、data、CRC32、DELETE——不关心采样开始/结束时间、样本时间、bundle 锚点**（D-3a，见 §5）。

### 15.1 路径隐含约定（D-8）

协议只传相对文件名（`dc/ecg_0706_1600.uof`、`log/eiotlog.log`）；根目录、目录结构、命名规则是固件与 App 的文档级共识（Profile 章写死）。App 需要"文件时间"时从文件名约定解析或读文件头（如 UOF1）。`kind`：`0 不过滤 · 1 LOG · 2 CONFIG⏸ · 3 DEBUG_DUMP⏸ · 4 OFFLINE_DATA`。

### 15.2 LIST（Protobuf，大页允许 SEG_MODE）

```proto
message FileListReq { uint32 kind = 1; uint32 page = 2; uint32 page_size = 3; }
message FileListRsp { uint32 status = 1; uint32 total = 2; uint32 page = 3; repeated FileEntry entries = 4; }
message FileEntry   { string name = 1; uint32 size = 2; uint32 kind = 3; uint32 flags = 4; }
```

> r2 修订：**FileEntry 删除 `mtime_sec`**——严格执行"时间戳只在在线数据使用"；`flags` 为保留位（bit0 只读等，V1 置 0）。

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

- 窗口 ACK 职责 = 流控 + 进度（链路层已可靠，不做重传语义）；表最多领先未 ACK 一个窗口；ACK 超时 5s → 传输失败；
- **`file_crc32` = CRC-32/IEEE 802.3**（poly `0x04C11DB7`、init `0xFFFFFFFF`、refin/refout true、xorout `0xFFFFFFFF`，D-18）；App 收完全量比对，不符 → 整档重拉（文件仍在）；
- **V1.0 不支持断点续传**；未来文件尺寸明显增大时，可在 READ_BEGIN 增加 optional offset 字段——V1 语义固定从 0 传；
- transfer_id 规则同 §14.4（D-17）；
- **DELETE{name}**（NEED_RSP）：App 校验通过且确认后显式删除；读传输中拒删（BUSY）。传输本身永不隐式删数据。

## 16. LOG 域（`main_type 0x03`）

- `0x01 LIVE_EVENT`：实时日志推流，可丢不 ACK（V0.99 可选实现）；二进制 `[rel_ms u32][level u8][module u8][event u16][args...]`（rel_ms 相对本连接首个 LIVE_EVENT 的在线锚点，锚点帧带 `[unix_sec u32][ms u16]` 前缀，flags 位区分——实现细节 Profile 定）；事件字典 = 文档静态注册表。
- `0x02–0x09`（V4 的 EXPORT_* 组）：编号保留、弃用——**日志文件导出统一走 FILE 域**（V1.0 保持：LOG 域只做 LIVE_EVENT）。

## 17. TUNNEL 域（`main_type 0x06`）

`sub_type = tunnel_id`（注册表：`0x01 GH3220_EVK`）。payload = 厂商协议字节原样透传，设备与 UWTP 均不解释；消息边界不保证（字节流语义）。

> **强约束：UWTP 不为 TUNNEL 提供可靠性。若厂商协议需要可靠性，必须由厂商协议自身实现 seq / ack / retry；否则 TUNNEL 只适合实时观测与可丢数据。** FILE/OTA 传输期间 TUNNEL 上行让位（§7）。

## 18. VENDOR（`main_type 0xF0`）

厂商/实验保留。启用前须在 §20 注册表登记。

---

# Part III · 附录

## 19. Example raw packet + decode（脚本实算，r2 布局）

```
E1  TIME SET (13B):  14 01 05 21 08 | 80 60 4B 6A | 00 00 | E0 01
    sec=1783324800 ms=0 tz=+480 → rsp: 10 01 05 21 00 (status=0 空)

E2  HELLO (40B):  14 01 01 22 23 | 08 01 | 12 0D "bluetrace-1.0"
                  | 1A 0A "s7-collect" | 20 D4 B2 A2 CF 06        ← registry_hash=0x69E89954
    RSP (48B):    10 01 01 22 2B | 10 01 | 1A 0B "s7-fw-1.2.0" | 22 08 "S7A1B2C3"
                  | 2A 0A "s7-collect" | 30 D4 B2 A2 CF 06        ← 同表校验通过

E3  FILE/LIST req (7B): 14 05 01 40 02 | 18 10
    rsp (62B): 10 05 01 40 39 | total=2
               | entry{name="dc/ecg_0706_1600.uof", size=460800, kind=4}
               | entry{name="log/eiotlog.log", size=131072, kind=1}     ← 无时间字段(r2)

E4  READ_BEGIN (27B): 14 05 02 41 16 | name="log/eiotlog.log" | chunk=230 | win=16
    READ_READY (22B): 10 05 03 41 11 | tid=2 total=131072 chunk=230 win=16 crc32=0xA56BF2B7
    READ_DATA#0(240B): 10 05 04 00 EB | 02 | 00 00 00 00 | data 230B
    READ_ACK (11B):    10 05 05 50 06 | 02 | 60 0E 00 00 | 00

E5  OTA UPLOAD_BEGIN (57B): 14 04 02 31 34 | size=262144 | 12 20 <SHA-256 32B: 6B 5C 5E E5...>
                            | v"1.3.0" | slot=1 | chunk=224 | win=32
    UPLOAD_READY (16B): 10 04 03 31 0B | tid=7 chunk=224 win=32 next_offset=0
                        | 30 08 ← max_in_flight_chunks=8（节流，D-16）
    UPLOAD_DATA#0 (234B): 10 04 04 00 E5 | 07 | 00 00 00 00 | data 224B
    UPLOAD_ACK (11B):     10 04 05 60 06 | 07 | 00 1C 00 00 | 00       ← next=7168

E6  REPORT_TLV (40B): 10 02 02 36 23 | 05 | A0 7C 4B 6A | FA 00 | 02
      | rec{algo=1 rel_ms=00 00 00 flags=03 len=6 HrResultV1{72,90,80}}
      | rec{algo=2 rel_ms=E8 03 00(=1000ms) flags=03 len=9 Spo2ResultV1{98,72,85,pi1500}}
      bundle 锚点 = sec+7200, 250ms；record 头 6B（u24 rel_ms，D-3b）
```

## 20. 静态注册表汇总（D-12：文档即共识，双端硬编码）

### 20.0 注册表标识（HELLO 校验源）

- **`profile_id`**：产品剖面标识字符串，注册表：`"s7-collect"`（S7 采集）；新剖面在此登记。
- **`registry_hash`**：u32，**由文档维护者在每次注册表（§20.1–20.5 任一表）变更时更新**。当前值 = `CRC32("UWTP-REG/0.99-r2") = 0x69E89954`。双端硬编码此值，HELLO 不一致 → 拒绝业务域（§12.1）。

### 20.1 main_type

`0x01 CTRL · 0x02 ALGO · 0x03 LOG · 0x04 OTA · 0x05 FILE · 0x06 TUNNEL · 0xF0 VENDOR`（sub 表见各域章节）。

### 20.2 FILE kind

`0 不过滤 · 1 LOG · 2 CONFIG⏸ · 3 DEBUG_DUMP⏸ · 4 OFFLINE_DATA`。

### 20.3 algo_id（Profile 填充）

`1 HR → HrResultV1 · 2 SPO2 → Spo2ResultV1 · 3 WEAR → WearResultV1 · 【S7 Profile 增补】`。

### 20.4 tunnel_id

`0x01 GH3220_EVK`。

### 20.5 离线文件内容格式（FILE 对内容不透明，App 按扩展名/Profile 约定选解析器）

`.uof = UOF1 统一离线格式（推荐；**文件内时间/采样率/通道由 UOF1 自定义**，见 D-3a）· 现网 ECG1_V2 / PPGHR40 / GS18 兼容期保留`。

## 21. 相对 UHTP V4 的差异清单

| 项 | V4 | V0.99-r2 |
| --- | --- | --- |
| 名称 | UHTP | UWTP（D-1） |
| GATT 绑定 | 未定义 | §2（形状 + 产品表） |
| 时间模型 | 无 | §5 三层制：**时间戳只属于在线数据**；FILE/OTA 无采样时间；离线时间归文件格式（D-2/D-3/D-3a） |
| 断连语义 | 无 | §6.2 分域定义 |
| 并发规则 | 无 | §7（OTA 独占） |
| 字节序 | 未声明 | 固定字段全小端（D-11） |
| 超时/节流 | 无 | CTRL 3s×2、窗口 ACK 5s（D-15）；WriteNoRsp in_flight 节流（D-16） |
| 心跳 | CTRL/HEARTBEAT | 不做，编号保留（D-10） |
| 能力发现 | HELLO 大协商 + Manifest | 静态注册表 + **profile_id/registry_hash 同表校验**（D-12） |
| LOG 导出 | 独立 EXPORT_* 组 | 并入 FILE 域，编号保留弃用 |
| FILE | 匿名 file read | 多文件名 + LIST + DELETE；**FileEntry 无时间字段**（D-3a/D-8） |
| OTA | 自研描述 | 对齐 MCUmgr/SMP（§14.6）；image_hash 写死 **SHA-256**（D-18） |
| TUNNEL | 无 | 新增域；**明示 UWTP 不保可靠** |
| transfer_id | u32 | u8，0 无效、断连作废（D-17） |
| REPORT record 头 | 4B（time_delta u8×100ms） | **6B（rel_ms u24 LE）**（D-3b）+ 异常解析规则 §13.2 |
| 文件校验 | "CRC32" | 写死 CRC-32/IEEE 802.3 全参数（D-18） |

## 22. V1.0 冻结前待办

1. **S7 采集 Profile**：把 [`../architecture/s7/protocol-zqdata-uhtp-v1.md`](../architecture/s7/protocol-zqdata-uhtp-v1.md) 改写为一页 Profile——选域（CTRL/ALGO/FILE/TUNNEL）、填注册表（algo_id、`dc/`/`log/` 文件命名约定、tunnel_id、profile_id）、S7 GATT 绑定、legacy 双栈迁移；UOF1 文件格式（含时间定义）随 Profile 定稿。
2. 固件评审确认点：OTA 窗口 ACK + 节流参数默认值；文件命名规则定稿。
3. 双端金帧联调 → `protocol_version=1` + `registry_hash` 定版 → 冻结 V1.0。

## 23. r2 修订记录（对照 2026-07-06 完整审查意见，10/10 采纳）

| # | 审查意见 | 落点 |
| --- | --- | --- |
| 1 | 时间戳只保留在在线数据路径 | §5 重写三层制；D-2/D-3/D-3a/D-3b |
| 2 | FILE 域删除采样时间语义 | §15.2 FileEntry 删 `mtime_sec`；§15 开头明示"不关心采样时间" |
| 3 | REPORT record 时间改 u24 rel_ms | §13.1（6B record 头，打包公式 8+Σ(6+len)≤238） |
| 4 | HELLO 增加 profile_id / registry_hash | §12.1 + §20.0（校验规则：不一致只留 CTRL） |
| 5 | OTA/FILE 补 WriteNoRsp 节流 | §14.4 节流铁律 + READY 字段 6/7（D-16） |
| 6 | transfer_id 明确 0 无效、断连作废 | §14.4 / §15.3（D-17） |
| 7 | REPORT_TLV 补异常解析规则 | §13.2（7 条） |
| 8 | CRC32 / SHA-256 写死算法 | D-18；§14.3/§15.3 |
| 9 | TUNNEL 明确不保可靠 | §17 强约束句 |
| 10 | SECURITY 加量产边界 | §9（量产开放 OTA/FILE/DELETE/USER_PROFILE 必须启用鉴权） |

另按意见补：超时参数（D-15）、legacy 误判保护（§11.2 `UWTP_BAD_FRAME` 不回落）、"UWTP FILE 是搬运协议不解释内容"总则句（§5/§0.1）。

## 引用

- 前身：[`UHTP_BLE_Protocol_Design_V4.md`](UHTP_BLE_Protocol_Design_V4.md)（骨架来源）
- 契约草案：[`uwtp_v0.99_draft.proto`](uwtp_v0.99_draft.proto)
- 示例脚本：[`assets/gen_uwtp_examples.py`](assets/gen_uwtp_examples.py)
- 现网协议（迁移对象）：[`../architecture/s7/S7协议共识规格.md`](../architecture/s7/S7协议共识规格.md)；参照系：Zephyr MCUmgr/SMP img_mgmt、MCUboot 双槽模型
