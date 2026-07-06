# BlueTrace 协议分析：App 层 / 扫描识别 / UART & 4G 定位

> 通读 4 份文档后的提炼。所有出处沿用原文档标注的「文件:行号」，源文档路径见文末。

---

## 1. App 层协议总览（02-app-protocol.md）

### 1.1 与 B2A 手册的关系 —— 同一协议，另一视角

`02-app-protocol.md` 本身就是 **B2A（Ble 2 App）应用层协议手册**，不是与某份「B2A 手册」并列的第二份文档。它描述的正是手表 ↔ 手机 App 的自定义二进制业务协议，代号 **B2A**。因此几份文档的关系是「同一 B2A 协议的不同切面」：

- **02-app-protocol.md**：B2A 帧结构 + 命令分发 + 收发调用链（协议主体）。
- **05-scan-identify.md**：连接前的广播/扫描识别（B2A 承载服务 AMDTP 的 UUID `0xFFE0` 等在广播里怎么出现）。
- **manual-uart-protocol.md §4**：产测时 UART0 用 `SYS_BleRecvData()` 把字节灌进**同一个 B2A 解析入口**，即「B2A 协议可经工厂 UART 注入，与 BLE 完全同帧」（pc_factory_mode_main.c:1476/1486）。

结论：**B2A 是唯一的应用层业务协议，BLE 与工厂 UART 是它的两条注入通道**，二者共用 `B2A_RecvData` 解析栈。

### 1.2 帧结构（两层）

| 层 | 结构 | 关键字段 | 出处 |
|---|---|---|---|
| 外层帧头 `B2A_HEAD`（8B，小端） | SOF/status/len/CRC16/index | SOF=`0xBB`；len 不含 8B 帧头；CRC16 仅覆盖帧头之后 payload | ble2appEx.h:74-81；写入 ble2appEx.c:57-71；解析 :595-604 |
| 内层命令头 `B2A_DATA_CMD`（4B） | Cmd(1)+Key(1)+paramLen(2,LE)+param | **仅首包 `uiIndex==0` 才带命令头**，续包只放裸 payload | ble2appEx.h:513-525；ble2appEx.c:236-243 |

- **CRC**：CRC-16/CCITT-FALSE（Poly=0x1021, Init=0xFFFF, 无反转, Xorout=0），范围 = `pszData+8`，长 `uiLen-8`。crc16_ccitt_false.c:14-42；ble2appEx.c:64/644。
- **承载层**：Ambiq **AMDTP** GATT 服务（非 custss 空示例）。UUID：Service `0xFFE0` / RX `0xFFE1`(Write No Rsp) / TX `0xFFE2`(Notify) / ACK `0xFFEB`（本基线不建服务）。svc_amdtp.h:63-80。

### 1.3 命令字与分发（App 侧「状态机」实为两级分发表）

文档明确指出**没有独立的超时重传状态机**，只有「CRC 失败立即回 `EHST_FAIL` 被动 NAK，由 App 重发」（ble2appEx.c:645-652）。所谓 App 侧状态机主要有两处：

1. **命令分发**：外层 `switch(ucCmd)` + 内层 `if/else-if(ucKey)` 两级。入口 `B2A_RecvDataHandle`（ble2appWrap.c:11698，switch @:11883）。命令字 10 个有效项：

   | 值 | 命令 | 语义 |
   |---|---|---|
   | 0x01 | BOND | 绑定 |
   | 0x02 | GET | App 读设置 |
   | 0x03 | SET | App 写设置 |
   | 0x04 | PUSH | App→设备推送（来电/消息/天气）|
   | 0x05 | IND | 指示/控制确认 |
   | 0x06 | RPT_DATA | 同步健康数据 |
   | 0x07 | DEV_CTRL | 设备控制（关机/复位/找表）|
   | 0x08 | TEST | 工厂测试 |
   | 0x0F | FILE_TRANS | 文件传输/OTA |
   | 0x86 | RPT_DATA2 | =(0x80\|RPT_DATA)，与 0x06 合并 case |

   （ble2appEx.h:85-104；各 case ble2appWrap.c:11886/12087/12225/…）

2. **文件传输/OTA 分片状态机**：`EOTA_IDLE→REQ→READY→START→TRANS_DATA(续片)→END`，用帧头状态位 `EHST_IS_MULTI_PKT/MULTI_PKT_END` + 高位 2bit 包 ID `(ucStatus>>4)&0x03` 驱动。ble2appWrap.h:23-34；分片判定 ble2appWrap.c:11771-11799。

### 1.4 绑定 / 认证流程

- **配对层（BLE）**：Just Works（`SMP_IO_NO_IN_NO_OUT`）+ 仅绑定（`DM_AUTH_BOND_FLAG`，无 SC），TX CCC 安全级别 NONE 不要求加密。05-scan-identify.md §6。
- **业务层（B2A BOND 命令，0x01）**：子命令 `ENUM_B2A_BOND_KEY_TYPE`（ble2appEx.h:121-133）：`REQ(0x01)/DEV(0x02)/DEL(0x03)/CANCEL(0x04)/KEYCODE(0x05)/REQINFO_GET(0x06)/REQINFO_RESET(0x07)`，处理 ble2appWrap.c:11891-12085。这是应用层绑定握手（请求/设备信息/删除/取消/配对码），**独立于 BLE SMP 配对**，文档未展开每个 Key 的 payload 字段（属未决项 17）。

---

## 2. 扫描识别（05-scan-identify.md）

面向「不连接、仅凭广播识别本手表」，基线 `dev-en-collect`（海外 dev-en 同源）。

### 2.1 广播过滤规则（TL;DR，满足任一即可判定）

| 优先级 | 过滤条件 | 出现位置 |
|---|---|---|
| ★★★ | 设备名前缀 `SKG WATCH S7-` | **SCAN_RSP**（0x09 Complete Local Name）|
| ★★★ | 16-bit Service UUID **`0xFFE0`** | ADV_IND（AD type 0x03）|
| ★★☆ | 厂商数据前 5 字节 `68 39 71 25 81`（海外）| ADV_IND（AD type 0xFF，紧跟内部 flags `0x21`）|

> 关键坑：**设备名在 SCAN_RSP 不在 ADV_IND**——被动扫描只能拿到 UUID 与厂商数据，要拿名字必须主动扫描（Android/iOS `BluetoothLeScanner` 默认 active scan，可直接拿到）。

### 2.2 设备名格式

- 完整名 = `"SKG WATCH S7-"`（13 字符）+ **4 位大写 hex 后缀**，固定 17 字节，无 CRC8。
- **后缀算法（历史易错点）**：`后缀 = 大写Hex(g_BLEMacAddress[1]) + 大写Hex(g_BLEMacAddress[0])`，即先 MAC 字节[1] 后字节[0]，各展开 2 位大写 hex。**不是**末 2 字符、不是倒序。
- 反查：嗅探器显示地址尾部 `…:AB:CD`（MAC1=0xAB, MAC0=0xCD）→ 名字必为 `SKG WATCH S7-ABCD`。
- 出处：`SDK_AdvDataDiscUpdate()` watch_main.c:1502-1503；`SYS_One2Two()` System.c:176-187。

### 2.3 厂商数据布局（AD type 0xFF，共 14 字节）

```
flags(1) + device_type(5) + MAC(6) + CRC8(1) = 14
0x21     68 39 71 25 81    g_BLEMacAddress[0..5]  运行时算
         （海外；国内 …25 80 被 #if 0 关闭）  原序 LSB-first  （占位 0xCC）
```

- **UUID 列表小端存放**：`0xFFE0` 空中字节为 `E0 FF`；AD type 是 `0x03`（Complete）不是 `0x02`。
- 厂商段 6 字节 MAC 为 `[0..5]` 原序（与空中 AdvA 一致）；**注意与设备名后缀只取 `[1][0]` 两字节的取用方式不同，勿混用**。
- CRC8 仅在广播包末字节，对 `flags+device_type+MAC` 共 12 字节求值；扫描端识别一般不必校验。
- 完整 ADV_IND payload 30 字节、SCAN_RSP 19 字节，实例见原文 §8。
- 出处：`watchAdvDataDisc[]` watch_main.c:311-339；`watchScanDataDisc[]` :398-406。

### 2.4 广播链路参数

广播间隔固定 1000ms（1600×0.625ms），duration=0 连续广播，最大连接数 1，期望连接参数 min 10ms / max 22.5ms / latency 0。watch_main.c:117-126。

---

## 3. UART / 4G 两份手册定位 + 对 BLE 控制台实现的影响

### 3.1 一句话定位

- **manual-uart-protocol.md**：描述手表内部所有物理 UART 口——UART0（921600，日志/调试 shell/产测注入口）、UART3（9600/38400，血压模组 WKJJ202B 私有帧 `CD F2`）、UART bootloader（有线救砖升级）；**其中只有 UART0 产测通道会复用 B2A 帧，其余均为板内串口调试/外设/引导用途**。
- **manual-4g-protocol.md**：描述 Apollo4 主控(AP) 与 ASR3603 蜂窝模组(CP) 之间的**本地 SPI 链路**（IOM3, 24MHz, 全双工 + MRDY/SRDY 握手 + GSM 0710 MUX），承载 AT/PS/OTA/NITZ/HTTPS；**这是一条独立于 BLE 的蜂窝直连云端链路，物理层是 SPI 不是 UART**。

### 3.2 是否影响 BLE 控制台实现 —— 确认：均不直接影响

**4G 手册（不影响）**：文档反复强调「这是**独立于 BLE 的链路**：BLE 连手机，ASR3603 走蜂窝网直连云端」（manual-4g-protocol.md §1）。唯一交集是业务层面——同一份健康数据有 BLE 与 4G 两条出口，由 `g_upload_flag` 互斥（ble2appWrap.c:8849）。BLE 控制台若只做 BLE 链路的连接/收发/B2A 交互，**无需触碰 SPI/MUX/AT 这一整套**。

**UART 手册（基本不影响，但有一处需知晓）**：
- UART3（血压）、UART bootloader、4G 对照段——与 BLE 控制台**完全无关**。
- **UART0 产测通道**唯一需要知晓的点：产测 HEX 模式经 `SYS_BleRecvData()` 注入的是**与 BLE 完全同帧的 B2A 协议**（manual-uart-protocol.md §4；pc_factory_mode_main.c:1476/1486）。这意味着若 BLE 控制台要复现/调试 B2A 帧，UART0 是一条等价的旁路注入通道（进入产测握手帧 `BB 02 03 00 2D 46 00 00 08 01 01`），但**它不改变 B2A 协议本身，也不改变 BLE 控制台的 BLE 侧实现**。

**结论**：两份手册对「BLE 控制台」的 BLE 链路实现均无直接影响。4G 是并行的 SPI 云链路；UART 是板内串口，仅 UART0 产测口与 B2A 同帧（可作旁路，非必须）。**预计不影响，已确认成立。**

---

## 源文档出处

- E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\02-app-protocol.md
- E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\05-scan-identify.md
- E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\manual-uart-protocol.md
- E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\manual-4g-protocol.md

（代码级出处「文件:行号」均引自上述文档正文，指向 `product/apollo_eiot/` 工程源码。）