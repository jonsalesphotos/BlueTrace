# ZQDATA·UHTP V1 —— S7 采集数据 BLE 协议设计（离线优先）

> **本文是 ZQDATA 服务协议的重设计稿**：以 [`UHTP_BLE_Protocol_Design_V4.md`](../../UWTP/UHTP_BLE_Protocol_Design_V4.md)（UHTP V4，5B 小头 + 事务域状态机 + Protobuf 协商 + Report TLV + offset 大对象传输；**已被 [UWTP V0.99](../../UWTP/UWTP_BLE_Protocol_Design_V0.99.md) 取代，本文待按其改写为 S7 采集 Profile**）为最佳实践基线，**收敛成采集业务的一个剖面（Profile）**并把离线回传深化到可实现级。
> **范围钉死**（用户口径 2026-07-06）：① **离线数据回传为主体**；② **在线数据控制一律透传**（不重新设计在线流）；③ **可开关的算法结果上传**；④ **可写个人信息**。其余（LOG/OTA/SENSOR_CTRL/SECURITY）保留编号、V1 不实现。
> **状态**：设计稿 V1（2026-07-06），待与固件端评审冻结。机器可读契约：[`zqdata_uhtp_v1_draft.proto`](zqdata_uhtp_v1_draft.proto)。示例包全部由脚本实算（[`assets/gen_zqdata_uhtp_examples.py`](assets/gen_zqdata_uhtp_examples.py)），protobuf wire 与 CRC32 可复验。
> 现网协议（被替代对象）见 [`S7协议共识规格.md`](S7协议共识规格.md)：B2A TEST 0x10-0x12 + 40B→28B 重打包那套；迁移与共存见 §11。深度参考：同目录 [`protocol-b2a.md`](protocol-b2a.md)（B2A 全命令集）、[`protocol-zqdata.md`](protocol-zqdata.md)（现网 zqdata 全量审计）。

| 项 | 内容 |
| --- | --- |
| 帧头 | **5B**：`ver_flags | main_type | sub_type | seq | len`（UHTP BLE Header V1 原样） |
| 传输 | 复用 **ZQDATA GATT 服务**（`45121540-51F2-406E-927A-3E1E183412E0`，RX `…1541` WriteNoRsp / TX `…1542` Notify+CCC），推荐挂在开发仓新 zqdata 传输模块（0x0A10 句柄段，环形缓冲 + txReady 背压） |
| 尺寸 | 帧上限 = min(协商 ATT payload, **243**)；payload ≤ **238**；FILE 数据块默认 **230**B |
| 校验 | 逐帧不加 CRC（BLE 链路层已保证）；**离线文件整档 CRC32(IEEE)**；协商/控制走 Protobuf |
| 字节序 | 帧头与固定二进制字段一律**小端**；Protobuf 自带编码——**全协议告别大端**（对比现网三种字节序并存） |
| 主 B2A 通道 | **不动**。AMDTP `0xFFE0` 上的 B2A（时间/设备信息/OTA/日志）维持现状，本协议只管 ZQDATA 服务 |

---

## 1. 设计目标与范围

### 1.1 V1 做什么 / 不做什么

| 能力 | V1 | 说明 |
| --- | --- | --- |
| 离线会话目录 / 回传 / 删除 / 断点续传 | ✅ **主体** | FILE 域，§6 |
| 对时（离线数据 UTC 锚点的前提） | ✅ | CTRL/TIME_SYNC，§4.3 |
| 算法结果上传（可逐算法开关） | ✅ | CTRL/ALGO_CTRL + ALGO/REPORT_TLV，§5 |
| 写（/读）个人信息 | ✅ | CTRL/USER_PROFILE，§4.4 |
| 在线数据与其控制 | **透传** | TUNNEL 域，汇顶 EVK 字节原样进出，设备不解释，§7 |
| 会话协商 / 能力声明 / 心跳 / 存储查询 | ✅ | CTRL，§4 |
| 实时日志、日志导出（LOG） | ⏸ 保留编号 | 现阶段走 B2A `DEV_CTRL 0x07/0x07` |
| OTA | ⏸ 保留编号 | 走 B2A `FILE_TRANS 0x0F`，不进本协议 |
| 在线高频 raw 正式流（ALGO/RAW_STREAM） | ⏸ 保留编号 | 在线诉求由 TUNNEL 满足；未来要正式化再启用 |
| 加密 / 压缩 / 并发 request_id / bitmap ACK | ⏸ | UHTP V4 §17 的 V1 暂缓项照单全收 |

### 1.2 修复现网协议的哪些问题

| 现网问题（见 [S7协议共识规格.md](S7协议共识规格.md)） | 本设计的解法 |
| --- | --- |
| 同一 TEST Key 在两条通道语义不同（0x01 产测 vs gsensor 帧） | `main_type/sub_type` 事务域分流，通道内自洽 |
| 三种字节序并存（B2A 小端 / PPG 大端 / ECG 小端） | 固定字段全小端 + Protobuf，解析器单一规则 |
| 40B→28B 重打包：ticks 槽位改装 AGC、HR/SpO2 偏移不同、通道数 ABI 冻结 | 文件**原样回传**（offset 传输），格式由 `content_format` 声明；推荐统一 UOF1 格式（§8） |
| 上行无重传/无断点（`g_last_hr_sended_len` 手工水位） | READ_BEGIN `resume_offset` + 窗口 ACK 水位 + 整档 CRC32 |
| 上传与主通道互斥靠全局旗标（`svcSwFlag[6]`） | 域级并发矩阵（§9），CTRL 永远可用 |
| 无能力协商（App 只能硬编码格式） | HELLO/HELLO_RSP + AlgorithmManifest + content_format 注册表 |
| SpO2 单包 228B 顶格无余量 | 打包判断入协议（§5.4），超限 SEG_MODE 分帧 |

## 2. Frame Layout：5B 头（UHTP BLE Header V1）

### 2.1 Frame Overview

| Offset | Size | Field | Description |
| --- | --- | --- | --- |
| 0 | 1B | `ver_flags` | bit7-4 `header_ver`=1；bit3 `EXT_HDR`（V1 恒 0）；bit2 `NEED_RSP`；bit1-0 `SEG_MODE` |
| 1 | 1B | `main_type` | 事务域（§3） |
| 2 | 1B | `sub_type` | 域内小类型（§3） |
| 3 | 1B | `seq` | 本方向递增 0..255 回绕；分段帧连续递增；**响应回显请求 seq** |
| 4 | 1B | `len` | payload 字节数；接收端强校验 `BLE value 实长 == 5 + len` |
| 5 | ≤238B | `payload` | 语义由 main/sub 决定：控制协商 = Protobuf；数据块/对时 = 固定二进制 |

### 2.2 Bit-level memory map

```
Byte Offset:   0                    1           2           3        4        5 ...
              +--------------------+-----------+-----------+--------+--------+-------------+
              | ver_flags          | main_type | sub_type  | seq    | len    | payload     |
              +--------------------+-----------+-----------+--------+--------+-------------+

ver_flags (byte 0)，bit7 → bit0：
              +------+------+------+------+---------+----------+---------+---------+
         bit  |  7   |  6   |  5   |  4   |    3    |    2     |    1    |    0    |
              +------+------+------+------+---------+----------+---------+---------+
              |     header_ver = 0001     | EXT_HDR | NEED_RSP |     SEG_MODE      |
              |        (V1 固定)          |  V1=0   | 要求响应 | 00 SINGLE 01 START|
              |                           |         |          | 10 CONT   11 END  |
              +------+------+------+------+---------+----------+---------+---------+

常见首字节速查：0x10 单包 · 0x14 单包+要求响应 · 0x11/0x12/0x13 分段起/中/止
与 legacy 判别：现网 B2A 帧首字节恒 0xBB（header_ver=0xB 非法）→ 首字节即可分流（§11）
```

<div class="fig">
<svg viewBox="0 0 840 250" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>ZQDATA/UHTP 5 字节帧头布局与 ver_flags 位图</title>
<desc>上排五个字节格：ver_flags、main_type、sub_type、seq、len 加 payload；下排把 ver_flags 的 8 个位展开为 header_ver、EXT_HDR、NEED_RSP、SEG_MODE。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.lna{stroke:var(--accent);stroke-width:1.2;}
</style>
<text x="40" y="20" class="t">UHTP 帧 = 5B 头 + payload（≤238B）；无 magic、无逐帧 CRC（BLE 链路层已保证）</text>
<g class="ms">
  <text x="105" y="44" text-anchor="middle">0</text><text x="235" y="44" text-anchor="middle">1</text>
  <text x="365" y="44" text-anchor="middle">2</text><text x="475" y="44" text-anchor="middle">3</text>
  <text x="565" y="44" text-anchor="middle">4</text><text x="705" y="44" text-anchor="middle">5 …</text>
</g>
<g class="m">
  <rect x="40" y="50" width="130" height="52" class="bxa"/>
  <text x="105" y="72" text-anchor="middle" class="a">ver_flags</text><text x="105" y="90" text-anchor="middle" class="ms">位图↓</text>
  <rect x="170" y="50" width="130" height="52" class="bxa"/>
  <text x="235" y="72" text-anchor="middle" class="a">main_type</text><text x="235" y="90" text-anchor="middle" class="ms">事务域</text>
  <rect x="300" y="50" width="130" height="52" class="bx"/>
  <text x="365" y="72" text-anchor="middle">sub_type</text><text x="365" y="90" text-anchor="middle" class="ms">域内动作</text>
  <rect x="430" y="50" width="90" height="52" class="bx"/>
  <text x="475" y="72" text-anchor="middle">seq</text><text x="475" y="90" text-anchor="middle" class="ms">回显/去重</text>
  <rect x="520" y="50" width="90" height="52" class="bx"/>
  <text x="565" y="72" text-anchor="middle">len</text><text x="565" y="90" text-anchor="middle" class="ms">=payload 长</text>
  <rect x="610" y="50" width="190" height="52" class="bx"/>
  <text x="705" y="72" text-anchor="middle">payload</text><text x="705" y="90" text-anchor="middle" class="ms">Protobuf / 固定二进制</text>
</g>
<line x1="105" y1="102" x2="105" y2="158" class="lna"/>
<text x="40" y="150" class="t">ver_flags 位图（bit7 → bit0）</text>
<g class="m">
  <rect x="40" y="158" width="260" height="52" class="bx"/>
  <text x="170" y="180" text-anchor="middle">header_ver = 0001 (V1)</text><text x="170" y="198" text-anchor="middle" class="ms">bit7–4；≠1 整帧拒收</text>
  <rect x="300" y="158" width="130" height="52" class="bx"/>
  <text x="365" y="180" text-anchor="middle">EXT_HDR</text><text x="365" y="198" text-anchor="middle" class="ms">bit3，V1=0</text>
  <rect x="430" y="158" width="150" height="52" class="bxa"/>
  <text x="505" y="180" text-anchor="middle" class="a">NEED_RSP</text><text x="505" y="198" text-anchor="middle" class="ms">bit2，响应回显 seq</text>
  <rect x="580" y="158" width="220" height="52" class="bxa"/>
  <text x="690" y="180" text-anchor="middle" class="a">SEG_MODE</text><text x="690" y="198" text-anchor="middle" class="ms">bit1-0：00单包 01起 10中 11止</text>
</g>
<text x="40" y="238" class="s">职责边界：SEG_MODE 管"小消息重组"（大 HELLO_RSP/大目录页）；offset 管"大对象进度"（离线文件）；seq 管"短期顺序 + 响应回显"。</text>
</svg>
</div>

### 2.3 seq 与请求/响应约定

- 普通帧：各方向独立递增，检测短期丢包/乱序；分段帧 START/CONT/END 必须 seq 连续。
- `NEED_RSP=1` 的请求 → 对端用**同 main/sub + 回显 seq** 响应；**同一方向同时只允许一个未决请求**（单飞，V1 不做 request_id）。App 侧默认超时 3s（实测标定）。
- 响应 payload 的 Protobuf 消息首字段统一 `uint32 status = 1`（0=OK，proto3 缺省不编码；非 0 = §10 状态码）。

### 2.4 SEG_MODE 使用边界（UHTP V4 §2.3 原样）

适用：大 HELLO_RSP、大目录页、偶发超包的 REPORT_TLV。**不适用**：离线文件数据（走 offset）、TUNNEL 高频流。重组器按 (main,sub) 缓冲，seq 断续即整条丢弃。

## 3. 事务域注册表（TLV：T=`main/sub`，L=`len`，V=`payload`）

| main_type | 域 | V1 | sub_type（V1 实现加粗） |
| --- | --- | --- | --- |
| `0x01` | CTRL | ✅ | **`0x01` SESSION** · `0x02` DEVICE⏸ · `0x03` SENSOR_CTRL⏸ · **`0x04` ALGO_CTRL** · **`0x05` TIME_SYNC** · **`0x06` HEARTBEAT** · **`0x07` STORAGE** · `0x08` SECURITY⏸ · **`0x09` USER_PROFILE**（本设计新增） |
| `0x02` | ALGO | ✅ | `0x01` RAW_STREAM⏸ · **`0x02` REPORT_TLV** · `0x03-0x05`⏸ |
| `0x03` | LOG | ⏸ | UHTP V4 §4.3 编号保留 |
| `0x04` | OTA | ⏸ | UHTP V4 §4.4 编号保留（现阶段 OTA 走 B2A） |
| `0x05` | FILE（**离线数据**） | ✅ | **`0x01` CATALOG · `0x02` READ_BEGIN · `0x03` READ_READY · `0x04` READ_DATA · `0x05` READ_ACK · `0x06` READ_END · `0x07` ABORT · `0x08` DELETE**（新增） |
| `0x06` | TUNNEL（**在线透传**，本设计新增域） | ✅ | sub_type = tunnel_id：**`0x01` GH3220_EVK**（汇顶协议） |
| `0xF0` | VENDOR | ⏸ | 厂商实验 |

> 对 UHTP V4 的两处扩展都走"新增编号、不改既有语义"：FILE 增 `DELETE`（离线数据取走后须显式删）；新增 `TUNNEL` 域（V4 没有透传诉求）。未知 main/sub 处理：**回 `NOT_SUPPORTED`（NEED_RSP 时）或静默丢弃并计数**，绝不断链。

## 4. CTRL 域

CTRL 永远可用（`CTRL_ALWAYS_READY`），任何传输过程中都可以穿插心跳/查询。控制协商 payload 一律 Protobuf（权威定义 [`zqdata_uhtp_v1_draft.proto`](zqdata_uhtp_v1_draft.proto)）。

### 4.1 SESSION：HELLO / HELLO_RSP / BYE

连接建立后 App **必须先 HELLO**，ALGO/FILE 域才开放（TUNNEL 同样要求，设备据此确认对端是新协议 App）。

```proto
message Hello {            // App → 表, NEED_RSP=1
  uint32 protocol_version = 1;   // 本文 = 1
  uint32 max_payload      = 2;   // App 侧按协商 MTU 算出
  uint32 feature_flags    = 3;   // bit0 SEG 重组 bit1 TUNNEL bit2 REPORT ...
  string app_version      = 4;
}
message HelloRsp {         // 表 → App（大包, 允许 SEG_MODE 分帧）
  uint32 status            = 1;
  uint32 protocol_version  = 2;
  uint32 max_payload       = 3;
  uint32 feature_flags     = 4;
  string firmware_version  = 5;
  string device_sn         = 6;
  repeated uint32 supported_main_types = 7 [packed = true];
  repeated AlgorithmManifest algorithms = 8;   // UHTP V4 §7.2
  repeated uint32 content_formats = 9 [packed = true];  // §8 注册表
  StorageInfo storage      = 10;
  TimeAnchorState time_anchor = 11;  // 是否有有效 UTC 锚点
  repeated uint32 tunnel_ids = 12 [packed = true];
}
```

`AlgorithmManifest`（UHTP V4 §7.2 原样收编）：`algorithm_instance_id / algorithm_type / vendor_id / algorithm_version / result_schema_id / output_rate_milli_hz / capability_flags / schema_hash`——REPORT_TLV 的 `algo_id` 即 `algorithm_instance_id`，`result_schema_id` 决定 record.value 用哪个 Protobuf 解。

### 4.2 HEARTBEAT（`0x06`）

`Heartbeat { uptime_s=1, battery_mv=2, flags=3 }`，表→App 周期上报（建议 30s）或 App 查询。作用同 UHTP V4 §8：证明业务线程活着、白捡电量。

### 4.3 TIME_SYNC（`0x05`，固定二进制——离线数据的时间地基）

NTP 式四时戳，**不用 Protobuf**（varint 会抖动包长，定长利于时延对称假设）：

```
ping (App→表, 9B):   [sync_seq u8][t0 u64 LE]              t0 = App 发送时刻(us)
pong (表→App, 25B):  [sync_seq u8][t0 u64][t1 u64][t2 u64]  t1=表收 t2=表回(设备时钟us)
App 记 t3 = 收到时刻:
  offset = ((t1-t0)+(t2-t3))/2      rtt = (t3-t0)-(t2-t1)
```

- 连接后（HELLO 之后）立即做 **3 次取 rtt 最小者**为锚点；
- 设备把「设备时钟 ↔ UTC」锚点**持久化**（掉电重启后离线会话仍可换算绝对时间）；离线目录的 `anchor_utc_ms`（§6.2）即由最近锚点换算；
- 误差 = rtt/2 + 晶振漂移（20ppm × 24h ≈ 1.7s）；要求更高时导入后用最近一次对时二次校正。

### 4.4 USER_PROFILE（`0x09`，写/读个人信息）

```proto
message UserProfile {      // 写: App→表带字段; 读: App→表发空 payload + NEED_RSP
  uint32 height_cm      = 1;
  uint32 weight_kg_x10  = 2;   // 70.5kg = 705, 避免浮点
  uint32 sex            = 3;   // 0 未知 1 男 2 女
  uint32 birth_year     = 4;
  uint32 birth_month    = 5;
  uint32 birth_day      = 6;
  uint32 wear_hand      = 7;   // 0 未知 1 左 2 右
}
message UserProfileRsp { uint32 status = 1; UserProfile profile = 2; }
```

写入落设备 NVM，供算法（卡路里/距离等）取用；响应 `status=0` 即已持久化。

### 4.5 ALGO_CTRL（`0x04`，算法结果上传开关）

```proto
message AlgoReportCtrl {   // App→表, NEED_RSP=1
  repeated uint32 enable_ids = 1 [packed = true];  // 开哪些 algorithm_instance_id, 空=全关
  uint32 min_interval_ms     = 2;                  // 上报节流下限, 0=按算法原生速率
}
message AlgoReportCtrlRsp { uint32 status = 1; repeated uint32 active_ids = 2 [packed = true]; }
```

- 只控制**结果上传**（REPORT_TLV 推流），不控制算法本身的启停——算法由手表自身/在线透传通道驱动；
- `active_ids` 回读**实际生效**集合（含被设备裁剪的），对账语义同 BlueTrace 一贯的 QueryState 纪律；
- 断连自动全关（推流有订阅者才有意义）。

### 4.6 STORAGE（`0x07`）

`StorageInfoReq {}` → `StorageInfo { status, total_bytes, used_bytes, session_count }`。离线拉取前预检、UI 展示存储压力。

## 5. ALGO 域：REPORT_TLV（结果上传）

UHTP V4 §10 原样收编：**外层 TLV 容器省带宽、内层每算法自己的 Protobuf 管语义、Manifest 管映射**。

### 5.1 Bundle 布局（bit 级）

```
payload = Report TLV Bundle:
Byte:   0            1        2        3        4          5
       +------------+--------+--------+--------+----------+--------------+
       | bundle_seq |      base_time_ms u32 LE            | record_count |
       +------------+--------+--------+--------+----------+--------------+
       | ReportRecord[0] | ReportRecord[1] | ...                         |
       +--------------------------------------------------------------- +
ReportRecord:
       +---------+------------+-------+------+----------------+
       | algo_id | time_delta | flags | len  | value          |
       | 1B      | 1B (x100ms)| 1B    | 1B   | len B protobuf |
       +---------+------------+-------+------+----------------+
```

- `bundle_seq`：u8 递增，丢包统计；`base_time_ms`：**设备时钟毫秒**（App 用 TIME_SYNC 锚点换算 UTC）；
- `time_delta` 单位定为 **×100ms**（0–25.5s 覆盖低频结果聚包窗口）——UHTP V4 未定单位，此处冻结；
- `flags`：bit0 VALID · bit1 NEW_RESULT · bit2 WARNING · bit3 ERROR · bit4 HAS_EXT（余保留）；
- `value` = 该算法的 Protobuf bytes，schema 由 Manifest 的 `result_schema_id` 决定，如：

```proto
message HrResultV1   { uint32 hr_bpm = 1; uint32 confidence = 2; uint32 quality = 3; }
message Spo2ResultV1 { uint32 spo2 = 1; uint32 hr_bpm = 2; uint32 quality = 3;
                       uint32 state_flags = 4; uint32 pi_x1000 = 5; }
message WearResultV1 { uint32 wear_state = 1; uint32 confidence = 2; uint32 reason_flags = 3; }
```

### 5.2 打包判断（固件侧，UHTP V4 §10.7）

`used(初始 6) + 4 + value_len ≤ 238` 则并入当前 bundle，否则 flush 新开；单 record 超 232 → SEG_MODE 分帧或精简 schema。REPORT 推流不逐包 ACK，丢了靠 `bundle_seq` 缺口统计。

## 6. FILE 域：离线数据回传（本设计主体）

### 6.1 传输模型

```
Object  = 一个离线会话文件（设备端已按 content_format 落盘）
Chunk   = 一个 READ_DATA 帧携带的数据块（≤230B，READY 定版）
Window  = ack_window_chunks 个 chunk（默认 16）= 一次授信/确认单位
```

- **协商定参**：App 在 READ_BEGIN 提议 `chunk/window`，**设备最终定版**（READ_READY 回真值）——App 提议、Device 决定（UHTP OTA 同款原则）；
- **流控**：表最多领先「最后 ACK 水位」一个窗口；App 每收满一个窗口回 `READ_ACK{next_offset}` 滑动授信（BLE Notify 链路层有序可靠，**ACK 防缓冲溢出、做断点检查点，不承担丢包重传**——断连才产生缺口）；
- **断点续传**：重连后 `READ_BEGIN{resume_offset=已收连续水位}`，设备从该偏移继续（不足一 chunk 对齐由设备处理）；
- **完整性三层**：BLE 链路层 CRC → offset 连续性 → **整档 CRC32(IEEE)**（READY 预告，App 收完全量后校验；不符则整段重拉，文件仍在设备上）；
- **删除**：App 校验通过且用户确认后显式 `DELETE{session_id}`——**传输本身绝不隐式删数据**（对比现网 EOF 即弃）。

### 6.2 sub_type 逐字节

**CATALOG（`0x01`，Protobuf，分页；目录超包由 SEG_MODE 兜底）**

```proto
message SessionCatalogReq { uint32 page = 1; uint32 page_size = 2; }
message SessionCatalogRsp {
  uint32 status = 1;  uint32 total = 2;  uint32 page = 3;
  repeated OfflineSessionMeta sessions = 4;
}
message OfflineSessionMeta {
  uint32 session_id     = 1;
  uint32 kinds          = 2;   // 位图, 与现网 DC_TYPE 对齐: bit0 HR bit1 SPO2 bit2 GS bit4 ECG
  uint32 start_utc      = 3;   // 锚点换算的开始 UTC 秒; 0=从未对时
  uint32 duration_s     = 4;
  uint32 size_bytes     = 5;
  uint32 frame_count    = 6;
  uint32 content_format = 7;   // §8 注册表: 文件内容格式 id
  uint32 crc32          = 8;   // 整档 CRC32(IEEE), 落盘完成时算好
}
```

**READ_BEGIN（`0x02`，App→表，NEED_RSP）→ READ_READY（`0x03`）**

```proto
message ReadBegin {
  uint32 session_id            = 1;
  uint32 resume_offset         = 2;   // 断点续传, 0=从头
  uint32 preferred_chunk_bytes = 3;   // App 提议
  uint32 preferred_ack_window  = 4;
}
message ReadReady {
  uint32 status            = 1;
  uint32 transfer_id       = 2;   // 本次传输句柄, 设备自增
  uint32 total_bytes       = 3;
  uint32 chunk_bytes       = 4;   // 设备定版 = min(App 提议, 238-8, 设备能力)
  uint32 ack_window_chunks = 5;
  uint32 file_crc32        = 6;
  uint32 next_offset       = 7;   // 设备确认的起始偏移(回显/夹紧 resume_offset)
}
```

**READ_DATA（`0x04`，表→App，固定二进制，高吞吐路径禁分段）**

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4B | `transfer_id` u32 LE |
| 4 | 4B | `offset` u32 LE（块首字节在文件内的偏移） |
| 8 | ≤230B | `data` |

**READ_ACK（`0x05`，App→表，固定二进制 9B）**：`transfer_id u32 | next_offset u32 | status u8`。`next_offset` 之前已连续收妥；status≠0 时设备回退到 `next_offset` 重发（如 `NO_MEMORY` 暂停）。

**READ_END（`0x06`，表→App，Protobuf）**：`ReadEnd { transfer_id=1, reason=2 (0 COMPLETE/1 ABORTED/2 DEVICE_ERROR), sent_bytes=3 }`。

**ABORT（`0x07`，双向）**：`Abort { transfer_id=1 }`，立即终止，文件保留。

**DELETE（`0x08`，App→表，NEED_RSP）**：`DeleteReq { session_id=1 }` → `CommonRsp{status}`。READING 中的会话拒删（`BUSY`）。

### 6.3 时序（协商 → 窗口流 → 校验 → 删除）

<div class="fig">
<svg viewBox="0 0 840 470" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>ZQDATA/UHTP V1 离线数据回传时序：目录 → 协商 → 窗口化数据流 → CRC 校验 → 删除</title>
<desc>App 与手表两条泳道：CATALOG 查目录、READ_BEGIN/READY 协商 chunk 与窗口、READ_DATA 连发一个窗口后等 READ_ACK 授信、READ_END 结束、App 校验 CRC32 后 DELETE；断连后凭 resume_offset 续传。</desc>
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
<marker id="za" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--accent)"/></marker>
<marker id="zm" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="var(--muted)"/></marker>
</defs>
<g class="m">
  <rect x="80" y="12" width="150" height="30" class="bx"/><text x="155" y="32" text-anchor="middle">App</text>
  <rect x="610" y="12" width="150" height="30" class="bx"/><text x="685" y="32" text-anchor="middle">S7（ZQDATA 服务）</text>
</g>
<line x1="155" y1="42" x2="155" y2="456" class="lane"/>
<line x1="685" y1="42" x2="685" y2="456" class="lane"/>
<line x1="155" y1="66" x2="685" y2="66" class="fl2" marker-end="url(#zm)"/>
<text x="420" y="58" text-anchor="middle" class="m">FILE/CATALOG req {page,page_size}（NEED_RSP）</text>
<line x1="685" y1="92" x2="155" y2="92" class="fl2" marker-end="url(#zm)"/>
<text x="420" y="84" text-anchor="middle" class="m">CATALOG rsp {total, meta[]: id·kinds·utc·size·format·crc32}</text>
<line x1="155" y1="122" x2="685" y2="122" class="fl" marker-end="url(#za)"/>
<text x="420" y="114" text-anchor="middle" class="m a">READ_BEGIN {session_id, resume_offset, 提议 chunk/窗口}</text>
<line x1="685" y1="148" x2="155" y2="148" class="fl" marker-end="url(#za)"/>
<text x="420" y="140" text-anchor="middle" class="m a">READ_READY {transfer_id, 定版 chunk=230, win=16, total, file_crc32}</text>
<g class="m">
  <line x1="685" y1="180" x2="155" y2="180" class="fl2" marker-end="url(#zm)"/>
  <text x="420" y="172" text-anchor="middle">READ_DATA ×16：{tid, offset, data≤230B}，seq 递增</text>
  <line x1="685" y1="200" x2="155" y2="200" class="fl2" marker-end="url(#zm)"/>
  <line x1="685" y1="220" x2="155" y2="220" class="fl2" marker-end="url(#zm)"/>
</g>
<rect x="620" y="166" width="130" height="62" fill="none" stroke="var(--muted)" stroke-dasharray="4 3"/>
<text x="756" y="196" class="s">一个窗口</text>
<text x="756" y="210" class="s">(16×230B)</text>
<line x1="155" y1="252" x2="685" y2="252" class="fl" marker-end="url(#za)"/>
<text x="420" y="244" text-anchor="middle" class="m a">READ_ACK {tid, next_offset=3680, status=OK} ← 滑动授信</text>
<text x="420" y="274" text-anchor="middle" class="s">…… 窗口循环（表最多领先未 ACK 一个窗口），直至 EOF ……</text>
<line x1="685" y1="300" x2="155" y2="300" class="fl" marker-end="url(#za)"/>
<text x="420" y="292" text-anchor="middle" class="m a">READ_END {tid, reason=COMPLETE, sent_bytes}</text>
<g class="s">
  <text x="60" y="330">── App: CRC32(收到的字节) == READY.file_crc32 ？──</text>
</g>
<line x1="155" y1="356" x2="685" y2="356" class="fl2" marker-end="url(#zm)"/>
<text x="420" y="348" text-anchor="middle" class="m">校验通过（且用户确认）→ FILE/DELETE {session_id}</text>
<line x1="685" y1="380" x2="155" y2="380" class="fl2" marker-end="url(#zm)"/>
<text x="420" y="372" text-anchor="middle" class="m">DELETE rsp {status=OK}</text>
<g class="s">
  <text x="60" y="412">失败路径：断连 → 重连后 READ_BEGIN{resume_offset=已收连续水位} 续传；CRC 不符 → 整段重拉（文件仍在）；</text>
  <text x="60" y="430">状态非法（采集中读/重复 BEGIN）→ CommonRsp{INVALID_STATE / BUSY}；App 中止 → FILE/ABORT。</text>
  <text x="60" y="448">传输期间：TUNNEL 上行暂停，ALGO REPORT 降频，CTRL 心跳/查询照常（并发矩阵 §9）。</text>
</g>
</svg>
</div>

### 6.4 吞吐估算

MTU 247 / CI 30ms / 每 CI 4 包 ≈ 26 kB/s（保守）：46KB 的 HR 会话 ≈ 2s；1h ECG（500Hz×4B ≈ 7.4MB）≈ 5 分钟；2M PHY + DLE + CI 15ms 理想可达 60–100 kB/s。进度条 = `next_offset / total_bytes`。

## 7. TUNNEL 域：在线数据控制透传

```
App → 表:  [5B 头 main=0x06 sub=tunnel_id] + 原始厂商协议字节  → 设备原样递给厂商协议栈
表 → App:  厂商协议栈产出的字节 原样打进同款帧 → App 递给厂商 SDK/解析器
```

- `tunnel_id 0x01 = GH3220_EVK`（汇顶协议，下行进 `Gh3x2xDemoProtocolProcess` 同款入口）；
- **设备与本协议均不解释 payload**——在线测量的启停/参数/数据全部由隧道两端的厂商协议自理；
- 厂商字节流超过单帧 238B 时由**发送方按字节切段**（隧道不保证消息边界，与串口语义一致——厂商协议自带帧同步）；
- 强流控：TX 队列满即丢并计数（实时流可丢）；`bundle` 优先级低于 FILE 传输（§9）。

> 这就是「在线数据控制透传」的全部：**不给在线路径发明第二套语义**，未来若要正式化在线流，再启用 ALGO/RAW_STREAM 编号即可，不影响本剖面。

## 8. 离线文件格式：`content_format` 注册表 + 统一格式 UOF1

传输层对内容**不透明**（offset 搬字节），格式由目录项 `content_format` 声明，App 据此挑解析器——**协议稳定性与文件格式演进解耦**。

| content_format | 名称 | 说明 |
| --- | --- | --- |
| `0x01` | ECG1_V2 | 现网 `ecg_raw.dat`（32B 头 + 8B 帧头流 + ACC 段，小端；见 [S7协议共识规格.md](S7协议共识规格.md) §6） |
| `0x02` / `0x03` | PPGHR40 / PPGSPO40 | 现网 40B 落盘帧（28B 大端主体 + 12B AGC 位域）——**原样整档回传，不再 40→28 重打包** |
| `0x04` | GS18 | 现网 gsensor 18B 大端帧 |
| `0x10` | **UOF1** | 统一离线格式（推荐固件迁移目标，见下） |

### 8.1 UOF1（Unified Offline Format，全小端，append-only）

```
[32B 文件头][8B 流描述 × stream_count][帧流: (8B 帧头 + payload) 交错 ...]

文件头 (32B):
  0   4  magic        "UOF1" (55 4F 46 31)
  4   2  version      1
  6   2  header_size  32 + 8*stream_count
  8   4  session_id
  12  4  start_utc            0=未对时
  16  4  anchor_tick          对时锚点: 设备 tick(ms)
  20  8  anchor_utc_ms        对应 UTC 毫秒 (0=无锚点)
  28  1  kinds 位图           bit0 HR bit1 SPO2 bit2 GS bit4 ECG
  29  1  stream_count
  30  2  保留 =0

流描述 (8B × stream_count):
  0   1  stream_id     帧头引用
  1   1  sensor_kind   1 PPG 2 ACC 3 ECG 4 TEMP ...
  2   1  format_id     样本格式注册表 (UHTP V4 §9): 0x03 ACC_I16_XYZ / 0x02 PPG1_S24 /
  3   1  channel_count               0x07 PPG3_S24_ACC_I16 / 0x08 ECG_S24_IN_U32 ...
  4   4  sample_rate_mhz  u32 LE 毫赫兹

帧头 (8B, 各流交错, 沿用现网 ECG v2 已验证的帧步进设计):
  0   1  stream_id
  1   1  seq           该流内递增 u8 回绕 → 离线丢帧检测
  2   2  len u16 LE    payload 字节数
  4   4  tick u32 LE   写帧时刻设备 tick(ms), 与 anchor_tick 同源
```

- 一个会话一个文件、多流交错、自描述——ECG+ACC、HR+SpO2+GS 任意组合不再各造格式；
- 掉电安全同现网：append-only，最多损失尾部一帧，解析器容忍截断；
- 时间轴：`utc(帧) = anchor_utc_ms + (tick − anchor_tick)`，全部样本可换算绝对时间。

<div class="fig">
<svg viewBox="0 0 840 240" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>UOF1 统一离线文件布局：32B 文件头 + 流描述表 + 多流交错帧</title>
<desc>横向条带：文件头、stream 描述表、随后 ECG/ACC 等多流帧交错排列，每帧 8B 头标注 stream_id、seq、len、tick。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;}
.ms{fill:var(--muted);font-size:9.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bxa{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
</style>
<text x="40" y="20" class="t">UOF1 = 32B 文件头 + 8B×N 流描述 + 多流交错帧（全小端，append-only）</text>
<g class="m">
  <rect x="40" y="34" width="140" height="52" class="bxa"/>
  <text x="110" y="56" text-anchor="middle" class="a">文件头 32B</text>
  <text x="110" y="74" text-anchor="middle" class="ms">"UOF1"·session·锚点</text>
  <rect x="180" y="34" width="150" height="52" class="bxa"/>
  <text x="255" y="56" text-anchor="middle" class="a">流描述 ×N</text>
  <text x="255" y="74" text-anchor="middle" class="ms">id·kind·format·rate</text>
  <rect x="330" y="34" width="120" height="52" class="bx"/>
  <text x="390" y="56" text-anchor="middle">ECG 帧</text><text x="390" y="74" text-anchor="middle" class="ms">s0 seq0</text>
  <rect x="450" y="34" width="120" height="52" class="bx"/>
  <text x="510" y="56" text-anchor="middle">ECG 帧</text><text x="510" y="74" text-anchor="middle" class="ms">s0 seq1</text>
  <rect x="570" y="34" width="110" height="52" class="bx"/>
  <text x="625" y="56" text-anchor="middle">ACC 帧</text><text x="625" y="74" text-anchor="middle" class="ms">s1 seq0</text>
  <rect x="680" y="34" width="120" height="52" class="bx"/>
  <text x="740" y="56" text-anchor="middle">ECG 帧 …</text><text x="740" y="74" text-anchor="middle" class="ms">s0 seq2</text>
</g>
<text x="40" y="118" class="t">8B 帧头（各流交错，seq 按流独立递增）</text>
<g class="m">
  <rect x="40" y="128" width="150" height="52" class="bx"/>
  <text x="115" y="150" text-anchor="middle">stream_id u8</text><text x="115" y="168" text-anchor="middle" class="ms">查流描述表</text>
  <rect x="190" y="128" width="150" height="52" class="bxa"/>
  <text x="265" y="150" text-anchor="middle" class="a">seq u8</text><text x="265" y="168" text-anchor="middle" class="ms">流内递增, 缺号=丢帧</text>
  <rect x="340" y="128" width="170" height="52" class="bx"/>
  <text x="425" y="150" text-anchor="middle">len u16 LE</text><text x="425" y="168" text-anchor="middle" class="ms">payload 字节数</text>
  <rect x="510" y="128" width="290" height="52" class="bx"/>
  <text x="655" y="150" text-anchor="middle">tick u32 LE</text><text x="655" y="168" text-anchor="middle" class="ms">utc = anchor_utc_ms + (tick − anchor_tick)</text>
</g>
<text x="40" y="212" class="s">迁移路径：V1 先用 content_format 声明现网格式（ECG1_V2/PPGHR40/GS18）原样回传；固件择机切 UOF1，App 解析器按注册表并存。</text>
<text x="40" y="230" class="s">对比现网：不再 40→28 重打包丢 AGC、不再三种字节序并存、ECG/ACC 不再分段回填 offset。</text>
</svg>
</div>

## 9. 状态机与并发矩阵

### 9.1 FILE 域状态机

```
                 READ_BEGIN(合法 session, 非采集中)
   [FILE_IDLE] ───────────────────────────────▶ [FILE_PREPARED] ── READ_READY 已发
        ▲                                             │ 首个 READ_DATA 发出
        │ READ_END(COMPLETE/ERROR) / ABORT / 断连      ▼
        └───────────────────────────────────── [FILE_READING]
                                                      │ 窗口满未获 ACK → 暂停等授信
                                                      │ ACK(status≠0) → 回退 next_offset
   DELETE: 仅 FILE_IDLE 且目标 session 未在读 → 执行; 否则 BUSY
   采集中(该 session 仍在写) READ_BEGIN → INVALID_STATE
```

### 9.2 并发矩阵（同一连接内）

| 进行中 ↓ \ 请求 → | CTRL | ALGO REPORT 推流 | FILE 传输 | TUNNEL |
| --- | --- | --- | --- | --- |
| **空闲** | ✅ | ✅（已 enable） | ✅ | ✅ |
| **FILE 传输中** | ✅（心跳/查询照常） | ⚠ 降频（min_interval 强制 ≥1s） | ❌ 第二路 `BUSY` | ⚠ 上行暂停/丢弃计数，下行放行 |
| **TUNNEL 活跃** | ✅ | ✅ | ✅（开始后 TUNNEL 上行让位） | — |

原则：**CTRL 永远可用**（对比现网 `svcSwFlag[6]` 一刀切）；带宽大户（FILE）独占期间其余数据面让位；让位策略由设备执行、App 不感知。

### 9.3 REPORT 推流状态

`disabled →(ALGO_CTRL enable)→ enabled →(断连/enable 空集)→ disabled`。未 enable 收到 REPORT = 固件 bug，App 丢弃计数。

## 10. 状态码（UHTP V4 §15 原样收编）

`0x00 OK · 0x01 INVALID_REQUEST · 0x02 NOT_SUPPORTED · 0x03 INVALID_STATE · 0x04 BUSY · 0x05 INVALID_PARAM · 0x06 NO_MEMORY · 0x07 NO_SPACE · 0x08 CRC_ERROR · 0x09 HASH_MISMATCH · 0x0A AUTH_FAILED · 0x0B TIMEOUT · 0x0C RETRY_LATER · 0x0D SERVICE_REJECTED · 0x0E UNSUPPORTED_SCHEMA · 0x0F UNSUPPORTED_FORMAT · 0x10 TOO_LARGE`

错误处理汇总：

| 错误 | 处理 |
| --- | --- |
| `header_ver ≠ 1` | 整帧拒收计数（首字节 `0xBB` 即 legacy B2A，见 §11） |
| `len ≠ 实长-5` | 丢帧计数 |
| 未知 main/sub | NEED_RSP → `NOT_SUPPORTED`；否则静默跳过计数 |
| SEG 重组 seq 断续 | 整条丢弃 |
| 状态机拒绝 | `INVALID_STATE / BUSY / RETRY_LATER` |
| 文件 CRC32 不符 | App 整段重拉（DELETE 之前文件恒在） |
| READ_ACK 带错误 | 设备回退 `next_offset` 或 ABORT |
| protobuf 解析失败 | `INVALID_PARAM`；未知字段按 proto3 忽略（向后兼容） |

## 11. 版本管理与 legacy 共存

- **分层版本**（UHTP V4 §16）：`header_ver`（每帧，只管头解析）→ `protocol_version`（HELLO 协商）→ `feature_flags`（能力开关，区分 optional/critical）→ `result_schema_id` / `content_format`（数据语义）。proto3 演进只增不改 tag。
- **与现网 legacy 的判别（关键）**：legacy B2A 帧首字节恒 `0xBB`（header_ver=0xB 非法）；UHTP V1 帧首字节高半字节恒 `0x1`。**首字节完全不相交** → 固件可低成本双栈：RX 首字节 `0xBB` 进旧 B2A 解析器，`0x1?` 进 UHTP，其余丢弃计数。
- **App 探测**：连接 ZQDATA 服务后发 HELLO，1s×2 超时未回 → 判定 legacy 固件，回落现网协议（BlueTrace 侧即换 Profile/解析器，上层不动）。
- 冻结顺序：本稿评审 → `zqdata_uhtp_v1_draft.proto` 冻结 → 固件实现（可先只做 FILE+CTRL 最小集）→ 双端金帧联调。

## 12. Example raw packet + decode

> 全部由 [`assets/gen_zqdata_uhtp_examples.py`](assets/gen_zqdata_uhtp_examples.py) 实算（protobuf wire + CRC32），len 自洽断言通过。示例 UTC = 1783324800（2026-07-06 08:00:00Z）。

### 12.1 E1 · TIME_SYNC（固定二进制）

```
ping (14B): 14 01 05 21 09 | 01 | 00 60 5B A0 9D 37 06 00
   hdr: ver1+NEED_RSP, CTRL/TIME_SYNC, seq=0x21, len=9
   payload: sync_seq=1, t0=1750000000000000 us (u64 LE)
pong (30B): 10 01 05 21 19 | 01 | t0 | t1 | t2      ← 回显 seq=0x21
   offset=((t1-t0)+(t2-t3))/2, rtt=(t3-t0)-(t2-t1)；3 次取 rtt 最小者为锚点
```

### 12.2 E2 · USER_PROFILE 写入

```
req (20B): 14 01 09 22 0F | 08 AF 01 | 10 C1 05 | 18 01 | 20 C9 0F | 28 06 | 30 0F
   (1)height=175  (2)weight_x10=705  (3)sex=1  (4..6)生日 1993-06-15
rsp (5B):  10 01 09 22 00        ← CommonRsp{status=0}, proto3 零值不编码 → 空 payload
```

### 12.3 E3 · 打开算法结果上传 + REPORT_TLV

```
ALGO_CTRL req (12B): 14 01 04 23 07 | 0A 02 01 02 | 10 E8 07
   enable_ids=[1,2](packed), min_interval_ms=1000

REPORT_TLV (34B): 10 02 02 36 1D | 05 | 87 D6 12 00 | 02 |
                  01 00 03 06 [08 48 10 5A 18 50] | 02 0A 03 09 [08 62 10 48 18 55 28 DC 0B]
   bundle: seq=5, base_time_ms=1234567, count=2
   rec1: algo_id=1(HR)  dt=0      flags=VALID|NEW len=6 → HrResultV1{hr=72,conf=90,quality=80}
   rec2: algo_id=2(SpO2) dt=10(1s) flags=VALID|NEW len=9 → Spo2ResultV1{spo2=98,hr=72,quality=85,pi=1.5%}
```

### 12.4 E4 · 离线回传全流程（46080B 文件，CRC32=0x1FE5C89B）

```
CATALOG req (7B):  14 05 01 40 02 | 10 08                    page=0, page_size=8
CATALOG rsp (37B): 10 05 01 40 20 | 10 01 | 22 1C [08 07 10 01 18 80 C1 AD D2 06 20 AC 02
                                            28 80 E8 02 30 80 09 38 02 40 9B 91 97 FF 01]
   total=1; meta: session_id=7, kinds=HR, start_utc=1783324800, dur=300s,
                  size=46080, frames=1152, content_format=0x02 PPGHR40, crc32=0x1FE5C89B

READ_BEGIN (12B):  14 05 02 41 07 | 08 07 18 E6 01 20 10     session=7, 提议 chunk=230 win=16
READ_READY (22B):  10 05 03 41 11 | 10 01 18 80 E8 02 20 E6 01 28 10 30 9B 91 97 FF 01
   transfer_id=1, total=46080, chunk=230(定版), win=16, file_crc32

READ_DATA#0 (243B): 10 05 04 00 EE | 01 00 00 00 | 00 00 00 00 | data 230B
READ_DATA#1 (243B): 10 05 04 01 EE | 01 00 00 00 | E6 00 00 00 | data 230B   ← offset=230, seq 递增
READ_ACK    (14B):  10 05 05 50 09 | 01 00 00 00 | 60 0E 00 00 | 00          ← next_offset=3680(16 片), OK
READ_END    (11B):  10 05 06 52 06 | 08 01 18 80 E8 02          reason=COMPLETE, sent=46080
DELETE req  (7B):   14 05 08 53 02 | 08 07                      校验+确认后显式删 session 7
```

### 12.5 E5 · TUNNEL 透传 与 E6 · 心跳

```
TUNNEL (11B):    10 06 01 60 06 | AA 11 02 00 5A 01      ← 汇顶协议字节原样, 设备不解释
HEARTBEAT (13B): 10 01 06 70 08 | 08 90 1C 10 8C 1F 18 01   uptime=3600s, batt=3980mV, 充电中
```

## 13. V1 最小实现清单与开放问题

**固件 V1 必做**：5B 头收发 + 双栈首字节分流 → CTRL（HELLO/HELLO_RSP、TIME_SYNC、USER_PROFILE、ALGO_CTRL、STORAGE、HEARTBEAT）→ FILE 全 8 个 sub → ALGO/REPORT_TLV → TUNNEL/GH3220_EVK → 状态码。
**App（BlueTrace）V1 必做**：ZQDATA Profile 换新解析器（legacy 探测回落）、单飞请求队列、SEG 重组器、FILE 下载器（窗口 ACK + 断点 + CRC32）、REPORT 解码（Manifest→schema 映射）、hexlog 纪律不变。

| # | 开放问题（冻结前与固件拍板） | 倾向 |
| --- | --- | --- |
| O-1 | FILE 传输期间 TUNNEL 上行暂停还是降速 | 暂停（简单、独占带宽） |
| O-2 | `session_id` 掉电持久性 | Flash 自增 u32 不回绕 |
| O-3 | 存储满策略 | 环形覆盖最旧 + HEARTBEAT flags 置位上报 |
| O-4 | UOF1 何时切换 | V1 先 legacy content_format 原样回传，UOF1 作为固件下一版落盘目标 |
| O-5 | DELETE 是否要二次确认 token | V1 不要（App 侧 CRC 校验 + 用户确认已够） |
| O-6 | TIME_SYNC 锚点持久化粒度 | 每次对时都写 NVM（低频，磨损可忽略） |
| O-7 | REPORT `time_delta` 溢出（>25.5s） | 拆新 bundle（base_time_ms 重锚） |
| O-8 | 配对/加密 | 内部采集工具定位，V1 Just Works；SECURITY 编号已留 |

## 14. 引用

- 设计基线：[`Docs/UWTP/UHTP_BLE_Protocol_Design_V4.md`](../../UWTP/UHTP_BLE_Protocol_Design_V4.md)（UHTP V4：5B 头 / 事务域 / Report TLV / offset 传输 / 分层版本，本文 §2/§5/§10/§11 大量原样收编）；**后继定稿 = [`Docs/UWTP/UWTP_BLE_Protocol_Design_V0.99.md`](../../UWTP/UWTP_BLE_Protocol_Design_V0.99.md)**
- 被替代的现网协议：[`S7协议共识规格.md`](S7协议共识规格.md)（B2A + DC 0x10-0x12 + 40B→28B 重打包）；全量审计：[`protocol-b2a.md`](protocol-b2a.md)、[`protocol-zqdata.md`](protocol-zqdata.md)
- 机器可读契约：[`zqdata_uhtp_v1_draft.proto`](zqdata_uhtp_v1_draft.proto)（本文所有 Protobuf 消息的权威定义）
- 示例生成脚本：[`assets/gen_zqdata_uhtp_examples.py`](assets/gen_zqdata_uhtp_examples.py)
- 传输层落点：开发仓 `product/apollo_eiot/zqdata/`（新 zqdata 模块：环 + 双线程 + txReady，正好是本协议的收发骨架）
- BlueTrace 侧接入：[`../02_parser_registry_design.md`](../../architecture/02_parser_registry_design.md)（Profile/解析器注册架构）；相关草案 [`../03_collect_protocol_design.md`](../自研协议线_v0/03_collect_protocol_design.md)（BTCP/1，其对时/credit/离线镜像思想已并入本文 §4.3/§6）
