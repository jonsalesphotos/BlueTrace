# UHTP 嵌入式健康设备 BLE 协议设计 V4

> UHTP = Unified Health Transport Protocol  
> 适用场景：智能手表 / 可穿戴设备 / PPG + ACC 高频采集 / 多算法结果输出 / 厂商算法联调 / 实时日志 / 日志导出 / OTA / 文件传输。  
> 本版本是基于讨论后的收敛版：**5B 小包头 + 事务域 main_type + 域内 sub_type + 状态机 + Protobuf 协商 + Report TLV + 大对象 offset 传输**。

---

## 0. 核心结论

UHTP 不应该把所有东西都塞进一种 payload，也不应该把所有控制逻辑都塞进一个巨大的 MGMT 模块。

最终设计：

```text
BLE Notify / Write Value
└── UHTP Frame
    ├── Header 5B
    │   ├── ver_flags
    │   ├── main_type
    │   ├── sub_type
    │   ├── seq
    │   └── len
    └── Payload
        ├── CTRL：控制 / 协商 / 查询，大部分使用 Protobuf
        ├── ALGO：raw / report / vendor io / debug
        ├── LOG：实时日志 / 日志导出
        ├── OTA：升级协商 / 数据 / ACK / 状态切换
        └── FILE：文件 / debug dump 导出
```

一句话：

> **Header 管分流，main_type 管事务域，sub_type 管域内小类型，payload 管具体数据；控制协商用 Protobuf，算法结果用 TLV 容器 + 独立 Protobuf，大对象用 offset 传输。**

---

## 1. BLE 传输假设

UHTP V4 主要面向 BLE Notify / Write。

常见 BLE 条件：

```text
ATT MTU ≈ 247
ATT Value Payload ≈ 244B
工程上可按 243B 或 244B 设计
```

本文采用保守值：

```text
BLE Value Max = 243B
UHTP Header = 5B
Payload Max = 238B
```

运行时应根据实际 MTU 计算：

```text
max_payload = negotiated_att_value_payload_size - sizeof(uhtp_header)
```

BLE 已提供：

```text
1. 单个 Value 的边界
2. 本次收到的长度
3. 链路层校验
4. GATT Characteristic 级数据通道
```

因此 BLE 版 UHTP Header 不放 `magic`、不强制 frame CRC、不需要 uint16 length。

---

## 2. UHTP BLE Header V1

### 2.1 Header 格式

```text
+------------+-----------+----------+--------+--------+----------------+
| ver_flags  | main_type | sub_type | seq    | len    | payload        |
| 1B         | 1B        | 1B       | 1B     | 1B     | 0~238B         |
+------------+-----------+----------+--------+--------+----------------+
```

C 结构：

```c
typedef struct __attribute__((packed)) {
    uint8_t ver_flags;   // header version + flags
    uint8_t main_type;   // CTRL / ALGO / LOG / OTA / FILE
    uint8_t sub_type;    // meaning depends on main_type
    uint8_t seq;         // frame sequence
    uint8_t len;         // payload length
} uhtp_ble_hdr_v1_t;
```

---

### 2.2 `ver_flags`

```text
bit7~bit4: header_ver
bit3:      EXT_HDR
bit2:      NEED_RSP
bit1~bit0: SEG_MODE
```

| Bit | 名称 | 说明 |
|---:|---|---|
| bit7~4 | `header_ver` | Header 格式版本，V1 = 1 |
| bit3 | `EXT_HDR` | 是否存在扩展头，V1 默认不用 |
| bit2 | `NEED_RSP` | 是否希望对方返回业务响应 |
| bit1~0 | `SEG_MODE` | 单包 / 连续包起始 / 中间 / 结束 |

`header_ver` 只表示 Header 如何解析，不表示协议版本、算法版本、Schema 版本或固件版本。

---

### 2.3 `SEG_MODE`

```text
00 = SINGLE
01 = START
10 = CONT
11 = END
```

用途：

```text
把一个超过单个 BLE Value 的逻辑消息重新拼接出来
```

适合：

```text
大的 HELLO_RSP
大的算法 manifest
大的 Protobuf 查询结果
偶发较大的 Report TLV
```

不适合：

```text
OTA image 传输
日志导出
文件导出
高频 PPG / ACC raw stream
```

职责边界：

```text
SEG_MODE 负责“小消息重组”
offset 负责“大对象进度”
seq 负责“短期包顺序和响应回显”
```

---

### 2.4 `seq`

`seq` 为 `uint8_t`，0~255 循环。

用途：

```text
普通包：本方向递增，用于短期丢包/乱序检测
分段包：START / CONT / END 必须连续递增
响应包：回显请求包 seq
```

例如：

```text
Request:  seq = 0x21, NEED_RSP = 1
Response: seq = 0x21
```

V1 约束：

```text
同一方向同一时间只允许一个等待响应的请求
```

未来若需要并发请求，可在 payload 内增加 `request_id`。

---

### 2.5 `len`

`len` 表示当前 payload 长度。

接收端检查：

```text
actual_ble_value_len == 5 + len
```

`len` 不表示 OTA 镜像总长度、日志对象总长度、分段消息总长度或采样总数。

---

## 3. main_type 事务域设计

`main_type` 不按“具体包格式”分，而按**事务域**分。

事务域意味着：

```text
1. 有自己的状态机
2. 有自己的协商流程
3. 有自己的并发规则
4. 有自己的拒绝策略
5. 有自己的 payload 语义
```

V1 定义：

```text
0x00 RESERVED
0x01 CTRL
0x02 ALGO
0x03 LOG
0x04 OTA
0x05 FILE
0xF0 VENDOR / EXPERIMENTAL
```

| main_type | 名称 | 职责 |
|---:|---|---|
| `0x01` | CTRL | 控制、协商、查询、配置、心跳 |
| `0x02` | ALGO | 算法相关数据，raw / report / vendor io / debug |
| `0x03` | LOG | 实时日志、日志导出 |
| `0x04` | OTA | 固件升级 |
| `0x05` | FILE | 文件 / debug dump 导出 |
| `0xF0` | VENDOR | 厂商私有扩展 |

---

## 4. sub_type 设计

`sub_type` 的含义由 `main_type` 决定。

```text
main_type 管“哪个事务域”
sub_type 管“事务域内的哪种小类型”
payload 管“具体参数和数据”
```

### 4.1 CTRL sub_type

```text
0x01 SESSION
0x02 DEVICE
0x03 SENSOR_CTRL
0x04 ALGO_CTRL
0x05 TIME_SYNC
0x06 HEARTBEAT
0x07 STORAGE
0x08 SECURITY
```

CTRL payload 通常使用 Protobuf。

---

### 4.2 ALGO sub_type

```text
0x01 RAW_STREAM
0x02 REPORT_TLV
0x03 VENDOR_IO_BUNDLE
0x04 DEBUG_OBSERVATION
0x05 QUALITY_METRIC
```

---

### 4.3 LOG sub_type

```text
0x01 LIVE_EVENT
0x02 EXPORT_BEGIN
0x03 EXPORT_READY
0x04 EXPORT_DATA
0x05 EXPORT_ACK
0x06 EXPORT_END
0x07 QUERY
0x08 ERASE
0x09 ABORT
```

---

### 4.4 OTA sub_type

```text
0x01 IMG_LIST
0x02 UPLOAD_BEGIN
0x03 UPLOAD_READY
0x04 UPLOAD_DATA
0x05 UPLOAD_ACK
0x06 UPLOAD_END
0x07 IMG_STATE
0x08 RESET
0x09 ABORT
```

---

### 4.5 FILE sub_type

```text
0x01 FILE_LIST
0x02 READ_BEGIN
0x03 READ_READY
0x04 READ_DATA
0x05 READ_ACK
0x06 READ_END
0x07 ABORT
```

---

## 5. 事务域状态机

### 5.1 总原则

```text
Header 负责分流
main_type 选择事务域
sub_type 选择域内动作
domain state machine 判断动作是否允许
payload 负责具体参数和数据
```

如果当前状态不允许某个 sub_type，应返回：

```text
INVALID_STATE
BUSY
RETRY_LATER
SERVICE_REJECTED
```

---

### 5.2 CTRL 状态

CTRL 通常随时可用：

```text
CTRL_ALWAYS_READY
```

允许穿插：

```text
心跳
查询电量
查询设备状态
查询 OTA 进度
查询日志状态
```

---

### 5.3 ALGO 状态

```text
ALGO_IDLE
ALGO_CONFIGURING
ALGO_READY
ALGO_RUNNING
ALGO_STOPPING
ALGO_ERROR
```

典型流程：

```text
1. CTRL / SESSION HELLO
2. CTRL / SENSOR_CTRL SET_STREAM_CONFIG
3. CTRL / ALGO_CTRL SET_ALGO_CONFIG
4. CTRL / ALGO_CTRL START_ALGO
5. ALGO RAW_STREAM / REPORT_TLV / VENDOR_IO_BUNDLE
6. CTRL / ALGO_CTRL STOP_ALGO
```

未进入 READY/RUNNING 状态时收到 ALGO data，应拒绝或丢弃。

---

### 5.4 LOG 状态

```text
LOG_IDLE
LOG_LIVE_RUNNING
LOG_EXPORT_PREPARED
LOG_EXPORT_RUNNING
LOG_ERROR
```

实时日志可丢、不 ACK。

日志导出必须先协商：

```text
EXPORT_BEGIN
EXPORT_READY
EXPORT_DATA
EXPORT_ACK
EXPORT_END
```

---

### 5.5 OTA 状态

```text
OTA_IDLE
OTA_LISTED
OTA_PREPARED
OTA_TRANSFERRING
OTA_VERIFYING
OTA_READY_TO_APPLY
OTA_WAIT_REBOOT
OTA_ERROR
```

OTA 必须严格走状态机：

```text
IMG_LIST
UPLOAD_BEGIN
UPLOAD_READY
UPLOAD_DATA / UPLOAD_ACK
UPLOAD_END
IMG_STATE(TEST)
RESET
CONFIRM
```

---

### 5.6 FILE 状态

```text
FILE_IDLE
FILE_PREPARED
FILE_READING
FILE_ERROR
```

文件导出流程：

```text
FILE_LIST
READ_BEGIN
READ_READY
READ_DATA
READ_ACK
READ_END
```

---

## 6. Payload 编码原则

### 6.1 默认使用 Protobuf 的部分

```text
CTRL 协商 / 控制 / 查询
DEVICE 状态
SESSION HELLO
SENSOR_CTRL 配置
ALGO_CTRL 配置
OTA BEGIN / READY / END / STATE
LOG EXPORT BEGIN / READY / END
FILE READ BEGIN / READY / END
错误响应
```

### 6.2 使用裸字节 / 固定二进制的部分

```text
ALGO RAW_STREAM
ALGO VENDOR_IO_BUNDLE
OTA UPLOAD_DATA
LOG EXPORT_DATA
FILE READ_DATA
高频实时数据
```

### 6.3 混合方式

算法结果使用：

```text
外层 Report TLV Bundle
内层每种算法自己的 Protobuf bytes
```

---

## 7. CTRL 与 Session 协商

### 7.1 HELLO

使用：

```text
main_type = CTRL
sub_type  = SESSION
```

用于连接开始阶段的能力协商。

HELLO / HELLO_RSP 应包含：

```text
协议版本
最大 payload
支持的 main_type
支持的 sub_type
设备信息
固件版本
bootloader 版本
算法清单
每种算法的 schema
stream format registry
vendor format registry
log dictionary
时间基准
安全能力
```

---

### 7.2 Algorithm Manifest

每个算法实例都需要独立声明。

```proto
message AlgorithmManifest {
  uint32 algorithm_instance_id = 1;
  string algorithm_type = 2;       // HR / SPO2 / WEAR / STEP / ACTIVITY
  string vendor_id = 3;            // INTERNAL / GH / ...
  string algorithm_version = 4;
  string result_schema_id = 5;     // e.g. spo2.gh.result.v2
  string debug_schema_id = 6;
  uint32 output_rate_milli_hz = 7;
  uint32 capability_flags = 8;
  uint32 schema_hash = 9;
}
```

示例：

```text
algorithm_instance_id = 1
algorithm_type        = HR
vendor_id             = INTERNAL
result_schema_id      = hr.internal.result.v1

algorithm_instance_id = 2
algorithm_type        = SPO2
vendor_id             = GH
result_schema_id      = spo2.gh.result.v2

algorithm_instance_id = 3
algorithm_type        = WEAR
vendor_id             = INTERNAL
result_schema_id      = wear.internal.result.v1
```

---

## 8. 心跳包

心跳使用：

```text
main_type = CTRL
sub_type  = HEARTBEAT
```

payload 可以是 Protobuf：

```proto
message Heartbeat {
  uint32 uptime_s = 1;
  uint32 battery_mv = 2;
  uint32 flags = 3;
}
```

作用：

```text
1. 证明协议任务还活着
2. 统计业务层延迟
3. 周期性轻量状态上报
4. 发现 BLE 连接还在但业务线程卡死
```

心跳不是 BLE 连接保活的唯一依据。

---

## 9. ALGO RAW_STREAM

用于高频原始数据：

```text
PPG
ACC
TEMP
PPG + ACC 对齐 sample group
```

不使用 Protobuf。

推荐 payload：

```text
stream_id        u8
format_id        u8
sample_count     u8
base_time_delta  u8/u16 可选
sample_data      bytes
```

常见 format_id：

```text
0x01 PPG1_U24
0x02 PPG1_S24
0x03 ACC_I16_XYZ
0x04 PPG1_U24_ACC_I16
0x05 PPG3_U24_ACC_I16
0x06 TEMP_I16_CENTI
0x07 PPG3_S24_ACC_I16
```

高频 raw stream 不逐包 ACK。

丢包通过：

```text
seq gap
sample_count
base_time
```

统计采集质量。

---

## 10. ALGO REPORT_TLV

### 10.1 设计动机

每种算法都有自己的 Protobuf Schema。

如果外层也用 Protobuf ReportBundle，打包大小判断不够直观。

因此算法结果采用：

```text
Report TLV Bundle + inner Protobuf
```

即：

```text
外层 TLV 负责省带宽和打包
内层 Protobuf 负责算法语义
Session Manifest 负责 schema 映射
```

---

### 10.2 REPORT_TLV 总结构

```text
main_type = ALGO
sub_type  = REPORT_TLV
payload   = Report TLV Bundle
```

Report TLV Bundle：

```text
+-------------+---------------+--------------+
| bundle_seq  | base_time_ms  | record_count |
| 1B          | 4B            | 1B           |
+-------------+---------------+--------------+
| ReportRecord[0]                            |
| ReportRecord[1]                            |
| ...                                        |
+--------------------------------------------+
```

Bundle Header V1：

```text
bundle_seq:    u8
base_time_ms:  u32
record_count:  u8
```

固定开销：

```text
6B
```

---

### 10.3 Report Record

```text
+---------+------------+-------+------+----------------+
| algo_id | time_delta | flags | len  | value          |
| 1B      | 1B         | 1B    | 1B   | len bytes      |
+---------+------------+-------+------+----------------+
```

字段：

| Field | Size | 说明 |
|---|---:|---|
| `algo_id` | 1B | Session 中分配的 algorithm_instance_id |
| `time_delta` | 1B | 相对 base_time_ms 的时间偏移 |
| `flags` | 1B | 通用状态 |
| `len` | 1B | value 长度 |
| `value` | N | 该算法自己的 Protobuf bytes |

---

### 10.4 Record flags

推荐：

```text
bit0: VALID
bit1: NEW_RESULT
bit2: WARNING
bit3: ERROR
bit4: HAS_EXT
bit5~bit7: reserved
```

通用状态放 TLV flags。

算法细节放 inner Protobuf，例如：

```text
quality
confidence
reason_flags
state_flags
debug_codes
```

---

### 10.5 value

`value` 是算法自己的 Protobuf bytes。

示例：

```proto
message HrResultV1 {
  uint32 hr_bpm = 1;
  uint32 confidence = 2;
  uint32 quality = 3;
}
```

```proto
message Spo2GhResultV2 {
  uint32 spo2 = 1;
  uint32 hr_bpm = 2;
  uint32 quality = 3;
  uint32 state_flags = 4;
  uint32 pi_x1000 = 5;
}
```

```proto
message WearResultV1 {
  uint32 wear_state = 1;
  uint32 confidence = 2;
  uint32 reason_flags = 3;
}
```

---

### 10.6 REPORT_TLV 解码流程

```text
收到 ALGO / REPORT_TLV
  ↓
解析 bundle_seq / base_time_ms / record_count
  ↓
循环读取 ReportRecord
  ↓
根据 algo_id 查 Session AlgorithmManifest
  ↓
得到 schema_id
  ↓
用对应 Protobuf message 解 value
```

---

### 10.7 REPORT_TLV 打包判断

假设：

```text
BLE Value Max = 243B
UHTP Header = 5B
Payload Max = 238B

Report Bundle Header = 6B
Report Record Header = 4B
```

当前已用：

```text
used = 6 + existing_records_total_size
```

新算法结果 Protobuf 长度：

```text
value_len = serialized_algorithm_result_len
```

新增所需：

```text
need = 4 + value_len
```

判断：

```text
if used + need <= 238:
    放入当前 bundle
else:
    flush 当前 bundle
    新建 bundle
```

如果单个 record 过大：

```text
4 + value_len > 238 - 6
```

处理策略：

```text
1. 用 SEG_MODE 分帧发送整个 REPORT_TLV
2. 若是 debug 大数据，改走 DEBUG_OBSERVATION / FILE / LOG_EXPORT
3. 若是厂商输入输出，改走 VENDOR_IO_BUNDLE
4. 若是普通结果，说明 schema 过大，需要精简
```

---

## 11. ALGO VENDOR_IO_BUNDLE

用于厂商要求：

```text
每包同时包含算法输入和算法输出
```

它不污染正常路径：

```text
RAW_STREAM + REPORT_TLV
```

典型 payload：

```text
vendor_id
algo_id
format_id
base_time
group_count
group_data
```

每组固定 stride。

示例：厂商 SpO2 IO V1

```text
输入：
  green_ppg u24
  red_ppg   u24
  ir_ppg    u24
  acc_x     int16
  acc_y     int16
  acc_z     int16

输出：
  hr         uint8
  spo2       uint8
  quality    uint8
  state      uint8
  valid_mask uint8

总计：
  20B / group
```

---

## 12. LOG 设计

### 12.1 实时日志

```text
main_type = LOG
sub_type  = LIVE_EVENT
```

实时日志允许丢，不逐包 ACK。

建议二进制格式：

```text
timestamp_delta
level
module_id
event_id
arg_count
args[]
```

App 使用 Session 中的 `log_dictionary_id` 解码。

---

### 12.2 日志导出

日志导出走状态机：

```text
EXPORT_BEGIN
EXPORT_READY
EXPORT_DATA
EXPORT_ACK
EXPORT_END
```

`EXPORT_BEGIN / READY / END` 使用 Protobuf。

`EXPORT_DATA` 使用固定结构：

```text
transfer_id u32
offset      u32
data        bytes
```

`EXPORT_ACK` 返回：

```text
transfer_id u32
next_offset u32
status      u8/u32
```

---

## 13. OTA 设计

### 13.1 总原则

OTA 是独立事务域：

```text
main_type = OTA
```

必须先协商，再传输。

核心思想：

```text
前置协商阶段把规则讲清楚
稳定传输阶段只带最小必要字段
确认阶段按协商窗口返回 ACK
```

---

### 13.2 OTA 分层概念

```text
Object = 整个 OTA 镜像
Window = 若干 chunk 组成的一次确认窗口
Chunk  = 一次传输的数据块
Frame  = BLE 上的一个 UHTP 包
```

---

### 13.3 OTA 协商

APP 发送：

```text
sub_type = UPLOAD_BEGIN
```

建议 Protobuf 字段：

```text
image_size
image_hash
image_version
image_type
target_slot
preferred_chunk_size
preferred_ack_window_chunks
preferred_crc_window_chunks
crc_type
resume_supported
min_battery_mv
require_charging
```

设备返回：

```text
sub_type = UPLOAD_READY
```

建议字段：

```text
transfer_id
accepted_chunk_size
total_chunks
ack_window_chunks
crc_window_chunks
crc_type
next_offset
flash_write_alignment
erase_block_size
max_in_flight_chunks
status
```

原则：

```text
App 提议参数
Device 最终决定参数
```

---

### 13.4 OTA DATA

稳定传输阶段：

```text
sub_type = UPLOAD_DATA
```

payload：

```text
transfer_id: u32
offset:      u32
data:        N bytes
```

`offset` 是 data 在整个镜像中的字节偏移。

---

### 13.5 OTA ACK

每 `ack_window_chunks` 个 chunk 返回一次 ACK：

```text
sub_type = UPLOAD_ACK
```

payload：

```text
transfer_id: u32
next_offset: u32
status:      u8/u32
crc_status:  optional
```

V1 采用简单模型：

```text
next_offset 之前的数据已连续接收成功
App 从 next_offset 继续发送
```

V1 不做 bitmap ACK。

---

### 13.6 CRC 策略

```text
BLE 每包不强制 CRC
每个 window 可选 CRC32
整个 image 必须 image_hash
最终必须 bootloader signature verify
```

如果 window CRC 失败：

```text
status = CRC_ERROR
next_offset = 当前 window 起始 offset
```

---

### 13.7 OTA 完整流程

```text
1. APP → DEVICE: IMG_LIST

2. APP → DEVICE: UPLOAD_BEGIN
   - image_size
   - image_hash
   - preferred_chunk_size
   - preferred_ack_window
   - preferred_crc_window

3. DEVICE → APP: UPLOAD_READY
   - transfer_id
   - accepted_chunk_size
   - total_chunks
   - ack_window_chunks
   - crc_window_chunks
   - next_offset

4. APP → DEVICE: UPLOAD_DATA offset=0

5. 连续发送 ack_window_chunks 个 chunk

6. DEVICE → APP: UPLOAD_ACK
   - next_offset
   - status

7. APP 从 next_offset 继续发送

8. APP → DEVICE: UPLOAD_END

9. DEVICE 校验 image_hash

10. APP → DEVICE: IMG_STATE(TEST)

11. APP → DEVICE: RESET

12. Bootloader 试启动新镜像

13. 新镜像自检成功后 CONFIRM

14. 未 confirm 则回滚
```

---

### 13.8 OTA 与 Bootloader 分工

UHTP OTA 负责：

```text
协商
传输
offset 续传
窗口 ACK
hash 校验
状态切换请求
```

Bootloader 负责：

```text
镜像格式解析
签名验证
slot 切换
test boot
confirm
rollback
```

---

## 14. FILE 设计

FILE 与 LOG_EXPORT 类似。

流程：

```text
FILE_LIST
READ_BEGIN
READ_READY
READ_DATA
READ_ACK
READ_END
```

用于：

```text
离线采集文件导出
debug dump
厂商分析文件
长日志文件
```

`READ_DATA` 使用：

```text
transfer_id
offset
data
```

---

## 15. 状态码

推荐通用状态码：

```text
0x00 OK
0x01 INVALID_REQUEST
0x02 NOT_SUPPORTED
0x03 INVALID_STATE
0x04 BUSY
0x05 INVALID_PARAM
0x06 NO_MEMORY
0x07 NO_SPACE
0x08 CRC_ERROR
0x09 HASH_MISMATCH
0x0A AUTH_FAILED
0x0B TIMEOUT
0x0C RETRY_LATER
0x0D SERVICE_REJECTED
0x0E UNSUPPORTED_SCHEMA
0x0F UNSUPPORTED_FORMAT
0x10 TOO_LARGE
```

---

## 16. 协议升级与版本管理

### 16.1 分层版本

```text
Header Version:
  在 ver_flags 中，每包携带
  只表示 Header 解析方式

Protocol Version:
  在 HELLO 中协商
  表示 UHTP 协议整体能力

Feature Flags:
  在 HELLO 中协商
  表示具体能力是否支持

Schema ID:
  在 AlgorithmManifest 中声明
  表示每种算法结果如何解析

Format Registry ID:
  在 Session 中声明
  表示 raw stream / vendor io 格式表

Log Dictionary ID:
  在 Session 中声明
  表示日志 event_id 如何解析
```

---

### 16.2 critical / optional 特性

应区分：

```text
optional:
  不理解可以忽略

critical:
  不理解必须拒绝
```

例如：

```text
optional:
  新增 debug 字段
  新增低优先级 report

critical:
  payload 加密
  payload 压缩
  新的 raw stream format
  新的 OTA ack 模式
```

---

## 17. V1 最小实现建议

V1 必须实现：

```text
1. 5B Header
2. main_type / sub_type 分流
3. CTRL SESSION HELLO / HELLO_RSP
4. CTRL HEARTBEAT
5. CTRL SENSOR_CTRL / ALGO_CTRL 基本配置
6. ALGO RAW_STREAM
7. ALGO REPORT_TLV
8. LOG LIVE_EVENT
9. OTA UPLOAD_BEGIN / READY / DATA / ACK / END / IMG_STATE / RESET
10. 通用状态码
```

V1 暂缓：

```text
1. EXT_HDR
2. bitmap ACK
3. 动态 schema 下载
4. 加密 payload
5. 压缩 payload
6. 多并发 request_id
7. OTA missing ranges
8. complex file management
9. report series bundle
```

---

## 18. 抓包示例

### 18.1 OTA DATA

```text
10 04 04 21 E0 ...
```

解释：

```text
ver_flags = 0x10
  header_ver = 1
  SEG_MODE = SINGLE

main_type = 0x04
  OTA

sub_type = 0x04
  UPLOAD_DATA

seq = 0x21
len = 224
```

---

### 18.2 ALGO REPORT_TLV

```text
10 02 02 35 31 ...
```

解释：

```text
ver_flags = 0x10
main_type = 0x02 ALGO
sub_type  = 0x02 REPORT_TLV
seq       = 0x35
len       = 49
```

---

### 18.3 CTRL HEARTBEAT

```text
14 01 06 40 08 ...
```

解释：

```text
ver_flags = 0x14
  header_ver = 1
  NEED_RSP = 1

main_type = 0x01 CTRL
sub_type  = 0x06 HEARTBEAT
seq       = 0x40
len       = 8
```

---

## 19. 最终总结

UHTP V4 的核心结构：

```text
5B Header:
  ver_flags | main_type | sub_type | seq | len

main_type:
  CTRL / ALGO / LOG / OTA / FILE

sub_type:
  各事务域内部小类型

Payload:
  控制协商查询 mostly Protobuf
  算法结果 Report TLV + inner Protobuf
  高频 raw / OTA data / log export / file data 使用二进制裸字节
```

最重要的职责边界：

```text
Header 管分流
main_type 管事务域状态机
sub_type 管域内动作
seq 管短期顺序和响应回显
SEG_MODE 管小消息重组
offset 管大对象进度
Session 管 schema / format / dictionary
TLV 容器管算法结果批量打包
Protobuf 管算法结果语义
```

一句话：

> **UHTP 是一个“5B 小头 + 事务域状态机 + Protobuf 协商 + TLV 算法结果容器 + offset 大对象传输”的嵌入式健康数据协议。**
