# S7 · zqdata 数据通道与离线采集上行协议完整规格（逐字节 · 含位域展开）

> **合成说明**：本文由三份独立抽取结果合成——
> ①**文档侧声明**（`06_.../离线采集_zqdata服务与上行协议.md` v1.0 + `05_.../离线采集_接口与数据格式_SPEC.md`）
> ②**采集固件 `_collect` 代码实证**（`E:/1/apollo4_watch_s7_collect/product/apollo_eiot`，**上行数据协议最高权威**）
> ③**新 zqdata 模块代码实证**（`E:/1/apollo4_watch_s7/product/apollo_eiot/zqdata/`，**GATT 传输层最高权威**）。
>
> **权威顺序**：
> - **上行数据帧字节布局**（40B 落盘 / 28B 上行 / 18B gsensor / B2A 封装）以**第 ② 份 `_collect` 代码实证为最高权威**（手工 `>>24` 拼装真相）；
> - **GATT 传输层**（句柄段 `0x0A10-0x0A15`、RX/TX/CCC、环/线程、MTU/长度约束）以**第 ③ 份 zqdata 模块代码实证为最高权威**；
> - 文档①提供设计意图、三通道架构、跨模块语义；三者冲突处于该字段/命令下**标注 `⚠差异`**，并在 [第 5 章](#5-代码实证-vs-文档-差异与完整性核查) 集中列出。
>
> **⚠️ 两棵源码树，务必区分**（本文最重要的前置认知）：
> | 树 | 路径 | 承担 | 上行 TX 句柄符号 |
> |---|---|---|---|
> | **A｜采集固件 `_collect`** | `E:/1/apollo4_watch_s7_collect` | **上行协议实证来源**（HR/SpO2/gsensor/ECG 组帧 + B2A 封装 + 起停命令） | `GH3X2X_BLE_TX_HDL`（强制 `svcSwFlag[0]=0x01` 切 ZQDATA 服务 Service `0x1540`/RX `0x1541`/TX `0x1542`） |
> | **B｜zqdata 新模块** | `E:/1/apollo4_watch_s7` | **传输层实证来源**（纯 GATT 收发通道，不定义业务帧） | `ZQDATA_BLE_TX_HDL = 0x0A14`（独立句柄段 `0x0A10-0x0A15`） |
>
> 两棵树的 UUID base **同为** `4512XXXX-51F2-406E-927A-3E1E183412E0`、句柄同落 `0x0A1x` 区间、Service/RX/TX part 同为 `0x1540/0x1541/0x1542`；差别在**软件实现**：A 树复用旧 `factory_gh3x2x_ble_server` 的强制切换 + 调用者线程直发；B 树是重写的独立静态库（私有环 + RX/TX 双线程）。**文档①把二者当同一条通道叙述，本文分栏澄清**（见 [§5](#5-代码实证-vs-文档-差异与完整性核查)）。

---

## 目录

1. [概述与三通道架构](#1-概述与三通道架构)
2. [zqdata GATT 传输层逐字节](#2-zqdata-gatt-传输层逐字节代码实证-③-为准)
3. [上行数据协议逐字节（最高权威）](#3-上行数据协议逐字节代码实证-②-为最高权威)
4. [下行与控制通道 · 广播 / 服务发现 · 约束](#4-下行与控制通道--广播--服务发现--约束)
5. [代码实证 vs 文档 差异与完整性核查](#5-代码实证-vs-文档-差异与完整性核查)
6. [附录：关键结构体 / 常量 / file:line 索引](#6-附录关键结构体--常量--filelne-索引)

---

## 1. 概述与三通道架构

同一条 BLE 连接上并存**三条独立通道**，句柄段 / 协议各自独立、不交织。文档①的核心澄清是：**「B2A」是主控制通道（AMDTP `0xFFE0` 服务）的协议，不是 zqdata**；zqdata / 190E 数据通道**只借用 B2A 的打包格式**做上行，其下行是 Goodix 汇顶协议。

| 通道 | 角色 | 上行 (TX Notify) | 下行 (RX Write) | 协议 | 用途 |
|---|---|---|---|---|---|
| **① 主控制通道 = B2A**（AMDTP `0xFFE0`） | 控制命令 + 上传触发 | `0x0804` notify | `0x0802` write → `B2A_RecvData` | **B2A**（GET/SET/PUSH/IND/RPT_DATA/TEST/DEV_CTRL/FILE_TRANS） | 承载控制命令与「上传触发」（`RPT_DATA → skg_rpt_4G_upload_start`）；详见 [protocol-b2a.md](protocol-b2a.md) |
| **② 数据上行通道**（借 B2A 打包，绕开主 B2A 传输） | 原始采集数据上行 | `0x0A14`（zqdata）/ `0x0A04`（190E）notify | —（本通道无下行） | 数据帧封进 **B2A `BCMD_TEST(0x08)`** 帧，经**栈缓冲 + 显式 `pfSend`** 直发，不依赖主 B2A 连接态 | HR/SpO2/gsensor 离线数据回传 |
| **③ 数据通道下行**（控制/命令） | 数据通道的命令 | — | `0x0A12`（zqdata）/ `0x0A02`（190E）write | **Goodix 汇顶协议**（`Gh3x2xDemoProtocolProcess`）或 zqdata `rx_handler` | 采集起止 / 参数；与 ① 的 B2A 命令集无关 |

**句柄段划分：**
- gh3x2x（190E，16-bit UUID）：`0x0A00-0x0A05`
- zqdata（`4512…`，128-bit UUID）：`0x0A10-0x0A15`
- 主 AMDTP/B2A：`0x0800`(Svc) / `0x0802`(RX) / `0x0804`(TX) / `0x0805`(CCC)

**三个「角色」的关系（务必厘清）：**
- **zqdata（B 树新模块）**：纯**传输层**，只搬 `(buf,len)` 不透明字节流，**不定义 28B 业务帧**（`zqdata.h:36-41`）。是「未来主线」的收发骨架。
- **gh3x2x 190E**：老现网数据服务（16-bit `0x190E`），产测/汇顶原生通道。
- **B2A `0xFFE0`（AMDTP）**：主控制通道协议本体；数据通道**只借它的 8B 帧头 + `BCMD_TEST` 打包格式**，用 `B2A_MakePkt2Send(..., pfSend=gh3x2x_ble_send_to_zqdata)` 栈缓冲直发，`GetBuffCb`/`SendDataCb` 全 `#if 0`，**不占用主 B2A 状态机**。

---

## 2. zqdata GATT 传输层逐字节（代码实证 ③ 为准）

> 本章描述 **B 树新模块** `E:/1/apollo4_watch_s7/product/apollo_eiot/zqdata/`。它是纯传输层——RX/TX 均以 `(buf,len)` 为单位，业务帧格式由调用方决定。

### 2.1 句柄枚举 `0x0A10-0x0A15`

来源 `zqdata_svc.h:18-31`：

| 句柄 | 枚举符号 | 含义 | file:line |
|---|---|---|---|
| **0x0A10** | `ZQDATA_BLE_SVC_HDL` | Primary Service 声明 | `zqdata_svc.h:22` |
| **0x0A11** | `ZQDATA_BLE_RX_CH_HDL` | RX 特征声明（Characteristic Declaration） | `zqdata_svc.h:23` |
| **0x0A12** | `ZQDATA_BLE_RX_HDL` | **RX 值** —— 手机写命令落点 | `zqdata_svc.h:24` |
| **0x0A13** | `ZQDATA_BLE_TX_CH_HDL` | TX 特征声明 | `zqdata_svc.h:25` |
| **0x0A14** | `ZQDATA_BLE_TX_HDL` | **TX 值** —— 表→手机通知源 | `zqdata_svc.h:26` |
| **0x0A15** | `ZQDATA_BLE_TX_CH_CCC_HDL` | **TX CCC**（客户端订阅开关） | `zqdata_svc.h:27` |

- 起始 `ZQDATA_BLE_START_HDL = 0x0A10`（`zqdata_svc.h:18`）；结束 `ZQDATA_BLE_END_HDL = ZQDATA_BLE_MAX_HDL-1 = 0x0A15`（`zqdata_svc.h:31`）。
- **独立句柄段**，与 gh3x2x（190E，`0x0A00-0x0A05`）**并行注册、互不重叠**（`zqdata_svc.h:4-7`、`zqdata_svc.c:7-8`）。
- ⚠️ **注意**：文档②表内 `0x0A12(RX)` 直接给 part `0x1541`、`0x0A14(TX)` 给 part `0x1542`——代码实证证明 `0x0A11`/`0x0A13` 是**特征声明**（`attChUuid`），128-bit 值 UUID 落在 `0x0A12`/`0x0A14`。文档句柄→含义对应正确，只是未显式列出 `0x0A11`/`0x0A13` 两个声明句柄。

### 2.2 UUID（128-bit 自定义）

来源 `zqdata_svc.c:26-38`：

- **Base**（小端存储，可读式 `4512XXXX-51F2-406E-927A-3E1E183412E0`）：`zqdata_svc.c:28-29`
- 构造宏 `ATT_UUID_ZQDATA_BUILD(part) = Base(12B 小端) + UINT16_TO_BYTES(part) + 0x12,0x45`（`zqdata_svc.c:30`），原型移植自 `factory_gh3x2x_ble_server.h:63-87`（`zqdata_svc.c:26` 注）。

| 角色 | part | 完整 UUID | file:line |
|---|---|---|---|
| Service | `0x1540` | `45121540-51F2-406E-927A-3E1E183412E0` | `zqdata_svc.c:32,36` |
| RX | `0x1541` | `45121541-…` | `zqdata_svc.c:33,37` |
| TX | `0x1542` | `45121542-…` | `zqdata_svc.c:34,38` |

### 2.3 属性表（6 条 `attsAttr_t`）—— 权限与属性

来源 `zqdata_svc.c:68-118`：

| # | 句柄 | UUID | permissions | settings(flags) | 特征属性 | file:line |
|---|---|---|---|---|---|---|
| 1 | 0x0A10 | `attPrimSvcUuid` | `ATTS_PERMIT_READ` | 0 | Service 声明 | `:70-77` |
| 2 | 0x0A11 | `attChUuid` | `ATTS_PERMIT_READ` | 0 | RX 声明，内含 `ATT_PROP_WRITE_NO_RSP` | `:78-85`（声明体 `:50`） |
| 3 | 0x0A12 | `svcZqdataRxUuid`(128b) | `ATTS_PERMIT_WRITE` | `ATTS_SET_UUID_128 \| ATTS_SET_VARIABLE_LEN \| ATTS_SET_WRITE_CBACK` | **RX 值**，变长、走写回调 | `:86-93` |
| 4 | 0x0A13 | `attChUuid` | `ATTS_PERMIT_READ` | 0 | TX 声明，内含 `ATT_PROP_NOTIFY` | `:94-101`（声明体 `:54`） |
| 5 | 0x0A14 | `svcZqdataTxUuid`(128b) | **0** | **0** | **TX 值**；flags=0 刻意（UUID 由声明属性携带），通知源 | `:102-109` |
| 6 | 0x0A15 | `attCliChCfgUuid` | `ATTS_PERMIT_READ \| ATTS_PERMIT_WRITE` | `ATTS_SET_CCC` | **TX CCC**，订阅开关（初值 `0x0000`） | `:110-117`（初值 `:64`） |

关键点：
- **RX = Write Without Response**（`ATT_PROP_WRITE_NO_RSP`，`zqdata_svc.c:50`），值属性最大长度 `ATT_VALUE_MAX_LEN`（`:90`），变长 + 写回调。
- **TX = Notify**（`ATT_PROP_NOTIFY`，`zqdata_svc.c:54`）；CCC 由 Cordio ATT 管理订阅状态（`ATTS_SET_CCC`，`:115`）。
- **TX 值 flags=0 是刻意的**（`:107` 注），照搬勿改——TX UUID 由声明属性携带。

### 2.4 服务组 add / remove / write_cback

- 服务组 `svcZqdataGroup`（`zqdata_svc.c:121-129`）：属性表 `zqdataList`，句柄段 `START..END`，`readCback=NULL`。
- **add**：`zqdata_svc_add_group()`（`zqdata_svc.c:142-147`）—— 设 `writeCback = zqdata_ble_write_cback` 后调 `AttsAddGroup(&svcZqdataGroup)`。
- **remove**：`zqdata_svc_remove_group()`（`zqdata_svc.c:149-152`）—— `AttsRemoveGroup(ZQDATA_BLE_START_HDL)`。
- **写回调** `zqdata_ble_write_cback`（`zqdata_svc.c:132-139`）：忽略 connId/handle/operation/offset/pAttr，仅 `zqdata_on_rx(pValue,len)` 入 RX 环即 `return ATT_SUCCESS`——O(1)，**不在 WSF 上下文做任何业务**。未装 readCback（TX 无读需求）。

### 2.5 环形缓冲（`zqdata_ring.*`）

编译期配置（可父级 `-D` 覆盖），`zqdata.c:27-56`：

| 宏 | 默认值 | 含义 | file:line |
|---|---|---|---|
| `ZQ_TX_RING_SIZE` | **4096（4KB）** | TX 环容量（100Hz PPG ~2.8KB/s，4KB 平滑突发） | `zqdata.c:28` |
| `ZQ_RX_RING_SIZE` | **512** | RX 环（命令量小） | `zqdata.c:31` |
| `ZQ_TX_FRAME_MAX` | **244** | TX 单帧上限（= 典型 247 MTU − 3 ATT notify 头） | `zqdata.c:34` |
| `ZQ_RX_FRAME_MAX` | **244** | RX 单帧上限 | `zqdata.c:37` |

- 缓冲区 `s_tx_buf[4096]` / `s_rx_buf[512]`（`zqdata.c:59-60`），存于普通 `.bss`（TCM），不入 `.shared`，避开 LVGL SSRAM 堆（`zqdata.c:59` 注）。
- 记录格式 `[u16 payload_len][payload]`（`zqdata_ring.h:5-8`、`zqdata_ring.c:12-13`）；记录**不跨尾**，尾部放不下则留 skip 区写 `ZQR_SKIP_MARK=0xFFFF` wrap-skip 标记。
- 环结构 `zqring_t`（`zqdata_ring.h:23-30`）：`buf/size/head/tail/count/dropped`。
- **丢弃路径**（累加 `r->dropped`）：非法/过大 `payload==0 || payload>=0xFFFF || n>size` → 返回 false **不计** dropped（`zqdata_ring.c:60-62`）；**空间不足** `need > (size-count)` → `dropped++` 返回 false（`:70-74`）。
- **pop 侧健壮性**：`len > cap`（消费者缓冲太小）→ **跳过该条防卡死**、不计 dropped（`:132-140`）；skip 标记 / 尾部不足自动绕尾（`:120-130`）。
- **并发**：上下文自适应临界区 `zqr_lock/unlock`（`zqdata_ring.c:16-32`）——ISR 用 `taskENTER_CRITICAL_FROM_ISR`，task 用 `taskENTER_CRITICAL`，**可从中断安全调用**。多生产者 / 单消费者；u16 用 `memcpy` 非对齐安全读写（`:35-36`）。

### 2.6 线程模型（RX/TX 双线程 + txReady 背压）

创建于 `zqdata_init()`（`zqdata.c:203-217`）：

| 线程 | 名 | 函数 | 栈 | 优先级 | file:line |
|---|---|---|---|---|---|
| **TX** | `"zq_tx"` | `zqdata_tx_task` | `ZQ_TX_TASK_STACK=256` word | `ZQ_TX_TASK_PRIO=2` | `zqdata.c:40,46,210-211` |
| **RX** | `"zq_rx"` | `zqdata_rx_task` | 256 word | 2 | `zqdata.c:43,49,214-215` |

- **通知只发信号（0 数据）**：`zq_notify`（`zqdata.c:71-83`）ISR 用 `vTaskNotifyGiveFromISR`+`portYIELD_FROM_ISR`，task 用 `xTaskNotifyGive`；数据全走私有环（`zqdata.h:8-9`）。
- **TX 线程循环**（`zqdata.c:147-159`）：`ulTaskNotifyTake(pdTRUE, 50ms)` 兜底唤醒防丢通知（`ZQ_TX_PERIOD_MS=50`，`:52`），然后 `while(zqring_pop>0) zqdata_ble_notify(...)` 排空。
- **发送 + 背压** `zqdata_ble_notify`（`zqdata.c:132-145`）：① `AppConnIsOpen()` 无连接→丢帧、`s_tx_ready=true` 保持就绪（`:136-139`）；② 上一帧未确认则 `zqdata_tx_wait_ready()` 等待，超时丢本帧（`:140-142`）；③ 置 `s_tx_ready=false` 后 `AttsHandleValueNtf(connId, ZQDATA_BLE_TX_HDL, len, buf)`（`:143-144`）。
- **等待就绪** `zqdata_tx_wait_ready`（`zqdata.c:116-128`）：1ms 轮询直至 `s_tx_ready` 或 `waited >= ZQ_TX_READY_TMO_MS=1000`（`:55`）；超时则 `s_tx_ready=true` 复位 + 返回 false（丢帧防永久卡死）。
- **txReady 置位** `zqdata_on_att_cnf`（`zqdata.c:107-113`）：BLE 事件派发收到 `ATTS_HANDLE_VALUE_CNF` 时，`handle==ZQDATA_BLE_TX_HDL` 才置 `s_tx_ready=true`；**忽略 status**（成功/超时/MTU 超限一律放行防卡死，`:109`）。初值 `s_tx_ready=true`（`:68`）。与 gh3x2x 各持独立 TX 句柄 / txReady，互不干扰。
- **RX 线程** `zqdata_rx_task`（`zqdata.c:184-200`）：`ulTaskNotifyTake(pdTRUE, portMAX_DELAY)` 纯事件驱动，排空环；有 handler 则 `s_rx_handler(frame,n)`（**非 WSF 上下文**，可落库/起停采集），否则 `s_rx_dropped++`。

### 2.7 API 契约

| API | 签名 / 位置 | 契约 |
|---|---|---|
| `zqdata_send` | `bool zqdata_send(const void*, uint16_t)`（`zqdata.h:41`，`zqdata.c:86-98`） | `buf==NULL \|\| len==0`→false；入 TX 环成功→通知返回 true，空间不足→丢弃累加 dropped 返回 false。**非阻塞**，真正 notify 在 TX 线程。`len` 建议 ≤244，更长由调用方先分帧（`zqdata.h:39-40`） |
| `zqdata_on_rx` | `void(const uint8_t*, uint16_t)`（`zqdata.h:55`，`zqdata.c:167-177`） | 供 write_cback 调用；入 RX 环 + 唤醒 RX 线程，失败 `s_rx_dropped++` |
| `zqdata_set_rx_handler` | `void(zqdata_rx_handler_t)`（`zqdata.h:51`，`zqdata.c:162-165`） | 注册 RX 命令处理回调，在 RX 线程上下文（非 WSF）回调；未注册则 RX 数据丢弃并计 dropped |
| `zqdata_on_att_cnf` | `void(uint16_t, uint8_t)`（`zqdata.h:65`，`zqdata.c:107-113`） | `ATTS_HANDLE_VALUE_CNF` 时调用；`handle==ZQDATA_BLE_TX_HDL` 置 txReady；**status 被忽略** |
| `zqdata_tx_dropped` / `_rx_dropped` | `uint32_t`（`zqdata.c:100-103` / `179-182`） | TX 溢出诊断 / RX 环溢出 + 无处理器丢弃 |
| `zqdata_init` | `void`（`zqdata.c:203-217`） | 复位 RX/TX 环、清 `s_rx_dropped`、创建两线程；`vTaskStartScheduler` 前调用安全 |
| `zqdata_svc_add_group` / `_remove_group` | `void`（`zqdata.h:33-34`） | BLE 初始化期挂/摘服务组（见 §2.4） |

### 2.8 MTU / 长度约束（传输层硬事实）

- **单帧硬上限 244B**：注释建议 ≤244（`zqdata.h:40`）；硬上限由环 `ZQ_TX_FRAME_MAX=244`（`zqdata.c:34`）——`zqring_pop` 传 `cap=244`，**超 244 的记录在 pop 侧被静默跳过丢弃**（`zqdata_ring.c:132-140`）。注意：`zqring_put` 本身只拒 `n>size(=4096)`，不按 244 拒；若入了 >244 帧，是在 pop 侧被丢。
- **244 = 247 MTU − 3**（ATT notify 头）。文档①「`len<=MTU-3`」「212B 上行包要求 MTU≥215（实际协商多为 247）」与此一致。
- ⚠️ **一致性提醒**：文档①§2.4 说单帧 `len<=MTU-3`；传输层实际是**固定 244 硬上限**（不随协商 MTU 动态放大）。若协商 MTU>247，单帧仍被 244 截断。见 [§5](#5-代码实证-vs-文档-差异与完整性核查) 第 9 条。

### 2.9 编译门控 `SYS_DATA_COLLECT_SVC`

- 独立静态库 `zqdata_svc`（`CMakeLists.txt:16-20`），父工程 `add_subdirectory(zqdata zqdata_svc)` + `target_link_libraries(apollo_watch zqdata_svc)`（`:4-5`）。
- **库本身无 `#ifdef SYS_DATA_COLLECT_SVC`**；门控在**调用方**：仅采集固件（`=1`）才调 `zqdata_init()`/`zqdata_svc_add_group()`。主线不引用入口 → 整库经 `--gc-sections` 丢弃、零占用（`CMakeLists.txt:12-13`、`zqdata.h:12`）。
- 文档①声明的「3 处门控 + 1 处 CNF 分支」（`rtos.c` / `watch_main.c` / `factory_..._main.c` 的 `ATTS_HANDLE_VALUE_CNF`）——**门控实际引用点在 zqdata 7 文件之外**（调用方固件），模块代码实证只能坐实「设计意图 = 调用方门控 + gc」。见 [§5](#5-代码实证-vs-文档-差异与完整性核查) 第 12 条。

---

## 3. 上行数据协议逐字节（代码实证 ② 为最高权威）

> 本章描述 **A 树采集固件 `_collect`** `E:/1/apollo4_watch_s7_collect/product/apollo_eiot` 的真实字节布局。**所有字段/字节序/长度均给 file:line。**
>
> **总链路**：算法执行 → 落盘 40B/帧（`ppghr.dat`/`ppgspo.dat`，8192B 攒批落盘）→ 触发上传时 **40B→28B 重打包** → 封 **B2A `BCMD_TEST(0x08)`** 帧 → `gh3x2x_ble_send_to_zqdata()` → `AttsHandleValueNtf(GH3X2X_BLE_TX_HDL)`。gsensor 走独立 18B 链，无重打包。

### 3.0 字节序总纲（一句话记住）

| 数据 | 字节序 | 依据 |
|---|---|---|
| HR/SpO2 落盘 40B 主体（ticks/PPG/ACC/cnt） | **大端 BE**（手工 `>>24/>>16/>>8`，非 htonl） | `hr.c:778-803` |
| HR/SpO2 落盘 40B 尾 12B AGC | **逐字节 / 半字节位域，无字节序** | `hr.c:814-820` |
| 28B 上行主体 | **大端 BE**（24B 从 40B 原样搬） | `hr.c:1091` |
| gsensor 18B 落盘/上行 | **大端 BE** | `user_gsensor_api.c:462-492` |
| B2A 8B 帧头 + 4B 命令头（Len/CRC/Index/内层 Len） | **小端 LE** | `ble2appEx.c:59-71,376-377` |
| ECG 会话 `ecg_raw.dat` 文件头 / ACC 镜像 | **小端 LE**（唯一例外通道） | `data_collect_session.c` 显式注释 |

> **一句话**：**PPG/HR/SpO2/gsensor 数据帧 = 大端；B2A 封装头 = 小端；新 ECG 会话 = 小端**。同一固件三种字节序并存，解析器必须按通道区分。文档①「28B 帧内字段全大端」成立，但需补充「40B 尾 AGC 是逐字节位域、非大端」这一层——见 [§5](#5-代码实证-vs-文档-差异与完整性核查) 第 1 条。

### 3.1 HR 落盘帧（`ppghr.dat`，每帧 40B）

组装位置 `gh3x2x_demo_algo_call_hr.c:773-823`（`GH3x2xHrFeedAlgoCacheFrame`）；长度常量 `data_len = 4 + 4*4 + 3*2 + 2 + 12 = 40`（`hr.c:628`），`HR_COV_FRAME_AGC_LEN=12`（`:74`），`ALGO_HR_TOTAL_CHN=HBA_TOTAL_CHN`（`:66`）。

#### 28B 数据主体（offset 0..27，大端）

| 偏移 | 字节数 | 字段 | 类型 | 字节序 | file:line |
|---|---|---|---|---|---|
| 0 | 4 | ticks（帧0=UTC 秒 / 帧≥1=OS-tick 递推） | u32 | **大端** | `hr.c:778-781` |
| 4 | 16 | PPG raw[4]（绿1..绿4，各 24bit ADC 存 int32） | int32×4 | **大端** | `hr.c:785-791` |
| 20 | 2 | acc_x | int16 | **大端** | `hr.c:794-795` |
| 22 | 2 | acc_y | int16 | **大端** | `hr.c:796-797` |
| 24 | 2 | acc_z | int16 | **大端** | `hr.c:798-799` |
| 26 | 2 | frame_cnt（`g_PPGRawDataFrameCnt`） | u16 | **大端** | `hr.c:802-803` |

#### 12B AGC 尾（offset 28..39，逐字节 / 位域，无字节序）

| 偏移 | 字节数 | 字段 | 编码 | file:line |
|---|---|---|---|---|
| 28 | 4 | AGC drv0[绿1..绿4] | u8×4 | `hr.c:814` |
| 32 | 4 | AGC drv1[绿1..绿4] | u8×4 | `hr.c:816` |
| 36 | 1 | gain 打包 `(gain[绿2]<<4)\|gain[绿1]` | 半字节位域 | `hr.c:817` |
| 37 | 1 | gain 打包 `(gain[绿4]<<4)\|gain[绿3]` | 半字节位域 | `hr.c:818` |
| 38 | 1 | cur_adj 位图（bit0..bit3=绿1..绿4） | 位图 | `hr.c:807-811,819` |
| 39 | 1 | 保留=0 | — | `hr.c:820` |

- **AGC 源拆位**（`hr.c:327-331`）：`gain = unAgcInfo & 0x0F`；`drv0 = (unAgcInfo>>8)&0xFF`；`drv1 = (unAgcInfo>>16)&0xFF`；`cur_adj = (punFrameFlag[0]>>ch)&0x01`。
- **帧 0（起始帧）**：`GH3x2xHrSendStartFrame`（`hr.c:426-491`）只写真实 UTC（`EQC_GetUtcTimeStampSec`，`:447`）到前 4B，其余 36B（含 12B AGC）留 0（`:452-463`）。

#### 40B 帧 bit-level memory map（HR；SpO2 同构，通道语义见 §3.2）

```
Byte Offset:   0        1        2        3
              +--------+--------+--------+--------+
ticks         |  u32 大端：帧0=UTC 秒 / 帧≥1=OS-tick 递推        (hr.c:778-781)
              +--------+--------+--------+--------+
Byte Offset:   4        ...                        19
              +-----------------------------------+
PPG raw       |  int32×4 大端：绿1..绿4（24bit ADC）             (hr.c:785-791)
              +-----------------------------------+
Byte Offset:   20       21       22       23       24       25       26       27
              +--------+--------+--------+--------+--------+--------+--------+--------+
              | acc_x s16 大端  | acc_y s16 大端  | acc_z s16 大端  | frame_cnt u16 BE|
              +--------+--------+--------+--------+--------+--------+--------+--------+
Byte Offset:   28       29       30       31       32       33       34       35
              +--------+--------+--------+--------+--------+--------+--------+--------+
AGC 尾        | drv0绿1| drv0绿2| drv0绿3| drv0绿4| drv1绿1| drv1绿2| drv1绿3| drv1绿4|
(hr.c:814-816)+--------+--------+--------+--------+--------+--------+--------+--------+
Byte Offset:   36                 37                 38       39
              +------------------+------------------+--------+--------+
              | b7-4:gain绿2     | b7-4:gain绿4     | cur_adj| 保留=0 |
              | b3-0:gain绿1     | b3-0:gain绿3     | 位图↓  |        |
              +------------------+------------------+--------+--------+
cur_adj(38)：bit0..bit3 = 绿1..绿4 电流调整标志                  (hr.c:807-811,819)
```

### 3.2 SpO2 落盘帧（`ppgspo.dat`，每帧 40B）

组装位置 `gh3x2x_demo_algo_call_spo2.c:641-703`（`GH3x2xSpo2FeedAlgoCacheFrame`）；`SPO2_COV_FRAME_AGC_LEN=12`（`spo2.c:61`），`ALGO_SPO2_TOTAL_CHN=SPO2_TOTAL_CHN`（`spo2.c:50`）。**布局与 HR 逐字节同构**，仅 4 通道语义不同：

- PPG 4 通道 = **IR1/IR2/RED1/RED2**，取自算法 ppg_rawdata 的通道 4/5/8/9（`spo2.c:647-650`）。SpO2 映射：RED←`rawdata[0]`、IR←`rawdata[4]`（`algo_config.c:422-424`）。
- AGC 通道索引 `{4,5,8,9}`（`spo2.c:685`）；gain 打包 byte36=`gain[IR2]<<4\|gain[IR1]`、byte37=`gain[RED2]<<4\|gain[RED1]`（`spo2.c:696-697`）；cur_adj bit0..3=IR1/IR2/RED1/RED2（`spo2.c:686-691`）。
- 类型字节 = **0x04**（HR 为 0x03）。

### 3.3 40B → 28B 重打包（兼容核心，逐字节）

重打包核心 `hr.c:1074-1092`。发送域单帧 `data_len = 4 + 4*4 + 3*2 + 2 = 28`（`hr.c:973`）；文件仍 40B/帧（`file_frame_len = data_len + 12 = 40`，`hr.c:1064`）；按帧号 seek `frame_base*40`，整包读入 `s_file_stage`（`hr.c:1067-1068`）。

**帧 0（全局帧序号 == 0）**：保留原始前 4B（真实 UTC 锚点），`dst[0..3]=src[0..3]`（`hr.c:1078-1082`）；`dst[4..27]=src[4..27]` 原样。

**帧 ≥1（ticks 4B 槽改装成 AGC）**（`hr.c:1085-1089`）：

| 目标字节 | 来源 / 变换 | 语义 | file:line |
|---|---|---|---|
| `dst[0]` | `= src[28]` | drv0 绿1 | `hr.c:1085` |
| `dst[1]` | `= src[36] & 0x0F` | gain 绿1 | `hr.c:1086` |
| `dst[2]` | `= src[32]` | drv1 绿1 | `hr.c:1087` |
| `dst[3]` | `= (src[36] >> 4) & 0x0F` | gain 绿2 | `hr.c:1088` |
| `dst[4..27]` | `= src[4..27]`（24B 原样，保持大端） | PPG16 + ACC6 + cnt2 | `hr.c:1091` |

- **丢弃**：drv0/drv1 绿2..绿4、gain 绿3/绿4（`src[37]`）、cur_adj（`src[38]`）——线上 28B 只带 drv0绿1 / drv1绿1 / gain绿1 / gain绿2。
- ✅ **与文档①§5.2 映射表逐字节一致**（`dst[2]=src[32]` 均为 drv1 绿1）。
- 每包 `HR_COV_SEND_FRAME_NUM=7` 帧（`hr.c:70`）= `28*7=196B` + 4B 应用头。
- 断电截尾自检：fileSize 非 40 倍数 → 按整帧向下取整丢残尾（`hr.c:988-989`）。

### 3.4 SpO2 40B → 28B 重打包（帧≥1 与 HR 有偏移差异）

重打包核心 `spo2.c:920-938`。帧≥1 的 AGC 改装（`spo2.c:932-935`）：

| 目标字节 | 来源 / 变换 | 语义 | file:line |
|---|---|---|---|
| `dst[0]` | `= src[28]` | drv0 IR1 | `spo2.c:932` |
| `dst[1]` | `= src[36] & 0x0F` | gain IR1 | `spo2.c:933` |
| `dst[2]` | `= src[30]` | **drv0 RED1**（注意用 `src[30]` 而非 HR 的 `src[32]`） | `spo2.c:934` |
| `dst[3]` | `= src[37] & 0x0F` | **gain RED1**（用 `src[37]&0x0F` 而非 HR 的 `src[36]>>4`） | `spo2.c:935` |

- ✅ **与文档①§7 的 SpO2 差异声明一致**（SpO2 用 `src[28]/[30]/[36]/[37]`）。**HR 与 SpO2 的 dst[2]/dst[3] 来源不同**：HR=`src[32]`(drv1绿1)/`src[36]>>4`(gain绿2)；SpO2=`src[30]`(drv0 RED1)/`src[37]&0x0F`(gain RED1)。
- 每包 `SPO2_COV_SEND_FRAME_NUM=8` 帧（`spo2.c:53`）。
- 断电截尾自检 `spo2.c:854-860`。

### 3.5 gsensor 上行/落盘帧（18B，无重打包）

组装 `Gsensor_CovrtSendFrame`（`user_gsensor_api.c:430-509`）。**文档①几乎未覆盖此帧**（只讲 PPG 28B）——此处以代码实证补全。

| 偏移 | 字节数 | 字段 | 字节序 | file:line |
|---|---|---|---|---|
| 0 | 4 | ticks（起始 UTC + 累加 deltaticks） | **大端** | `:462-465` |
| 4 | 2 | acc_x | 大端 | `:468-469` |
| 6 | 2 | acc_y | 大端 | `:470-471` |
| 8 | 2 | acc_z | 大端 | `:472-473` |
| 10 | 6 | gyro（**恒 0 占位**，gyro 关） | — | `:483-488` |
| 16 | 2 | frame_id（`g_unGsFrameCnt`） | 大端 | `:491-492` |

- 起始帧 `Gsensor_SendStartFrame`（`:390-428`）：前 4B 真实 UTC 大端，后 14B 留 0（共 18B）。
- 落盘文件 `AGS.dat`（`GS_COV_SAVE_FRAME_FILE`，`:32`），`GS_COV_SAVE_CFG=1`（`:31`）走落盘分支。上行 `Gsensor_SendSavedData`（`:590-705`）**逐帧原样发、无重打包**，每包 `18*10=180B`（`:630`）。

### 3.6 B2A 封装（`BCMD_TEST=0x08`）

封装函数 `B2A_MakePkt2Send(BCMD_TEST, Key, temp_buff, len, 0,0, gh3x2x_ble_send_to_zqdata)`（`ble2appEx.c:339-425`）。**栈缓冲 `pszBuff[256]`**（`:345`），`GetBuffCb`/`SendDataCb` 全 `#if 0`——不依赖主 B2A 状态。

#### 3.6.1 B2A 8B 帧头（全小端）

| 偏移 | 字节数 | 字段 | 字节序 | 组装 file:line |
|---|---|---|---|---|
| 0 | 1 | StartFlag = `0xBB`（`MAC_B2A_HEAD_FLAG`） | — | `ble2appEx.c:57` |
| 1 | 1 | Status（`ENUM_HEAD_STATUS_TYPE` 位域） | — | `:58` |
| 2 | 2 | Len（**不含 8B 头**） | **小端** | `:59-60` |
| 4 | 2 | CRC16/CCITT-FALSE（over payload，不含头） | **小端** | `:64,67-68` |
| 6 | 2 | Index（分包索引） | 小端 | `:70-71` |

- CRC = `CRC16_CCITT_FALSE`（Poly `0x1021`，Init `0xFFFF`，Refin/out=False，Xorout=0，`crc16_ccitt_false.c:24-42`），作用域 `pszData+8, len-8`（`ble2appEx.c:64`）。**与 B2A 主协议 CRC 完全一致**。
- Status 位（`ble2appEx.h:26-43`）：`EHST_FAIL=0x01` / `EHST_ACK=0x02` / `EHST_IS_MULTI_PKT=0x04` / `EHST_MULTI_PKT_END=0x08` / `EHST_OTA_PART=0x80`。28B 上行始终 `Index=0` 单包。

#### 3.6.2 内层命令头 4B（仅 Index==0 首包带）

| 偏移(头后) | 字节数 | 字段 | 字节序 | file:line |
|---|---|---|---|---|
| 8 | 1 | Cmd = `BCMD_TEST`=0x08 | — | `ble2appEx.c:374,610` |
| 9 | 1 | Key（子命令） | — | `:375,612` |
| 10 | 2 | 内层 Len | **小端** | `:376-377,614` |
| 12 | N | payload（4B 应用头 + 数据帧组） | — | `:382` |

#### 3.6.3 Key 与应用头（4B 自定义头）

| Key | 用途 | 应用头 4B | payload 结构 | file:line |
|---|---|---|---|---|
| `0x05` | HR 起帧 | `{0x01, 0x03, 0x02, 0x00}` | 4B（`type=HR`，通道数占位） | `hr.c:998-1002,1179` |
| `0x05` | HR 止帧 | `{0x00, 0x03, 0x02, 0x00}`（首字节 `0x00`） | 4B | 文档①§6.4；`hr.c` 起止用同一 Key |
| `0x06` | HR 数据 | `{0x01, 0x03, 0x00, ALGO_HR_TOTAL_CHN=4}` | 4B + 7×28=196B = **200B** | `hr.c:1042-1045,1122` |
| `0x05` | SpO2 起帧 | `{0x01, 0x04, 0x02, 0x00}` | 4B | `spo2.c:873,977` |
| `0x06` | SpO2 数据 | `{0x01, 0x04, 0x00, ALGO_SPO2_TOTAL_CHN=4}` | 4B + 8×28=224B = **228B** | `spo2.c:889-892,940` |
| `0x01` | gsensor 起止 | — | 通知 | `user_gsensor_api.c:618,696` |
| `0x02` | gsensor 数据 | — | 18×10=180B | `user_gsensor_api.c:654` |

- 应用头字节 1（type）：HR=`0x03`，SpO2=`0x04`。

#### 3.6.4 212B 总包核算（HR，以文档①标称为例）

文档①标「212B = 8 + 4 + 4 + 196」。代码实证核算：

```
B2A 8B 帧头  ┐
             ├─ Len16 域 = 内层命令头4B + 内层payload
命令头 4B     │       内层payload = 应用头4B + 数据196B = 200B
应用头 4B  ┐  │       ∴ 内层 Len(off10) = 200
数据 196B  ┘  ┘       ∴ B2A Len16(off2) = 4(命令头) + 200 = 204
─────────────
总包 = 8(帧头) + 204(Len16 域) = 212B  ✅ 自洽
```

- ✅ **212B 层级归属确认**：`B2A_MakePkt2Send(..., 0x06, temp_buff, 4+196=200, ...)` 的第 4 参 `200` = **内层 payload 长度**（应用头4 + 数据196），写入内层命令头 off10 的 Len=200；B2A 帧头 Len16(off2)=204=命令头4+payload200；总包 212=8+204。文档图注「4+4+196」与「Len200」的层级归属**由代码坐实**。
- ⚠️ **SpO2 每包 8 帧 → 包长不同**：SpO2 内层 payload = 4 + 8×28 = **228B**，B2A Len16 = 232，**总包 = 8 + 232 = 240B**（非 212B）。文档①只给 HR 的 212B 类比，未给 SpO2 完整包长。见 [§5](#5-代码实证-vs-文档-差异与完整性核查) 第 6 条。

#### 3.6.5 B2A_MakePkt2Send 硬约束（代码实证）

- **payload 上限 `244-16=228B`**（`ble2appEx.c:355-359`），超限直接返回。⚠️ **SpO2 单包 228B 正好顶格**（4+8×28），无余量。
- **早退条件**：`svcSwFlag[0]==0 || OtaFlag>0`（`:351`）、`payload>228`（`:355`）——采集必须先 `svcSwFlag[0]=0x01` 生效（`factory_gh3x2x_ble_server.c:416`）。
- 发送叶子 `gh3x2x_ble_send_to_zqdata`（`ble_server_main.c:576-632`）：**单包一次 `AttsHandleValueNtf(GH3X2X_BLE_TX_HDL)`**（`:595`，不再切片，因 B2A payload 已 ≤228 保证单 MTU），带 `gh3x2xBleCheckTxReady()` 流控（`:607`，最多 1000×1ms，`:201-215`）。

---

### 3.7 Bit-level memory map（B2A 封装上行包）

```
Byte Offset:   0        1        2        3        4        5        6        7
              +--------+--------+--------+--------+--------+--------+--------+--------+
B2A 帧头      | 0xBB   | status | Len u16 LE      | CRC u16 LE      | Index u16 LE=0  |
(全小端)      | SOF    | 0x00   | 不含 8B 头      | 覆盖偏移 8 起   | 上行恒单包      |
              +--------+--------+--------+--------+--------+--------+--------+--------+
Byte Offset:   8        9        10       11       12       13       14       15
              +--------+--------+--------+--------+--------+--------+--------+--------+
命令头+应用头 | 0x08   | Key    | 内层 Len u16 LE | 应用头 4B（逐 Key 定义见 §3.6.3： |
              | TEST   | 05/06  | =应用头+数据    |  HR数据={01 03 00 04}             |
              |        | 01/02  |                 |  HR起帧={01 03 02 00}）           |
              +--------+--------+--------+--------+--------+--------+--------+--------+
Byte Offset:   16 ...
              +---------------------------------------------------------------+
数据帧组      | 28B×7（HR）/ 28B×8（SpO2）/ 18B×10（gsensor）—— 数据帧全大端  |
              +---------------------------------------------------------------+
```

注意字节序分界：**偏移 0..15 全小端（B2A 信封），偏移 16 起全大端（数据帧）**——§4.5 约束汇总的"三种字节序并存"在同一个包里就有两种。

### 3.8 实例包 + decode（脚本实算）

> 布局与常量全部代码实证（§3.1/§3.3/§3.6 各表 file:line）；**数值为构造演示值**——zqdata 通道尚无真机抓包（需采集固件手表），待实录后替换。CRC 为真实算（生成脚本 [`assets/gen_zqdata_wire_examples.py`](assets/gen_zqdata_wire_examples.py)，含 B2A 真机金帧 `CRC16("08 01 01")=0x462D` 自校验）。

**例① HR 起帧包（16B，Key=0x05）**：

```
BB 00 08 00 64 19 00 00 08 05 04 00 01 03 02 00
└┘ │  └─┬─┘ └─┬─┘ └─┬─┘ │  │  └─┬─┘ └────┬────┘
SOF│  Len=8  CRC   Idx=0 │  │  内层    应用头{01 03 02 00}
   st=0x00   =0x1964     │  │  Len=4   [0]=0x01 起帧
                         │  └ Key=0x05（起止帧）[1]=0x03 type=HR
                         └ Cmd=0x08（BCMD_TEST） [2]=0x02 [3]=0x00
```

**例② HR 数据包（212B，Key=0x06，7 帧/包）**：

```
BB 00 CC 00 C7 F8 00 00 08 06 C8 00 01 03 00 04   ← 信封：Len=0xCC=204、CRC=0xF8C7、
68 6A 9C 80 00 01 D4 C0 00 01 DA 9C 00 01 D3 F8      内层 Len=0xC8=200、应用头{01 03 00 04}
00 01 DD BC 00 0C FF F8 04 00 00 00 2A 05 33 06   ← 帧0 后半 + 帧1 起始
00 01 D4 C1 00 01 DA 9D 00 01 D3 F9 00 01 DD BD
00 0C FF F8 04 00 00 01 2A 05 33 06 00 01 D4 C2      …（帧2..帧6 同构，共 212B）…
```

Decoded（帧 0，偏移 16..43，重打包保留真实 UTC，`hr.c:1078-1082`）：

| 偏移 | 字节 | 字段 | 值 |
|---|---|---|---|
| 16 | `68 6A 9C 80` | ticks（帧0=UTC 秒，大端） | 0x686A9C80 = 1751817344 |
| 20 | `00 01 D4 C0` | PPG 绿1（int32 大端） | 120000 |
| 24..35 | … | PPG 绿2/绿3/绿4 | 121500 / 119800 / 122300 |
| 36 | `00 0C` | acc_x（s16 大端） | 12 |
| 38 | `FF F8` | acc_y | −8 |
| 40 | `04 00` | acc_z | 1024 |
| 42 | `00 00` | frame_cnt（u16 大端） | 0 |

Decoded（帧 1 前 4B，偏移 44..47 = `2A 05 33 06`，ticks 槽已改装 AGC，`hr.c:1085-1089`）：

| 字节 | 来源（40B 帧内） | 字段 | 值 |
|---|---|---|---|
| `2A` | `src[28]` | drv0 绿1 | 0x2A |
| `05` | `src[36] & 0x0F` | gain 绿1 | 5 |
| `33` | `src[32]` | drv1 绿1 | 0x33 |
| `06` | `(src[36]>>4) & 0x0F` | gain 绿2 | 6 |

### 3.9 离线上行发送序列（状态机）

单文件（`ppghr.dat` 等）上行全程走 `BCMD_TEST` 封装，序列与门槛全部代码实证（§3.6.5 / §4.5）：

```
        前置门槛：svcSwFlag[0]==0x01 且 OtaFlag==0，否则 B2A_MakePkt2Send 早退
        （ble2appEx.c:351；采集服务生效 factory_gh3x2x_ble_server.c:416）

IDLE ──文件就绪(截尾自检：fileSize 非 40 倍数按整帧下取整, hr.c:988-989)──▶ 起帧
  ▲                                                                        │
  │                                                        Key=0x05, 应用头[0]=0x01
  │                                                                        ▼
  └◀── Key=0x05 止帧(应用头[0]=0x00) ◀── 数据发完 ◀── 数据 ◀──┐
                                                     │        │ Key=0x06 ×N
       每包发后等 txReady（ATTS_HANDLE_VALUE_CNF，   └─续包──┘ (HR 7帧/包, 按帧号
       zqdata 确认句柄 0x0A14）再发下一包                        seek frame_base*40)

抢占：OtaFlag>0 → 通道让路 OTA（发送门槛不满足，序列中断回 IDLE）
```

ECG 会话（DC_CTRL/DC_STATUS/DC_DATA）的四态状态机（IDLE/COLLECTING/READY/TRANSFERRING）见共识稿 §5.4，此处不重复。

## 4. 下行与控制通道 · 广播 / 服务发现 · 约束

### 4.1 数据通道下行（Goodix，非 B2A GET/SET）

- **zqdata 新模块（B 树）**：`0x0A12` write → `zqdata_ble_write_cback` → RX 环 → RX 线程 → `rx_handler`（应用注册；可转 `Gh3x2xDemoProtocolProcess`）。
- **190E 现网 / 采集固件（A 树）**：`0x0A02`（或 ZQDATA RX `0x0A12`）write → `gh3x2x_ble_write_cback`（`ble_server_main.c:355-376`）→ `Gh3x2xDemoProtocolProcess(pValue, len)`（`:371`，汇顶原厂协议，非 B2A）。

### 4.2 采集起停 / ECG 会话下行命令（B2A `BCMD_TEST` 子键，A 树 `ble2appWrap.c`）

采集控制主通道仍走 **① B2A**（`B2A_RecvData`→`BCMD_TEST` 分支）。新 ECG 采集会话命令（SKG S7 协议 6.8.16/17/18）：

| Key | 名称 | 方向 | payload（**全小端**） | file:line |
|---|---|---|---|---|
| `0x10` | `DC_CTRL` 采集开关 | App→Dev | `UTC(4 LE)+onoff(1)+type(1: 仅 ECG_BIT=1<<4=0x10)+duration_s(2 LE)` | `ble2appWrap.c:11876-11924` |
| `0x11` | `DC_STATUS` 状态 | 双向 | Dev→App `[type:1][status:1]`（IDLE0/COLL1/READY2/TRANS3） | `:11933-11943` |
| `0x12` | `DC_DATA` 随机读 | 双向 | req `[label:2][count:2][offset:4]`；resp `[label:2][got:2][offset:4][data:got]`（`got<count`=EOF），仅 `DC_LABEL_ECG=0x0010` 实现 | `:11982-12037` |

- ECG 会话 `data_collect_session.c`：Start（`:440-560`）写 32B 小端文件头；1Hz worker（`:711-730`）；Stop（`:402-438`）回填 ACC 段 offset。传感器侧 `GH3220_Start_Ecg_Collect`（`gh3x2x_eiot.c:865-878`）。
- PPG 采集门控 `g_PPGCollectFlg`（1=HR，2=SpO2，`gh3x2x_eiot.c:334`）；ECG `g_ECGCollectFlg`（`:339`）。

### 4.3 ECG 会话落盘帧（`ecg_raw.dat`，**小端**，与 PPG 相反）

`data_collect_session.c` 独立实现，字节序 = **小端**（务必与 HR/SpO2 大端区分）。

**32B 文件头**（`DC_WriteFileHeader_ECG`，`:175-232`）：magic `0x31474345`("ECG1", 落盘 `45 43 47 31`, `:182-185`) / version=2 / header_size=32 / utc / duration_s / sample_hz_raw=500 / sample_bytes=4 / acc_section_offset(off18, Stop 回填, `:343-349`) / acc_sample_hz / reserved(8B)。

**ECG 原始流**（off32 起）：`g_gh3220_fifo` 直接 drain，单样本 4B（24bit ADC packed u32），500Hz，不重组（`DC_DrainAndSave_ECG :116-150`）。

**ACC 镜像帧**（ECG 段之后，8B 头 + payload，小端，`DC_Acc_Mirror_Push :246-303`）：`tick(4 LE)+plen(2 LE=n*6)+frame_index(2 LE)+STGsensorRawdata[n]`（X16/Y16/Z16 无填充，直接 memcpy）。

### 4.4 广播 / 服务发现

- **zqdata 128 位 UUID 不进广播**（AD 结构 = 18B=1+1+16，ADV 剩 1B、SCAN-RSP 剩 ~10B 都塞不下），靠**连接后 GATT 发现**（同 190E）。
- 连接前广播 `watchAdvDataDisc[]`（`watch_main.c:318`）：Flags(3B) + 16 位服务 UUID 列表 `0x180A`(DIS)+`0xFFE0`(AMDTP/B2A)+`FFE1/FFE2/FFEB` + 厂商数据(15B)；扫描响应 = 设备名 `SKG VitaPilot-…`。
- 连接后 GATT 发现可见：AMDTP(B2A `0xFFE0`)、gh3x2x(`0x190E`)、DIS、以及采集固件下并行注册的 zqdata `45121540-…`。

### 4.5 约束汇总

- **字节序**：B2A 头/命令头全小端；PPG/HR/SpO2/gsensor 数据帧全大端；ECG 会话全小端（三种并存，勿混）。
- **分片**：B2A 支持 `Index` 分片；28B/gsensor 上行始终 `Index=0` 单包（payload 已 ≤228 保证单 MTU）。
- **MTU**：单包 ≤ 协商 MTU−3；HR 212B 包要求 MTU≥215（实际多协商 247）。传输层新模块固定 244 硬上限。
- **流控**：② 上行每包发后等 `ATTS_HANDLE_VALUE_CNF`（txReady）再发下一包；zqdata 用 `0x0A14` 确认、190E 用 `0x0A04`，独立互不干扰。
- **28B 绕开主 B2A**：`B2A_MakePkt2Send` 栈缓冲 + 显式 `pfSend` 直发数据通道，不依赖主 B2A/AMDTP 连接态。

---

## 5. 代码实证 vs 文档 差异与完整性核查

> 逐条列 **文档声明 / 代码实证 / 结论**。字节布局一律以代码实证为准（上行=② `_collect`，传输层=③ zqdata 模块）。
> 结论图例：✅一致 · ⚠差异（以代码为准）· 📄文档未覆盖 · 🔬代码未坐实需固件核对。

| # | 主题 | 文档声明 | 代码实证 | 结论 |
|---|---|---|---|---|
| **1** | **28B/40B 是否「全大端」** | ①§3/§4「28B 帧全大端」「40B 落盘全大端」 | 主体（ticks/PPG/ACC/cnt）确为大端（`hr.c:778-803` 手工 `>>24`）；但 **40B 尾 12B AGC 是逐字节/半字节位域，无字节序**（`hr.c:814-820`）；ECG 会话是小端 | **⚠部分修正**：「28B 主体+40B 前 28B 主体」全大端成立；「40B 尾 AGC」应表述为**逐字节位域**而非大端。采纳代码分层表述 |
| **2** | **HR 重打包逐字节映射** | ①§5.2：dst[0]=src[28] / dst[1]=src[36]&0x0F / dst[2]=src[32] / dst[3]=(src[36]>>4)&0x0F / dst[4..27]=src[4..27] | 逐字节完全一致（`hr.c:1085-1091`） | **✅一致**（`stage③` 零 REFUTED 得证） |
| **3** | **SpO2 重打包偏移差异** | ①§7：SpO2 用 `src[28]/[30]/[36]/[37]`（≠ HR 的 `[32]`/`[36]>>4`） | `dst[2]=src[30]`(drv0 RED1)、`dst[3]=src[37]&0x0F`(gain RED1)（`spo2.c:934-935`） | **✅一致**。SpO2 与 HR 的 dst[2]/dst[3] 来源确实不同 |
| **4** | **句柄段 0x0A10-0x0A15** | ①§2.2：Service`0x0A10`/RX`0x0A12`/TX`0x0A14`/CCC`0x0A15` | 一致；另坐实 `0x0A11`/`0x0A13` 为特征**声明**句柄（`zqdata_svc.h:22-27`） | **✅一致**（文档少列两个声明句柄，无冲突） |
| **5** | **B2A 封装总长 212B** | ①§6.1：212B=8+4+4+196；图注 Len16=204、命令头 Len=200 | `B2A_MakePkt2Send(...,0x06,temp_buff,200,...)`：内层 Len=200(应用头4+数据196)、B2A Len16=204(命令头4+200)、总包 212=8+204（`hr.c:1063,1122` / `ble2appEx.c:355-377`） | **✅一致且层级归属坐实**（见 §3.6.4） |
| **6** | **SpO2 完整包长** | 文档只给 HR 212B，未给 SpO2 类比 | SpO2 每包 8 帧 → 内层 payload=4+8×28=**228B**（顶格 `ble2appEx.c:355` 的 228 上限），B2A Len16=232，**总包=240B** | **📄文档未覆盖 + ⚠**：补出 SpO2 240B/包；且 228 顶格无余量 |
| **7** | **每包帧数 HR7/SpO2 8** | ①§7：HR=7、SpO2=8（但主叙述以 HR=7 为例，两处帧数并存） | `HR_COV_SEND_FRAME_NUM=7`（`hr.c:70`）、`SPO2_COV_SEND_FRAME_NUM=8`（`spo2.c:53`） | **✅一致**（代码确认两常量不同值） |
| **8** | **上行 TX 句柄「zqdata 0x0A14」** | ①把上行数据叙述为走 zqdata 新模块 `0x0A14` | **上行实证来自 A 树 `_collect`**，走 `GH3X2X_BLE_TX_HDL`（旧 `ble_server`，强制 `svcSwFlag[0]=0x01` 切 ZQDATA 服务），**非 B 树 zqdata 模块**（`factory_gh3x2x_ble_server.c:416` / `ble_server_main.c:595`） | **⚠差异（重要）**：文档把两棵树/两套实现混叙。字节协议真相在 A 树；B 树 zqdata 模块是传输层重写，尚未接生产者。见本文前置说明 |
| **9** | **单帧长度 `len<=MTU-3`** | ①§2.4「`len<=MTU-3`」（暗示随 MTU 动态） | 传输层**固定 244 硬上限**（`ZQ_TX_FRAME_MAX=244`，pop 侧丢弃 `zqdata_ring.c:132-140`）；`zqring_put` 只拒 `n>4096` | **⚠差异**：244 为编译期定值（=247−3），不随协商 MTU 放大；MTU>247 时单帧仍 244 截断 |
| **10** | **B2A payload 上限** | ①只提「栈缓冲 pszBuff[256]」 | 硬上限 `244-16=228B`（`ble2appEx.c:355-359`），超限直接返回 | **📄文档未量化**：补出 228B 硬约束（SpO2 顶格） |
| **11** | **gsensor 18B 帧** | ①主叙述仅 PPG 28B，未给 gsensor 帧布局 | 完整 18B ABI（`user_gsensor_api.c:430-509`）：ticks4+acc6+gyro6(恒0)+id2，大端 | **📄文档未覆盖**：本文 §3.5 补全 |
| **12** | **门控「3 处 + CNF 分支」** | ①§2.5 列 `CMakeLists/rtos.c/watch_main.c/factory_..._main.c` 四处门控 | zqdata 模块 7 文件内**无 `#ifdef SYS_DATA_COLLECT_SVC`**；门控引用点在模块外的调用方固件 | **🔬代码未坐实**：模块侧只证「设计=调用方门控+gc」；四处 `#if` 需在采集固件树核对 |
| **13** | **ACC 是否带 `>>3`/取反** | ①§11.2：组帧层无、板级 `lis2dh12_demo.c` 有（`>>3`、XY 取反） | 组帧 `hr.c:794-799` 直接 int16 大端搬 `stRawdata.acc_*`，无变换；板级变换在上游 `lis2dh12` | **🔬需固件链确认**：`pusFrameGsensordata` 上游是否已 `>>3`/取反，取决于板级到组帧的数据链（本次未逐行走通 lis2dh12→frame 链） |
| **14** | **ticks 语义（帧≥1）** | ①§4「帧1..N 落盘 ticks 为 OS-tick delta，上行被 AGC 覆盖」 | 落盘帧≥1 前 4B = `tempticks+=deltaticks` 递推（`hr.c:726,778`）；上行帧≥1 该 4B 被 AGC 覆盖（`hr.c:1085`）；时间轴靠帧0 UTC+每帧 delta 重建 | **✅一致** |
| **15** | **通道数 4 = ABI 冻结** | ①§11.3：16B PPG raw 固定，改 8 通道 → 帧变 44/56B → APP 错位 | 28/40 常量硬编码依赖 `ALGO_HR_TOTAL_CHN=HBA_TOTAL_CHN`（`hr.c:66`）；重打包 `src[28/30/32/36/37]` 偏移写死假设 4 通道 40B | **✅一致**（代码确认偏移写死，改通道数即失配） |

**未坐实项（需以采集固件编译/运行核对）：**
- **门控四处 `#if SYS_DATA_COLLECT_SVC`**（`rtos.c` / `watch_main.c` / `factory_..._main.c` 的 `ATTS_HANDLE_VALUE_CNF` 分支）——在 zqdata 模块 7 文件之外，本次实证只覆盖模块内（[§5](#5-代码实证-vs-文档-差异与完整性核查) 第 12 条）。
- **ACC `>>3`/XY 取反的层级归属**——板级 `lis2dh12_demo.c:287-289/603-605` 有变换，但「组帧层拿到的 `pusFrameGsensordata` 是否已带变换」需走通 lis2dh12→frame 数据链（第 13 条）。
- **B 树 zqdata 新模块的实际上行链路**——生产者（`gui_app_press.c` 10+ 处 `gh3x2x_ble_send_to_zqdata`）改调 `zqdata_send`、`rx_handler` 应用注册均为**遗留待接**（文档①§2.5「遗留」），代码实证仅证模块骨架，未证端到端跑通。
- **DevType 5B 二进制码语义**（真机 `68 39 71 25 81`）——仅 hex 展示，位含义未定义。

---

## 6. 附录：关键结构体 / 常量 / file:line 索引

### 6.1 关键常量

| 常量 | 值 | 出处 |
|---|---|---|
| `HR_COV_FRAME_AGC_LEN` | 12 | `hr.c:74`（A 树） |
| `HR_COV_SEND_FRAME_NUM` | 7 | `hr.c:70` |
| `SPO2_COV_SEND_FRAME_NUM` | 8 | `spo2.c:53` |
| HR/SpO2 落盘帧长 | 40B | `hr.c:628` / `spo2` 对称 |
| HR/SpO2 上行帧长 | 28B | `hr.c:973` |
| gsensor 帧长 | 18B | `user_gsensor_api.c` |
| B2A payload 上限 | 228B（244−16） | `ble2appEx.c:355` |
| `BCMD_TEST` | 0x08 | `ble2appEx.h:87-97` |
| `ZQ_TX_RING_SIZE` / `ZQ_RX_RING_SIZE` | 4096 / 512 | `zqdata.c:28,31`（B 树） |
| `ZQ_TX_FRAME_MAX` / `ZQ_RX_FRAME_MAX` | 244 / 244 | `zqdata.c:34,37` |
| `ZQ_TX_READY_TMO_MS` | 1000 | `zqdata.c:55` |
| zqdata 句柄段 | 0x0A10–0x0A15 | `zqdata_svc.h:18-31` |
| UUID Service/RX/TX part | 0x1540 / 0x1541 / 0x1542 | `zqdata_svc.c:32-34` |

### 6.2 关键结构体 / 符号

- `STGsensorRawdata { GS16 sXAxisVal; GS16 sYAxisVal; GS16 sZAxisVal; }` = 6B 无 padding（A 树 `eqc_data_type.h:1338-1342`；B 盘 clangd `gh_demo_inner.h:96`）
- `zqring_t { buf/size/head/tail/count/dropped }`（`zqdata_ring.h:23-30`）
- `svcZqdataGroup`（`zqdata_svc.c:121-129`）、`zqdataList` 属性表（`:68-118`）
- `AttsAddGroup`/`AttsHandleValueNtf`/`AppConnIsOpen`(`app_api.h:782`)（Cordio）
- `Gh3x2xDemoProtocolProcess`（`gh_demo_protocol.c`）、`B2A_MakePkt2Send`（`ble2appEx.c:339`）

### 6.3 file:line 索引（两树分栏）

**A 树｜采集固件 `_collect`（上行协议最高权威）** `E:/1/apollo4_watch_s7_collect/product/apollo_eiot`：
- `gh3x2x_demo_algo_call_hr.c`：落盘 `:773-823`、AGC 拆位 `:327-331`、起始帧 `:426-491`、上行/重打包 `:973/988-989/1042-1092/1122`、起止 `:998-1002/1179`
- `gh3x2x_demo_algo_call_spo2.c`：落盘 `:641-703`、AGC `:350-354`、重打包 `:920-938`、常量 `:50/53/61`
- `user_gsensor_api.c`：18B 帧 `:430-509`、起始帧 `:390-428`、上行 `:590-705`、常量 `:31/32`
- `Ble2App/ble2appEx.c`：B2A 头组装 `:57-71`、CRC `:64`、MakePkt2Send `:339-425`（上限 `:355`、pfSend `:412-414`）、RecvData `:559/599-648`
- `Ble2App/ble2appEx.h`：Status 枚举 `:26-43`、BCMD_TEST `:87-97`、命令头 `:74-81`、DC key `:118`
- `Ble2App/ble2appWrap.c`：DC_CTRL `:11876-11924`、DC_STATUS `:11933-11943`、DC_DATA `:11982-12037`
- `factory_gh3x2x_ble_server.c`：`svcSwFlag[0]=0x01` `:416`、AddGroup `:371/375-377/411-430`；`.h`：UUID/句柄 `:54-58/63-87`
- `factory_gh3x2x_ble_server_main.c`：write_cback `:355-376`、send_to_zqdata `:576-632`(单包 ntf `:595`)、TxReady `:201-215`
- `data_collect_session.c`：文件头 `:175-232`、ECG drain `:116-150`、ACC 镜像 `:246-303`、Start/Stop `:440-560/402-438`
- `gh3x2x_eiot.c`：ECG 起停 `:865-878/880-894`、门控标志 `:334/339`
- `crc16_ccitt_false.c:24-42`

**B 树｜zqdata 新模块（GATT 传输层最高权威）** `E:/1/apollo4_watch_s7/product/apollo_eiot/zqdata`：
- `zqdata_svc.h`：句柄枚举 `:18-31`
- `zqdata_svc.c`：UUID `:26-38`、属性表 `:68-118`、声明体 `:50/54`、CCC 初值 `:64`、服务组 `:121-129`、add/remove `:142-152`、write_cback `:132-139`
- `zqdata.h`：接口注释 `:8-9/36-41`、门控注释 `:12`、API 签名 `:29/33-34/41/50-51/55/58/65`
- `zqdata.c`：配置宏 `:27-56`、缓冲 `:59-60`、txReady 初值 `:68`、zq_notify `:71-83`、send `:86-98`、on_att_cnf `:107-113`、wait_ready `:116-128`、ble_notify `:132-145`、TX 循环 `:147-159`、on_rx `:167-177`、rx_task `:184-200`、init `:203-217`
- `zqdata_ring.h`：记录格式 `:5-8`、lock 注释 `:9-11`、结构 `:23-30`
- `zqdata_ring.c`：记录 `:12-13`、lock `:16-32`、u16 memcpy `:35-36`、put 丢弃 `:60-74`、绕尾 `:120-130`、pop 跳过 `:132-140`
- `CMakeLists.txt`：父挂载 `:4-5`、门控注释 `:12-13`、库 `:16-25`

---

> 本文档为合成稿。上行数据帧字节布局以 A 树 `_collect` 代码实证为最高权威，GATT 传输层以 B 树 zqdata 模块代码实证为准，设计意图/跨模块语义补自文档①。差异见 [第 5 章](#5-代码实证-vs-文档-差异与完整性核查)。相关：[protocol-b2a.md](protocol-b2a.md)（主控制通道 B2A 协议全集）。
