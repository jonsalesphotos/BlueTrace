# S7 采集固件 OTA · 指令交互与流程（真机 golden 坐实）

> **状态**：2026-07-08 定稿。**首份带 TX 帧的真机日志 `ota2.log` + 固件侧文档化头 `b2a_protocol.h` 双坐实**——本文每条指令的字节布局均有真机 hex + 固件 file:line，不再是反推。
> **真源**：真机报文 = `E:\1\apollo4_watch_s7\Docs\log\ota2.log`（一次完整成功的 14 文件 ota_all 刷写，2026-07-08 18:06:19→18:16，B→A 切区）；字段定义 = 固件 `product/apollo_eiot/Ble2App/b2a_protocol.h` + `ble2appWrap.c`（file:line 在册）；累加/分片实现见 App 侧 [`shared/.../s7/`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/)。
> **一句话**：App(BLE 中心/主机) 在现网 B2A/AMDTP 通道上，用 `FILE_TRANS`(Cmd `0x0F`) 五条子命令把 14 个文件逐个推给手表(外设/从机)，末文件 STOP 后设备自校验→切资源区→自复位生效。全程 App 只写 + 收 ack，看不到设备内部进度。

---

## 0. 会话时序（一图速览）

<div class="fig">
<svg viewBox="0 0 840 430" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>S7 OTA 会话时序：App 与手表的指令交互</title>
<desc>左侧 App(主机)、右侧手表(从机)两条生命线；REQ 报总量→逐文件 START/切片/STOP→末 STOP 后设备自复位。App 只写与收 ack。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:10.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.ll{stroke:var(--line);stroke-width:1.5;}
.arw{stroke:var(--fg);stroke-width:1.2;marker-end:url(#ah);}
.arwb{stroke:var(--accent);stroke-width:1.2;marker-end:url(#ahb);}
.lp{fill:none;stroke:var(--muted);stroke-width:1;stroke-dasharray:4 3;}
</style>
<defs>
<marker id="ah" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="var(--fg)"/></marker>
<marker id="ahb" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="var(--accent)"/></marker>
</defs>
<text x="150" y="22" text-anchor="middle" class="t">App（手机 · BLE 主机）</text>
<text x="670" y="22" text-anchor="middle" class="t">手表（S7 · 从机/外设）</text>
<line x1="150" y1="30" x2="150" y2="410" class="ll"/>
<line x1="670" y1="30" x2="670" y2="410" class="ll"/>

<line x1="150" y1="60" x2="668" y2="60" class="arw"/>
<text x="410" y="54" text-anchor="middle" class="m">REQ 0x04  <tspan class="s">报会话总量(module/文件数/总字节) 8B</tspan></text>
<line x1="670" y1="92" x2="152" y2="92" class="arwb"/>
<text x="410" y="86" text-anchor="middle" class="m a">12B 应答  <tspan class="s" font-weight="400">status/sliceMaxSize=3944  (先等设备 MMI 授权)</tspan></text>

<rect x="60" y="112" width="720" height="196" rx="4" class="lp"/>
<text x="70" y="128" class="s">对每个文件循环（本场 14 次）:</text>

<line x1="150" y1="150" x2="668" y2="150" class="arw"/>
<text x="410" y="144" text-anchor="middle" class="m">START 0x01  <tspan class="s">文件头(名/大小/分片尺寸) 16+N</tspan></text>
<line x1="670" y1="176" x2="152" y2="176" class="arwb"/>
<text x="410" y="170" text-anchor="middle" class="m a">1B ack <tspan class="s" font-weight="400">EBEC_SUCC</tspan></text>

<line x1="150" y1="212" x2="668" y2="212" class="arw"/>
<text x="410" y="206" text-anchor="middle" class="m">TRANS 0x02 ×(≤17 背靠背无响应写)  <tspan class="s">一切片=一条多包消息</tspan></text>
<line x1="670" y1="238" x2="152" y2="238" class="arwb"/>
<text x="410" y="232" text-anchor="middle" class="m a">9B DATA ack <tspan class="s" font-weight="400">recvLen+累加和  App 自核→推进 / 不符则重发本切片</tspan></text>
<text x="410" y="258" text-anchor="middle" class="s">↑ 切片循环直到本文件发完</text>

<line x1="150" y1="282" x2="668" y2="282" class="arw"/>
<text x="410" y="276" text-anchor="middle" class="m">STOP 0x03  <tspan class="s">无 payload(设备忽略)</tspan></text>
<line x1="670" y1="300" x2="152" y2="300" class="arwb"/>
<text x="410" y="296" text-anchor="middle" class="m a">1B ack <tspan class="s" font-weight="400">SUCC / FSUM_FAIL(尺寸校验)  设备 idx++</tspan></text>

<line x1="150" y1="342" x2="668" y2="342" class="arw"/>
<text x="410" y="336" text-anchor="middle" class="m">末文件 STOP（idx==fileCount）</text>
<text x="670" y="366" text-anchor="middle" class="s">设备: ResCheck 自校验 → DoRecvAfter → SetFlag → 跳 SecBL 刷写</text>
<text x="670" y="384" text-anchor="middle" class="m a">SYS_Reset() 自复位生效</text>
<text x="150" y="384" text-anchor="middle" class="s">App: 收末 STOP SUCC = DoneDownload</text>
<text x="410" y="404" text-anchor="middle" class="s">断连 → App 重连 → 读取显示当前版本（不做校验；App 观测不到设备内部生效）</text>
</svg>
</div>

**读法**：黑箭头 = App→表（写），蓝箭头 = 表→App（notify 应答）。App 全程只做两件事：**按序写命令 + 收 ack 核对**。设备内部的 `OTAR_*` 进度、切区、SecBL 刷写 App 一律看不到——**成功判据只能是"末 STOP 收 SUCC → 重连读版本号变化"**。

---

## 1. 承载与帧信封

### 1.1 承载通道

- **承载 = AMDTP GATT（现网 B2A 那条通道）**，与设备控制台/对时/拉日志**同一条特征**，不是独立 amota 服务。App 侧现有 `AndroidBleClient` 连接 S7 时已订阅这条 notify、拿到写特征——**OTA 直接复用，无需新 GATT 发现**。
- 方向：App→表 = GATT Write（数据切片走 Write Without Response 背靠背）；表→App = GATT Notify。
- **每次 GATT 写的可用载荷 = 协商 MTU − 3**（3B = ATT opcode 1 + handle 2）。本场协商 MTU=247 → 可用 244。

### 1.2 B2A 帧结构

每一帧（无论方向）= **8B 帧头 + 4B 命令头 + param**，全小端、packed、无转义。

<div class="fig">
<svg viewBox="0 0 840 170" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>B2A 帧信封字节布局</title>
<desc>8B 帧头(SOF/status/uiLen/uiCRC/uiIndex) + 4B 命令头(cmd/key/paramLen) + N 字节 param。</desc>
<style>
.t{fill:var(--fg);font-size:11px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:9.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:10px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.hd{fill:var(--code);stroke:var(--accent);stroke-width:1.3;}
</style>
<g>
<rect x="20" y="40" width="46" height="30" class="bx"/><text x="43" y="59" class="m">BB</text>
<rect x="66" y="40" width="60" height="30" class="bx"/><text x="96" y="59" class="m">status</text>
<rect x="126" y="40" width="76" height="30" class="bx"/><text x="164" y="59" class="m">uiLen</text>
<rect x="202" y="40" width="76" height="30" class="bx"/><text x="240" y="59" class="m a">uiCRC</text>
<rect x="278" y="40" width="76" height="30" class="bx"/><text x="316" y="59" class="m">uiIndex</text>
<rect x="354" y="40" width="60" height="30" class="hd"/><text x="384" y="59" class="m a">cmd</text>
<rect x="414" y="40" width="60" height="30" class="hd"/><text x="444" y="59" class="m a">key</text>
<rect x="474" y="40" width="86" height="30" class="hd"/><text x="517" y="59" class="m">paramLen</text>
<rect x="560" y="40" width="250" height="30" class="bx" stroke-dasharray="4 3"/><text x="685" y="59" class="m">param[paramLen] …</text>
<text x="43" y="34" class="s" text-anchor="middle">1B</text>
<text x="96" y="34" class="s" text-anchor="middle">1B</text>
<text x="164" y="34" class="s" text-anchor="middle">2B LE</text>
<text x="240" y="34" class="s" text-anchor="middle">2B LE</text>
<text x="316" y="34" class="s" text-anchor="middle">2B LE</text>
<text x="384" y="34" class="s" text-anchor="middle">1B</text>
<text x="444" y="34" class="s" text-anchor="middle">1B</text>
<text x="517" y="34" class="s" text-anchor="middle">2B LE</text>
</g>
<text x="164" y="92" class="s" text-anchor="middle">= 命令头+param 的总字节数</text>
<text x="240" y="92" class="s" text-anchor="middle">CRC16-CCITT-FALSE</text>
<text x="240" y="104" class="s" text-anchor="middle">覆盖偏移 8 起 uiLen 字节</text>
<text x="316" y="92" class="s" text-anchor="middle">多包时递增, 单包=0</text>
<text x="517" y="92" class="s" text-anchor="middle">param 字节数</text>
<line x1="20" y1="120" x2="354" y2="120" stroke="var(--muted)" stroke-width="1"/><text x="187" y="134" class="s" text-anchor="middle">8B 帧头（B2A_HEAD）</text>
<line x1="354" y1="120" x2="560" y2="120" stroke="var(--accent)" stroke-width="1"/><text x="457" y="134" class="s" text-anchor="middle">4B 命令头（B2A_DATA_CMD）</text>
</svg>
</div>

字段定义见 `b2a_protocol.h` 注释「Frame common header (8B) + B2A_DATA_CMD (4B)」。**`uiLen` 从 cmd 起算**（= 4 + paramLen）；**`uiCRC` 覆盖偏移 8 起的 `uiLen` 字节**（即命令头+param，不含帧头），CRC16-CCITT-FALSE(poly 0x1021 / init 0xFFFF / 不反转 / xorout 0)——App 侧实现见 [`S7Frame.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7Frame.kt) `S7Crc.crc16CcittFalse`。

### 1.3 帧头 status（ucStatus / EHST_*）位含义

`status` 是一个位域（`ble2appEx.h` ENUM_HEAD_STATUS_TYPE）。**应答成功/失败靠外层 status 的 FAIL 位、多包重组靠 MULTI 位**（很多子命令的成功语义不在 param 里）。

<div class="fig">
<svg viewBox="0 0 840 150" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>帧头 status 位域（EHST_*）</title>
<desc>bit0=FAIL, bit1=ACK, bit2=IS_MULTI_PKT, bit3=MULTI_PKT_END, bit4-5=多包ID, bit7=OTA_PART。</desc>
<style>
.t{fill:var(--fg);font-size:11px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:9px;font-family:-apple-system,"Microsoft YaHei",sans-serif;text-anchor:middle;}
.m{fill:var(--fg);font-size:10px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
</style>
<g>
<rect x="120" y="40" width="80" height="30" class="bx"/><text x="160" y="59" class="m">bit7</text>
<rect x="200" y="40" width="80" height="30" class="bx" fill="var(--bg)"/><text x="240" y="59" class="s">bit6</text>
<rect x="280" y="40" width="160" height="30" class="bx"/><text x="360" y="59" class="m a">bit5:4</text>
<rect x="440" y="40" width="90" height="30" class="bx"/><text x="485" y="59" class="m">bit3</text>
<rect x="530" y="40" width="90" height="30" class="bx"/><text x="575" y="59" class="m">bit2</text>
<rect x="620" y="40" width="70" height="30" class="bx"/><text x="655" y="59" class="m">bit1</text>
<rect x="690" y="40" width="70" height="30" class="bx"/><text x="725" y="59" class="m a">bit0</text>
<text x="160" y="90" class="s">OTA_PART</text><text x="160" y="101" class="s">0x80</text>
<text x="240" y="90" class="s">—</text>
<text x="360" y="90" class="s">多包 ID (0..3)</text><text x="360" y="101" class="s">(status&gt;&gt;4)&amp;3</text>
<text x="485" y="90" class="s">MULTI_PKT_END</text><text x="485" y="101" class="s">0x08 末片</text>
<text x="575" y="90" class="s">IS_MULTI_PKT</text><text x="575" y="101" class="s">0x04 多包</text>
<text x="655" y="90" class="s">ACK</text><text x="655" y="101" class="s">0x02 要应答</text>
<text x="725" y="90" class="s">FAIL</text><text x="725" y="101" class="s">0x01 失败</text>
</g>
<text x="120" y="130" class="s" text-anchor="start">应答帧 status=0x00(SUCC) 常见；数据切片首片=IS_MULTI_PKT(0x04)、末片再或 MULTI_PKT_END(0x08)；OTA 数据附带 OTA_PART(0x80)。</text>
</svg>
</div>

### 1.4 分片规则（App→表 数据切片）

一个"切片(slice)"= 一条逻辑 `TRANS` 消息，切成 **1..17 个 GATT 写**（固件 `MAC_SLICE_CNT=17` 硬限）。**每帧 param 上限 = MTU − 15**（= 3B ATT 头 + 8B 帧头 + 4B 命令头），首帧上链恰 ≤ MTU−3。

```
MTU 247  →  每帧 param 232 字节  →  切片上限 sliceMaxSize = 232 × 17 = 3944 字节
                                     （真机 FiTransReqFoMMI ParamPktLen:232, SliceMaxSize:3944）
```

⚠️ 这是曾经的 **BUG-1**：早前按"MTU−12=235"算，首帧上链 247B > MTU−3(244) → Android 写被拒、首切片即挂。已修为 MTU−15，见 [`S7FileTrans.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7FileTrans.kt) `maxParamPerFrame`。

<div class="fig">
<svg viewBox="0 0 840 200" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>一个切片如何切成多包 GATT 写</title>
<desc>切片(≤3944B)切成 ≤17 帧：首帧带命令头+第一段 param，续帧只带 param 段，末帧置 MULTI_PKT_END。</desc>
<style>
.t{fill:var(--fg);font-size:11px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:9.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;text-anchor:middle;}
.m{fill:var(--fg);font-size:10px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.hd{fill:var(--code);stroke:var(--accent);stroke-width:1.3;}
</style>
<text x="20" y="24" class="t">切片 = 一条 TRANS 消息 (cmd=0F, key=02, param = 3944B 文件数据)</text>
<!-- frame 0 -->
<rect x="20" y="44" width="70" height="28" class="bx"/><text x="55" y="62" class="m">帧头8B</text>
<rect x="90" y="44" width="120" height="28" class="hd"/><text x="150" y="62" class="m a">0F 02 paramLen</text>
<rect x="210" y="44" width="150" height="28" class="bx"/><text x="285" y="62" class="m">param[0..231]</text>
<text x="190" y="88" class="s">首帧 index=0, status=IS_MULTI_PKT(+多包ID)</text>
<!-- frame 1 -->
<rect x="20" y="104" width="70" height="28" class="bx"/><text x="55" y="122" class="m">帧头8B</text>
<rect x="90" y="104" width="270" height="28" class="bx"/><text x="225" y="122" class="m">param[232..463]（续段, 无命令头）</text>
<text x="150" y="148" class="s">续帧 index=1, status=IS_MULTI_PKT</text>
<text x="400" y="122" class="s" text-anchor="start">… index 递增 …</text>
<!-- frame N -->
<rect x="20" y="164" width="70" height="28" class="bx"/><text x="55" y="182" class="m">帧头8B</text>
<rect x="90" y="164" width="220" height="28" class="bx"/><text x="200" y="182" class="m">param[末段]</text>
<text x="200" y="200" class="s">末帧 status 再或 MULTI_PKT_END(0x08) → 设备重组完成、回 9B DATA ack</text>
</svg>
</div>

**切片内背压（协议层零 ack，GATT 层必须逐帧流控）**：协议上一个切片的 ≤17 帧**只在整切片发完后收一个 9B ack**（不是每包都有 ack）。但 GATT 层**不能真背靠背猛发**——Write Without Response 无流控会溢出 Android GATT 写缓冲、`writeCharacteristic` 返 BUSY 被静默丢（**真机 ota3.log 实证：单帧过、17 帧全丢 → 设备 15.36s 看门狗 abort**）。故 `AndroidBleClient.write` **逐帧串行 + 等 `onCharacteristicWrite` 再发下一帧**（No-Response 写 Android 亦回该回调，作节流；此即 BLE 吞吐节拍）。App 分片/重传逻辑见 [`S7OtaSession.sendSliceWithRetry`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7OtaSession.kt)。

---

## 2. 五条指令逐字节（真机 hex + 解码走查）

子命令（`ble2appEx.h:451-461`）：`START=0x01 / TRANS=0x02 / STOP=0x03 / REQ=0x04 / OFFSET=0x06`。下面每条给 **① 请求(App→表) ② 应答(表→App) ③ 真机 hex ④ 逐字段解码**。

### 2.1 REQ `0x04` — 会话请求（报总量 / 等授权）

**请求（App→表，param 8B）** `B2A_FILE_TRANS_REQ_REQ_T`（`ble2appWrap.c:3665-3676`）：

| 偏移 | 字段 | 宽 | 含义 |
|---|---|---|---|
| 0 | ucModuleId | 1B | 模块 ID，OTA=1（`BMID_*`；见 §6） |
| 1 | ucIsOffset | 1B | >0=按已存 offset 续传；0=从头 |
| 2 | ucFileCount | 1B | 本会话文件数（本场 14） |
| 3 | ucReserved | 1B | 保留=0 |
| 4-7 | ulTotalSize | 4B LE | 所有文件总字节；不足回 `BFRS_DISK_FULL` |

**应答（表→App，param 12B）** `B2A_FILE_TRANS_REQ_RESP_T`（`ble2appWrap.c:4029-4067` MMI 授权路径）。**注意：OK 路要先等设备端 MMI 授权（异步），才回这 12B**——不能套固定超时判失败（本机 golden 是即时授权 `lAck:1`，他机可能弹窗）。

<div class="fig">
<svg viewBox="0 0 840 150" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>REQ 12B 应答字节布局（真机 hex）</title>
<desc>status/moduleId/fileCount/currFileIdx 各 1B + sliceMaxSize 4B LE + offset 4B LE。</desc>
<style>
.s{fill:var(--muted);font-size:9px;font-family:-apple-system,"Microsoft YaHei",sans-serif;text-anchor:middle;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
</style>
<g>
<rect x="30" y="46" width="46" height="30" class="bx"/><text x="53" y="65" class="m">00</text>
<rect x="76" y="46" width="46" height="30" class="bx"/><text x="99" y="65" class="m">01</text>
<rect x="122" y="46" width="46" height="30" class="bx"/><text x="145" y="65" class="m">0e</text>
<rect x="168" y="46" width="46" height="30" class="bx"/><text x="191" y="65" class="m">00</text>
<rect x="214" y="46" width="184" height="30" class="bx"/><text x="306" y="65" class="m a">68 0f 00 00</text>
<rect x="398" y="46" width="184" height="30" class="bx"/><text x="490" y="65" class="m">00 00 00 00</text>
</g>
<text x="53" y="40" class="s">[0]</text><text x="99" y="40" class="s">[1]</text><text x="145" y="40" class="s">[2]</text><text x="191" y="40" class="s">[3]</text><text x="306" y="40" class="s">[4-7]</text><text x="490" y="40" class="s">[8-11]</text>
<text x="53" y="94" class="s">status</text><text x="53" y="105" class="s">0=OK</text>
<text x="99" y="94" class="s">moduleId</text><text x="99" y="105" class="s">1=OTA</text>
<text x="145" y="94" class="s">fileCount</text><text x="145" y="105" class="s">14</text>
<text x="191" y="94" class="s">currIdx</text><text x="191" y="105" class="s">0</text>
<text x="306" y="94" class="s a">sliceMaxSize LE</text><text x="306" y="105" class="s">0x0f68 = 3944</text>
<text x="490" y="94" class="s">offset LE</text><text x="490" y="105" class="s">0（不续传）</text>
</svg>
</div>

**真机帧（TX，24B）** `ota2.log 18:06:19`：
```
bb 00 10 00 5a 66 00 00 · 0f 04 0c 00 · 00 01 0e 00 68 0f 00 00 00 00 00 00
└─────帧头8B────────┘   cmd key len=12   └────────── param 12B ──────────┘
   SOF status uiLen=0x10 uiCRC uiIndex   0F=FILE_TRANS 04=REQ paramLen=0x0c
```
解码：status=**00**(OK)、moduleId=**01**、fileCount=**0e**(=14)、currIdx=**00**、sliceMaxSize=**0x00000f68**=**3944**、offset=**0**。

> **坐实点**：应答确为 **12B**（固件注释「Both paths produce the same 12-byte layout」）。早前 `ota.log` 里那个 8B `01 00 0e 00 8f 6e 6c 01` 是内部 SDM 记录(ftranreq.dat)、**不是上链 TX 帧**。App 侧 [`parseReqReply`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7FileTrans.kt) 的 12B 解析正确；`sliceMaxSize` 会话取 `min(设备回值 3944, 本地 (MTU−15)×17)`。

### 2.2 START `0x01` — 单文件头

**请求（App→表，param 16+N）** `B2A_FILE_TRANS_START_REQ_T`（`ble2appWrap.c:4149-4174`）：

<div class="fig">
<svg viewBox="0 0 840 155" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>START 请求字节布局（真机 hex, fCheck.dat）</title>
<desc>fileSize 4B + offset 4B + sliceSize 4B + type 1B + zip 1B + nameLen 2B + 文件名 N。</desc>
<style>
.s{fill:var(--muted);font-size:9px;font-family:-apple-system,"Microsoft YaHei",sans-serif;text-anchor:middle;}
.m{fill:var(--fg);font-size:10.5px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
</style>
<g>
<rect x="20" y="46" width="130" height="30" class="bx"/><text x="85" y="65" class="m">6c 00 00 00</text>
<rect x="150" y="46" width="130" height="30" class="bx"/><text x="215" y="65" class="m">00 00 00 00</text>
<rect x="280" y="46" width="130" height="30" class="bx"/><text x="345" y="65" class="m a">68 0f 00 00</text>
<rect x="410" y="46" width="44" height="30" class="bx"/><text x="432" y="65" class="m">02</text>
<rect x="454" y="46" width="44" height="30" class="bx"/><text x="476" y="65" class="m">00</text>
<rect x="498" y="46" width="70" height="30" class="bx"/><text x="533" y="65" class="m">0a 00</text>
<rect x="568" y="46" width="242" height="30" class="bx"/><text x="689" y="65" class="m">66 43 68 65 63 6b 2e 64 61 74</text>
</g>
<text x="85" y="40" class="s">[0-3]</text><text x="215" y="40" class="s">[4-7]</text><text x="345" y="40" class="s">[8-11]</text><text x="432" y="40" class="s">[12]</text><text x="476" y="40" class="s">[13]</text><text x="533" y="40" class="s">[14-15]</text><text x="689" y="40" class="s">[16..]</text>
<text x="85" y="94" class="s">fileSize=108</text>
<text x="215" y="94" class="s">offset=0</text>
<text x="345" y="94" class="s a">sliceSize=3944</text>
<text x="432" y="94" class="s">type</text><text x="432" y="105" class="s">2=FW</text>
<text x="476" y="94" class="s">zip=0</text>
<text x="533" y="94" class="s">nameLen=10</text>
<text x="689" y="94" class="s">"fCheck.dat"（ASCII, 不含结尾 0）</text>
</svg>
</div>

- **文件去向 100% 由文件名决定**：设备 `sprintf(MAC_EXT_ROOT + name)` 裸拼路径写盘。名字 **≤12 字符**（设备 `szFile[13]`，>13 截断到 12）。
- **`ucType`（BFTT_*，见 §6）只驱动进度 UI、不参与路由**——真机官方 App 对**全部 14 个文件都送 type=2(FW)**（连字库/Res 也是 2）。我方可照送 2，或按文件真实类型送，设备都不 care。
- **`ucZipFlag` 固件未消费**，恒 0（一律推明文整文件）。
- **`ulOffset`=0 → 设备 `wb` 覆盖写**；>0 → `ab+` 追加（续传，见 §2.5）。

**应答（表→App，param 1B）** `B2A_FILE_TRANS_START_RESP_T`：`ucResult` = `EBEC_SUCC(0)` 就绪 / `EBEC_MEMORY(4)` 缓冲分配失败或 `ulSliceSize > ulSliceMaxSize`。
**真机帧（TX，13B）** `bb 00 05 00 71 35 00 00 · 0f 01 01 00 · 00` → cmd=0F key=01 paramLen=1 param=**00**(SUCC)。

> ⚠️ START 声明的 `ulSliceSize` **必须 ≤ 设备 sliceMaxSize(3944)**，否则设备回 `EBEC_MEMORY(4)`。我方 `sliceSize = min(sliceMax, 文件大小)` 已满足。

### 2.3 TRANS `0x02` — 数据块（切片）

**请求（App→表）**：**裸文件字节流**，无字段结构；多包切分靠外层 status（§1.4）。一个切片 ≤ `sliceMaxSize`。

**应答（表→App，param 9B）** `B2A_FILE_TRANS_DATA_RESP_T`（成功，`ble2appWrap.c:4967-5009`）：

<div class="fig">
<svg viewBox="0 0 840 150" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>DATA 9B 应答字节布局（真机 hex）</title>
<desc>recvLen 4B LE + checkSum 4B LE + status 1B。recvLen=本切片长（非累计）。</desc>
<style>
.s{fill:var(--muted);font-size:9px;font-family:-apple-system,"Microsoft YaHei",sans-serif;text-anchor:middle;}
.m{fill:var(--fg);font-size:11px;font-family:Consolas,monospace;text-anchor:middle;}
.a{fill:var(--accent);font-weight:700;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
</style>
<g>
<rect x="60" y="46" width="220" height="30" class="bx"/><text x="170" y="65" class="m">68 0f 00 00</text>
<rect x="280" y="46" width="220" height="30" class="bx"/><text x="390" y="65" class="m a">b4 52 04 00</text>
<rect x="500" y="46" width="80" height="30" class="bx"/><text x="540" y="65" class="m">00</text>
</g>
<text x="170" y="40" class="s">[0-3]</text><text x="390" y="40" class="s">[4-7]</text><text x="540" y="40" class="s">[8]</text>
<text x="170" y="94" class="s">recvLen LE = 0x0f68 = 3944</text><text x="170" y="105" class="s">= 本切片实收字节（非累计）</text>
<text x="390" y="94" class="s a">checkSum LE = 0x000452b4</text><text x="390" y="105" class="s">= 本切片逐字节累加和</text>
<text x="540" y="94" class="s">status</text><text x="540" y="105" class="s">0=SUCC</text>
</svg>
</div>

**真机帧（TX，21B，fCN26 首切片）** `bb 00 0d 00 c0 7e 00 00 · 0f 02 09 00 · 68 0f 00 00 b4 52 04 00 00`。
另一例（fCheck.dat 整文件 108B 一切片）：`… 0f 02 09 00 6c 00 00 00 d9 19 00 00 00` → recvLen=**108**、checkSum=**0x000019d9**。

- **`recvLen` = 本切片实收字节数（非累计）**——108B 的文件 recvLen=108，满切片 recvLen=3944，末片 recvLen=余数。App 拿它 + 期望切片长比对。
- **`checkSum` = 本切片逐字节算术累加和（U32）**，App 自算比对（见 §3），不符则重发本切片。

**错误应答（param 1B）** `B2A_FILE_TRANS_DATA_ERR_RESP_T`（外层 status 置 FAIL）：`EBEC_MEMORY(4)` 缓冲空/超限 / `EBEC_FAIL(1)` 多包丢包(短包) / `EBEC_FSUM_FAIL(13)` 回读校验失败。**App 收到非 9B 成功应答 → 重发本切片**。

### 2.4 STOP `0x03` — 结束单文件 / 会话

**请求（App→表）**：**无 payload**。固件 `B2A_FileTransStopAck`（`ble2appWrap.c:5386-5391`）**只 hexdump、不解析任何字节**——官方 App 会送一个 16B 摘要（`15 c1 b0 f2 …`，疑 MD5），但**设备完全忽略**。我方送空即可。

**设备做什么**（`ble2appWrap.c:5400-5629`）：
1. **per-file 尺寸兜底**（`:5467`）：比对刚落盘文件的**磁盘尺寸 == START 声明的 `ulCurrFileSize`**；不符 → `EBEC_FSUM_FAIL(13)` + 中止（防漏包导致的短文件被提交）。
2. `idx++`；若非末文件 → 回 `EBEC_SUCC`，等下一个 START。
3. 若是**末文件**（idx==fileCount）→ 跑 `B2A_ResCheck()`（用随包下发的 `ResCheck.dat` 校验资源）→ 通过回 `EBEC_SUCC`、失败回 `EBEC_FSUM_FAIL`。

**应答（表→App，param 1B）**：`EBEC_SUCC(0)` / `EBEC_FSUM_FAIL(13)`。
**真机帧（TX，13B）** `bb 00 05 00 19 d8 00 00 · 0f 03 01 00 · 00` → SUCC。

> ⚠️ **STOP 非幂等**：设备靠内部 `idx++` 推进，**丢 ack 裸重发会二次推进 → 缺文件**。重传要谨慎（见 §7 纪律）。

### 2.5 OFFSET `0x06` — 查断点续传偏移

**请求（App→表）**：无 payload（固件不解析）。
**应答（表→App，param 4B）** `B2A_FILE_TRANS_OFFSET_RESP_T`：`ulOffset`（4B LE）= 当前文件已接收字节偏移（低内存时退化为 1B `EBEC_MEMORY`）。
**续传纪律**：断连重连后，若要续传，**必须先用 REQ 的 `ucIsOffset>0` 或 OFFSET 拿到设备 offset，再从该 offset 发 START（`ulOffset` 填它）**。从 0 重发而设备 offset>0 → 设备 `ab+` 追加成超长文件 → 被尺寸兜底判失败强制整包重传。attended 场景下续传是 best-effort。

---

## 3. 累加和校验（App 必须自算的唯一校验）

**算法**：对切片的每个字节做无符号累加，取低 32 位。

```
sum = 0
for b in slice:  sum = (sum + (b & 0xFF)) & 0xFFFFFFFF
```

例：`{0x01,0x02,0x03}` → sum=6；`{0xFF,0xFF,0xFF,0xFF}` → 1020（不溢出 32 位）。App 侧 = [`S7FileTrans.additiveChecksum`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7FileTrans.kt)。

**三套校验各司其职，别混**：
1. **传输层 9B 累加和**（本文）——App 自算 + 比对设备回值，唯一 App 要算的。
2. **`ResCheck.dat` / `fCheck.dat`**——构建工具预生成、随包下发的**普通文件**，App 只原样透传落盘，设备升级后自己拿它做 per-file 自检（golden 日志 `B2A_ResCheck ResFat 0xf7fc / ResData 0xab9e`）。
3. **STOP 尺寸兜底 + 末文件 ResCheck**——纯设备侧，App 观测不到。

---

## 4. 完整会话流程 + 真机时间线

真机 `ota2.log`（14 文件 ota_all，总 23,883,407 字节 ≈ 22.78MB）：

| 阶段 | 时间 | 事件 |
|---|---|---|
| 入口 | 18:06:19.784 | `APP_OtaReqEntry(B)` → `OTA_StartTask` → `OTA_DoRecvBefore ucResArea:B`（当前 B 区，写入 A 区） |
| REQ 授权 | 18:06:19.905 | `B2A_FileTransReqAckFromMMI lAck:1`（即时授权）→ 回 12B `sliceMax=3944` |
| 文件 1 | 18:06:20 | `fCheck.dat` 108B（1 切片） |
| 文件 2-10 | 18:06:20~18:08:12 | 字库 `fCN26/34/40`、`fNum48/64/72/80/96/120` |
| 文件 11 | 18:08:12~ | `fw.dat` 1,670,216B（主 MCU 镜像） |
| 文件 12-14 | ~18:15:59 | `ResCheck.dat` 4560B、`ResData.dat` 17,824,356B（占大头 ~7min）、`ResFat.dat` 3024B |
| 末 STOP | 18:15:59.788 | `[14/14]` → `B2A_ResCheck`(ResFat 0xf7fc / ResData 0xab9e 自校验 OK) |
| 收尾 | 18:16:03.744 | `OTA_StopTask` → `OTA_DoRecvAfter`（OldPath 0:/B NewPath 0:/A；Step1 Res 三件套 OK；Step2 fCheck+9 字库 OK） |
| 生效 | 18:16:04~ | `OTA_SetFlag(1)` → `OTA_Check(ucOtaFlag:1) goto SecBL 0x1e2000` → `E2P_LoadRes2Psram` → `SYS_Reset` |

- **文件顺序（官方 App）**：字库(fCheck→fCN*→fNum*) → `fw.dat` → Res 三件套(ResCheck→ResData→ResFat)。我方可自定序，但**末文件 STOP 才触发完成**。
- **A/B 双区交替**：本场 B→A（`ota.log` 那场是 A→B）。设备总写"另一区"，DoRecvAfter 校验通过才切过去。
- **实测速率 ~40KB/s**；`ResData.dat` 17.8MB 单文件 ~7min。全程 15.36s UI 看门狗未触发。

---

## 5. 完成与生效（App 观测边界）

设备侧完成链（App **全部看不到**，`ble2appWrap.c` / `OTA_Main.c`）：

```
末 STOP SUCC
 └─ OTA_DoRecvAfter()==EECT_OK   (新区 Res 三件套存在 + 字库齐, 缺字库从旧区补拷)
     └─ OTA_SetFlag(MAC_OTA_FLAG)  (MRAM 私有区置 OTA 标志)
         └─ 重启/跳转 → OTA_Check() 见标志 → goto SecBL (0x1e2000) 执行刷写
             └─ SYS_Reset() 自复位, 新固件生效
```

**App 侧判据**（[`OtaResult`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaPackage.kt)）：
- 收到**末文件 STOP 的 SUCC** = `DoneDownload`（下载收讫，非"已生效"）。
- 随后设备断连自复位 → App **重连 → 读取并显示当前版本** = `Reconnected(currentVersion?)`（读不到=null 显示"未知"）。**不做版本校验**（OTA 包无版本信息，无法机器比对包版本 vs 设备版本）——是否刷成功由人工看版本判断。
- 中途任何 START/STOP 收 `EBEC_FSUM_FAIL`、或切片重传耗尽 = 下载失败；重连失败 = `Failed(ReconnectFailed)`（attended 下整包重来 / 手动重连查看）。

**砖化边界**：BLE OTA 永不碰 SecBL/boot（包不含 + 固件无 handler），砖必落应用/资源区，`flash_fw.ps1`（J-Link SWD）无条件可救。**固件无低电门控（`EBEC_LOW_BAT` 定义了从不返回）→ 防掉电 100% 靠 App**（发起前强制充电 + 电量阈值）。

---

## 6. 错误码 / 枚举总表（固件 `ble2appEx.h`）

**REQ 应答状态 BFRS_*（`:500-508`）**：`OK=0 / DISK_FULL=1 / BUSY=2 / MEMORY=3`。

**通用 ack 错误码 EBEC_*（`:49-70`，STOP/START/DATA 的 1B 应答用）**：

| 值 | 名 | 值 | 名 |
|---|---|---|---|
| 0 | SUCC | 7 | BUSY |
| 1 | FAIL（多包丢包/短包） | 8 | LOW_BAT（定义了但从不返回） |
| 2 | TIMEOUT | 9 | NO_DATA |
| 3 | FORMAT | 10 | MD5 |
| 4 | MEMORY（缓冲空/超限/sliceSize 超上限） | 11 | CRC |
| 5 | NOT_SUPPORT | 12 | FATAL |
| 6 | PARAM | **13** | **FSUM_FAIL（尺寸/资源校验失败）** |

**模块 ID BMID_*（`:466-476`）**：`OTA=1 / GPS_FW=2 / WATCH_BG=3 / MODEM_FW=4 / SECBL_FW=5 / BP_FW=6`。
> **安全**：BLE 收流只认 `OTA(1)/WATCH_BG(3)/BP_FW(6)` 有 handler；`SECBL_FW(5)` 只是枚举、无 handler → BLE 永远刷不到 SecBL。

**文件类型 BFTT_*（`:482-494`，START[12]，仅驱动 UI）**：`BOOTLOADER=1 / FW=2 / RES=3 / MODEM=4 / GPS_FW=5 / GPS_HIS=6 / FONT=7 / WATCH_BG=8 / WATCH_CFG=9 / BP_FW=10`。

---

## 7. App 实现映射（现有 Phase 1.1 代码 → 待办）

| 协议元素 | App 侧现状（[`shared/.../s7/`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/)） |
|---|---|
| 帧编解码 + CRC16 + 多包分片/重组 | ✅ `S7Frame.kt`（`encodeMultiPacket` / `S7FrameDecoder`） |
| 五子命令 encode/parse + 累加和 | ✅ `S7FileTrans.kt`（REQ 8B/12B、START 16+N、DATA 9B、OFFSET 4B、`additiveChecksum`） |
| 独占长事务（REQ→逐文件→末 STOP=DoneDownload、切片背压+重传） | ✅ `S7OtaSession.kt` |
| 每帧 param = MTU−15、sliceMax 夹本地 | ✅ 已修（BUG-1，本文 §1.4） |
| REQ 应答 12B 解析 + 防御式 | ✅ `parseReqReply` 12B（真机坐实）+ 短应答不 abort |
| Mock 设备状态机 | ✅ `S7MockWatch.kt` |
| 数据模型 / 结果 / 失败原因 / 阶段 | ✅ `OtaPackage.kt`（`OtaResult{DoneDownload/Verified/Failed}` / `OtaFailure` / `OtaPhase`） |
| **下载后回连+读版本 编排**（下载→等复位→重连→读当前版本） | ✅ `OtaProvisioner.kt`（逻辑层，`provisionAndReconnect` → `Reconnected(currentVersion?)`；`readVersion` 注入，不做版本校验） |
| **烧录包校验**（合法 zip + 必推核心存在 + 名≤12 + 无空 + 缺字库告警） | ✅ `OtaPackageValidator.kt`（shared，可测）+ `sortByPushOrder`（golden 序） |
| **zip 选包/解压/建包**（SAF Uri → OtaPackage） | ✅ `OtaZipLoader.kt`（app，`ZipInputStream`） |
| **DEBUG「OTA 测试」屏 + 循环 N 次** | ✅ `OtaTestViewModel`/`OtaTestScreen`（选包/校验展示/循环次数/进度/各轮结果/日志，遇失败即停） |

**开发待办（Phase 2 端到端）——选包/校验/UI/循环已落地（`OtaTest*`）**：
1. **首台真机验证**（D8F7）：走完整刷入→重启→重连→读版本；跑 O-1 实验（能否只推 `fw.dat` 复用旧 Res，把 30min 降到 2min）；验 `reconnect()` 真机 link 语义（`AndroidBleClient.connect` 复位后建新 GATT，已支持）。
2. **续传/错误路径真机验证**：拔电/切后台触发 OFFSET 续传（golden 是 happy path，续传/NAK 分支未经真机验）。
3. **采集包联调**：把测试用 stock `ota_all.zip` 换成采集 `ota_all.zip`，验采集固件刷入后采集能力（读版本/`SYS_DATA_COLLECT_SVC`）。

---

## 附：溯源

- 真机报文：`E:\1\apollo4_watch_s7\Docs\log\ota2.log`（带 TX，2026-07-08 18:06；`ota.log` 为无 TX 版本）。
- 字段表：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\manuals\b2a_protocol.h`（FILE_TRANS 段 :1955-2119）。
- 固件实现：`product/apollo_eiot/Ble2App/ble2appWrap.c`（REQ :3665/4029、START :4149、DATA :4967、STOP :5400、OFFSET :5654）、`ble2appEx.h`（枚举 :28-508）、`OTA/OTA_Main.c`（DoRecvAfter/SetFlag/Check/SecBL）。
- App 侧：[`shared/src/commonMain/kotlin/io/bluetrace/shared/s7/`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/)；设计与活文档 [`S7采集OTA_设计.md`](S7采集OTA_设计.md) / [`implementation-notes.md`](implementation-notes.md)。
