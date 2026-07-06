# S7 · B2A 协议实现规格（BlueTrace 侧）

> 整理自 `E:\1\apollo4_watch_s7\Docs\03_BLE与协议`（14 个文件，9 代理并行深读 + 交叉校验，2026-07-02）。
> 本文是**实现级收敛稿**：只保留写代码必需的事实，按「设备维护控制台」用到的范围裁剪；
> 全量提取件在 [_raw/](_raw)（transport/header/manualA/manualB/otaLog/appScan + 三份校验报告）。
> 完整性结论见 [completeness-audit.md](completeness-audit.md)；实施计划见 [plan.md](plan.md)。

## 1. BLE 传输层（AMDTP 服务）

| 项 | 值 |
|----|----|
| Service | `0000FFE0-3C17-D293-8E48-14FE2E4DA212`（128-bit 私有 base `0000xxxx-3C17-D293-8E48-14FE2E4DA212`） |
| RX 特征（App→表，写命令） | `0000FFE1-...`，**Write Without Response** |
| TX 特征（表→App，应答/上报） | `0000FFE2-...`，**Notify**（CCC `0x2902`，安全级 NONE，开启无需加密） |
| 广播名 | `SKG WATCH S7-XXXX`（XXXX = MAC[1]、MAC[0] 各转 2 位 ASCII hex）——**仅展示用，不作协议判据** |
| **设备识别（nRF Connect 式分层）** | ① 广播特征匹配（首选）：广播 16-bit UUID 表含 `FFE0`（S7 实测广播 180A+FFE0/FFE1/FFE2/FFEB）；② 连接后确认（兜底）：service discovery 查 RX=FFE1 + TX=FFE2 同时存在；③ 名称/MAC 过滤 = 扫描页用户输入的辅助过滤（不写死）。实现：`shared/.../s7/B2aDetect.kt`。**不限 S7 一款**——同服务的任何 B2A 设备均可接入 |
| 测试真机（联调目标） | MAC `71:61:48:19:FC:C4`（用户提供 2026-07-02）→ 广播名 `SKG WATCH S7-FCC4`；常量 `S7_TEST_MAC`（shared/domain/Device.kt），仅测试期定位目标用，非白名单 |
| 广播内容 | Flags + 16bit UUID 表（0x180A + 0xFFE0/E1/E2/EB）+ 厂商数据（flags 0x21 + device_type 5B + MAC 6B + CRC8） |
| MTU | 目标 247（DLE 251）；机制文档有矛盾（AttcMtuReq vs 服务消息触发），App 侧只需接受协商结果 |
| 配对 | Just Works（NoInput/NoOutput）；TX CCC 不要求加密 → **连上即可通信**；LESC 与否文档矛盾，对 App 无影响 |
| 连接数 | 设备侧最多 1 连接 |

## 2. 帧信封（B2A，全小端、packed 无填充、无转义）

```
首包：  [B2A_HEAD 8B][B2A_DATA_CMD 4B][param...]
续包：  [B2A_HEAD 8B][裸 param 续段...]          ← 不带命令头，命令字由首包延续
```

**B2A_HEAD（8B）**

| 偏移 | 字段 | 宽 | 说明 |
|------|------|----|------|
| 0 | ucStartFlag | 1 | SOF = **0xBB** |
| 1 | ucStatus | 1 | 状态位域（下表）；多包 ID 占 bit[5:4] |
| 2 | uiLen | 2 LE | 帧头之后的字节数（首包 = 4 + paramLen；续包 = 续段长） |
| 4 | uiCRC | 2 LE | **CRC16-CCITT-FALSE**（poly 0x1021 / init 0xFFFF / 不反转 / xorout 0），**覆盖偏移 8 起 uiLen 字节**（不含帧头） |
| 6 | uiIndex | 2 LE | 包序号：首包 0，续包递增 |

**ucStatus 位**：`0x00 SUCC`、`0x01 FAIL`（CRC 错被动 NAK）、`0x02 ACK`（请求方置位=要求应答）、`0x04 IS_MULTI_PKT`、`0x08 MULTI_PKT_END`、bit[5:4] 多包ID `(s>>4)&3`、`0x80 OTA_PART`。

**B2A_DATA_CMD（4B，仅首包）**：`ucCmd(1) | ucKey(1) | uiParamLen(2 LE) | szParam[...]`。

**多包重组**（表→App）：首包带命令头置 `IS_MULTI_PKT`；续包裸 payload、uiIndex 递增、同多包 ID（不符即**整片丢弃**）；末包置 `MULTI_PKT_END`。
**已证实的短帧特例**：产测握手帧 `BB 02 03 00 2D 46 00 00 08 01 01`（uiLen=3 < 4，无 paramLen 域，CRC=0x462D 自洽）→ 解码器须容忍 `uiLen<4` 的短帧（cmd=payload[0]、key=payload[1]、其余为参数）。

**应答规则**：应答回显同 Cmd/Key（设备发起的上报用自己的 Key，如日志块 0x07/0x09）；请求帧头置 `EHST_ACK` 才要求应答；**协议无超时/重传定义 → App 自建单飞（one-outstanding）串行队列 + 3s 超时**（数值需实测标定）。

**通用应答 CommAck（1B）**：0=成功，1=失败，5=不支持；SET 类响应统一 1B CommAck。

**错误码 EBEC（0x00–0x0D）**：SUCC/FAIL/TIMEOUT/FORMAT/MEMORY/NOT_SUPPORT/PARAM/BUSY/LOW_BAT/NO_DATA/MD5/CRC/FATAL/FSUM_FAIL。

## 3. 一级命令字

`0x01 BOND / 0x02 GET / 0x03 SET / 0x04 PUSH / 0x05 IND / 0x06|0x86 RPT_DATA / 0x07 DEV_CTRL / 0x08 TEST / 0x0F FILE_TRANS`

## 4. 维护控制台用到的命令（逐字节）

### 4.1 时间（GET 0x02/0x01 · SET 0x03/0x01）

9B：`uiYear(2 LE) | ucMonth | ucDay | ucHour | ucMin | ucSec | ucWeek | timezone`
— GET 响应的 timezone 为 **int8 有符号**；SET 请求 timezone **0 = 保持设备本地时区不改**。SET 响应 = 1B CommAck。

### 4.2 设备信息（GET 0x02/0x21，变长 TLV）

`swLen(1)+swVer | mdLen(1)+mdVer | secLen(1)+secVer | reserved(1B=1) | bpLen(1)+bpVer`（均 ASCII）。
副作用：固件置 `ucOtaMode=0xFF`。

### 4.3 身份 SN（GET 0x02/0x26，定长 59B）

`DevType[5] | SN[12] | BleMac[6] | IMEI[16] | ICCID[20]`（零填充；**MAC/IMEI/ICCID 字节序待实机核对**——界面按原序 hex 展示并标注）。

### 4.4 电量（GET 0x02/0x24，定长 10B）

`uiCapacity(2 LE) | uiVol(2 LE, mV) | ucPower(1, %) | ucStatus(1, 充电状态) | ulLastTime(4 LE, 恒 0)`。

### 4.5 其它 GET

`0x22 GET_DEV_FUNC`→u32 LE 功能掩码（**逐位含义缺失**，界面只显示 hex）；`0x23 GET_DEV_BLE_MAC`→6B；`0x28 GET_BOND`→1B 绑定状态。

### 4.6 用户信息（GET 0x02/0x04 · SET 0x03/0x04）

GET 7B：`ucHeight | ucWeight | ucGender | uiBirthYear(2 LE) | ucBirthMonth | ucBirthDay`
SET 8B：同上 + `ulReserve(1B)`；身高 cm、体重 kg。SET 响应 1B CommAck。

### 4.7 设备控制（DEV_CTRL 0x07，空 payload）

| Key | 动作 | 应答 |
|-----|------|------|
| 0x01 | 关机 | **不回包**（固件强制 ucIsOk=0）→ 以断链为完成判据 |
| 0x02 | 重启 | 同上 |
| 0x03 | 恢复出厂 | 同上 |
| 0x04 / 0x05 | 找手表 / 结束 | 带 EHST_ACK 时回 1B |

### 4.8 日志拉取（DEV_CTRL 0x07/0x07 请求 → 0x07/0x09 回传块）

- 请求 payload：`ucModel(1B，==1 拉 now+bak 两文件) | szPassthru[]`（透传，语义未定；示例 `01 00 00 00 00`）；
- 回传块（设备发起，Cmd/Key=0x07/0x09）：`szReqPrefix[]（=请求 payload 逐字节回显）` + `szLogChunk[]`（裸日志片，~200+B/块，5ms 节流）；
- **无块序号、无 EOF 标志** → App 按到达顺序拼接，**空闲超时（建议 4s）判完成**，UI 注明「日志可能不完整」；源文件 `eiotlog.log`+`eiotlog2.log`（300KB 轮替），两文件间无边界分隔。

### 4.9 心跳（IND 0x05/0x0C，设备→App，8B）

`utc(4 LE) | seq(2 LE 自增) | battery(1) | reserved(1)`；App 可回 8B（utc+seq+network_status+reserved），设备不逐字节解析。被动解析可白捡电量与链路活性。

### 4.10 文件传输（FILE_TRANS 0x0F —— 控制台一期不做 OTA，仅记录）

REQ 0x04（8B：moduleId/isOffset/fileCount/rsv/totalSize u32）→ 12B 响应（status/moduleId/fileCount/currIdx/**ulSliceMaxSize u32**/ulOffset u32）；START 0x01（16+N）；DATA 0x02（裸块，成功响应 9B 含 u32 字节累加和）；STOP 0x03；OFFSET 0x06（4B）。**端到端编排（文件清单/生效触发）文档缺失 → OTA 暂缓**。

## 5. App 侧必须自建的可靠性层（协议未定义）

1. **单飞串行队列**：同一时刻最多一个未决请求（无事务 ID，同 Cmd/Key 并发无法区分应答）；
2. **超时**：默认 3s，超时归还队列并报错（重试由用户触发，避免对不回包命令误重试）；
3. **不回包命令**（关机/重启/恢复出厂）：发送后**监听 LinkState 断链**作为成功判据，超时 10s 未断链报「设备未响应」；
4. **设备主动帧**（心跳/日志块/告警）与应答共用 Notify 通道：分发器先按「未决请求的 Cmd/Key 匹配」拦截应答，其余走上报处理器；
5. **下行分片**：控制台全部命令 payload ≤ 20B，**永不分片**；日志/OTA 上行重组按 §2 规则实现。

## 6. golden bytes（现有唯一完整帧）

`BB 02 03 00 2D 46 00 00 08 01 01` —— 产测握手（Cmd=0x08 Key=0x01 选择子 0x01=BLE；短帧特例；CRC16-CCITT-FALSE(`08 01 01`)=0x462D）。用作信封编解码 + CRC 单测锚点；其余命令 golden 需实机抓包补齐（见 audit §10）。
