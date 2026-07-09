# S7 多设备 OTA · 界面与编排设计

> **状态**：**已落地并真机（M2101K9C）验证通过**（2026-07-09）。落码明细见 CHANGELOG [OTA·多设备]；本文已就地对齐实现。
> **定位**：在**单设备 OTA 端到端原语**之上，只加一层「串行批量编排 + 多设备 UI」——协议层 / 连接层 / OTA 会话层**一行不改**。
> **真源**：协议真值见 [S7采集OTA_指令交互与流程.md](S7采集OTA_指令交互与流程.md)（真机 golden 坐实）；单设备现状见 [`OtaTestScreen.kt`](../../app/src/main/java/io/bluetrace/ui/screen/settings/OtaTestScreen.kt) + [`OtaTestViewModel.kt`](../../app/src/main/java/io/bluetrace/viewmodel/OtaTestViewModel.kt)。
> **一句话目标**：一个 OTA 包，一次刷多台 S7 表——从工作队列里**逐台**连接 → 读版本+电量 → 刷写 → 复读电量 → 断开，失败/低电跳过并可手动重试。

---

## 0. 目标与边界

- **要做**：在现有「OTA 固件」DEBUG 屏顶栏加一个「多设备」开关；打开后从**工作队列**批量升级多台 S7 表，**一个包全队列共用**，**串行**逐台跑完整流程。
- **不做**：并发多台同刷（见 [§3](#3-为什么串行不并行)）、跨包混刷、后台无人值守、机器版本比对判成败（OTA 包不带版本，成败靠人看版本，沿用单设备 [`OtaProvisioner`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt) 的口径）。
- **复用姿态**：多设备 = 单设备流程的「外层循环 + 状态聚合」。真正新增只有一个 `MultiOtaViewModel` 和多设备 UI；下行分片、`FILE_TRANS` 编解码、独占事务、重连读版本、读电量**全部现成**（[§2](#2-复用账90-是现成的)）。

---

## 1. 关键决策（2026-07-09 讨论拍板）

| 决策点 | 结论 | 依据 |
|---|---|---|
| 一次一台 vs 一次多台 | **串行（一次一台）** | 设备端人工授权 + 切片看门狗 + 重连风暴，见 [§3](#3-为什么串行不并行) |
| 界面形态 | **顶栏开关同屏切换**单/多设备，开关**默认关** | 用户指定「开关在顶端栏最右侧」；[`BtTopBar`](../../app/src/main/java/io/bluetrace/ui/components/Common.kt) 已有 `actions` 槽 |
| 扫描添加语义 | **只入队、不连接**；**仅 B2A/S7 可加**，不支持的设备不可加 | 实际连接在批量执行时逐台发生 |
| OTA 包 | 多设备模式**锁 1 个**，全队列共用 | 用户指定「多设备只涉及一个 OTA 包」 |
| 电量门槛 | 刷前 **< 30% 跳过**（标「电量不足」，可充电后重试） | 固件无低电门控，掉电变砖风险 100% 由 App 兜底 |
| 失败处理 | **一台失败即跳过**，继续下一台；批尾汇总；失败/跳过行显示**重试**图标，可单台重试 | 用户拍板 |
| 刷后是否断开 | **断开**（`ble.disconnect` + `registry.remove`），释放射频给下一台 | 用户拍板 |
| 开始后能否改队列 | **禁新增**；**未开始的「待升级」行可删**、**已完成/失败/跳过行可删**；**正在刷写的当前台锁定**（要停用顶栏「停止批量」） | 用户拍板（新增禁），删除语义本设计建议 |
| 单设备现状 | 开关**关** = 现在的单设备 DEBUG 屏（1 包单次 / 2 包 A→B 循环）**原样保留** | 用户拍板 |

---

## 2. 复用账：90% 是现成的

多设备把「刷哪台」从单个黏性目标换成一条队列，其余每一步都落到现成件上：

| 流程步骤 | 落到哪个现成件 | 位置 | 新写量 |
|---|---|---|---|
| 连接 + 入册 | `ble.connect(device)` + `registry.add(device)` | [`BleClient.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/BleClient.kt) / [`ConnectionRegistry.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/ConnectionRegistry.kt) | 0 |
| 查版本 | `S7Console.getDeviceInfo().swVer`（`GET` `KEY_DEV_INFO=0x21`） | [`S7Console.kt:232`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7Console.kt#L232) | 0 |
| 查电量 | `S7Console.getBattery().percent`（`GET` `KEY_DEV_VOL=0x24`） | [`S7Console.kt:240`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7Console.kt#L240) | 0 |
| OTA + 等复位 + 重连 + 读版本 | `OtaProvisioner.provisionAndReconnect(pkg, onPhase, onProgress)` | [`OtaProvisioner.kt:52`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt#L52) | 0 |
| 下行分片 / 独占事务 | `S7OtaSession.provision()`（per-device，per-instance mutex） | [`S7OtaSession.kt:48`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7OtaSession.kt#L48) | 0 |
| 断开 | `ble.disconnect(id)` + `registry.remove(id)` | [`BleClient.kt`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/BleClient.kt) | 0 |
| 顶栏开关 | `BtTopBar(actions = { Switch(...) })` | [`Common.kt:40`](../../app/src/main/java/io/bluetrace/ui/components/Common.kt#L40) | 塞 1 个 `Switch` |
| **批量编排 + 队列状态** | **新写** `MultiOtaViewModel`（仿 [`OtaTestViewModel`](../../app/src/main/java/io/bluetrace/viewmodel/OtaTestViewModel.kt)），per-device 状态用 `List<DeviceOtaItem>` | — | **主要工作量** |
| **多设备 UI** | **新写** 多设备布局 + 扫描添加选择表 | — | **主要工作量** |

关键点：[`BleClient`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/BleClient.kt) 全接口按 `deviceId` 寻址，[`AndroidBleClient`](../../app/src/main/java/io/bluetrace/data/android/AndroidBleClient.kt) 用 `ConcurrentHashMap<String, Conn>` 每设备一条独立 GATT；[`OtaProvisioner`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt) 是纯 suspend、不持有 scope。所以「对队列逐台调 `provisionAndReconnect`」在结构上零摩擦。

---

## 3. 为什么串行（不并行）

抽象层**支持**并发（每设备独立 GATT / 独立 `S7OtaSession` / registry 上限 3 台 DUT），但 OTA 有三条硬约束把并发否掉：

1. **设备端人工授权**：`REQ` 后要等设备端 MMI 手动授权（`authorizeTimeoutMs = 60s`，见 [`S7OtaSession.kt:32`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/S7OtaSession.kt#L32)）。操作员一次只能盯一块表按授权。
2. **切片看门狗贴身**：单切片 9B ack 超时 `sliceAckTimeoutMs = 10s` 已贴着设备 ~15.36s UI 看门狗；写通道已是**逐帧串行等 `onCharacteristicWrite`**（`ota3.log` 实证 17 帧背靠背会溢出 GATT 缓冲丢包，见 [`AndroidBleClient.kt:306`](../../app/src/main/java/io/bluetrace/data/android/AndroidBleClient.kt#L306)）。并发会把射频/CPU 摊薄到 N 条链路 → 切片超时 → 批量 abort。
3. **重连风暴**：刷完每台自复位重启，要重连（`reconnectTimeoutMs = 30s × 5`，见 [`OtaProvisioner.kt:35`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt#L35)）。N 台并发复位 = N 路重连风暴 + 扫描竞争。

> **结论**：执行层**串行**，UI 呈现为**批量**——一条队列自动从上往下跑，每台实时状态，但同一时刻只刷一台。

---

## 4. 每台设备状态机（流程）

一台设备从「待升级」到「完成/失败/跳过」的完整生命周期。琥珀色的「刷写 OTA」是重步（内含 4 个子阶段，由 [`OtaProvisioner`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt) 的 `OtaPhase` 驱动）；虚线是失败/低电分支，都记录后跳下一台并可手动重试。

<div class="fig">
<svg viewBox="0 0 840 240" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>多设备 OTA 每台设备的串行处理状态机</title>
<desc>横向五步：连接、读版本和电量、刷写 OTA、复读电量、断开；断开后回到队首处理下一台；读版本电量时电量低于 30% 或刷写失败，都进入失败低电框，记录后跳下一台并可手动重试。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.hot{fill:var(--code);stroke:var(--accent);stroke-width:1.5;}
.err{fill:var(--code);stroke:var(--danger);stroke-width:1.2;}
.ar{stroke:var(--muted);stroke-width:1.4;fill:none;}
.dl{stroke:var(--danger);stroke-width:1.3;fill:none;stroke-dasharray:5 4;}
</style>
<defs>
<marker id="ah" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M2 1L8 5L2 9" fill="none" stroke="context-stroke" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></marker>
</defs>

<rect x="24" y="50" width="144" height="54" rx="8" class="bx"/>
<text x="96" y="73" text-anchor="middle" class="t">连接设备</text>
<text x="96" y="90" text-anchor="middle" class="s">ble.connect + 入册</text>

<rect x="188" y="50" width="150" height="54" rx="8" class="bx"/>
<text x="263" y="73" text-anchor="middle" class="t">读版本 + 电量</text>
<text x="263" y="90" text-anchor="middle" class="s">记刷前值 · 电量门槛</text>

<rect x="358" y="50" width="140" height="54" rx="8" class="hot"/>
<text x="428" y="73" text-anchor="middle" class="t">刷写 OTA</text>
<text x="428" y="90" text-anchor="middle" class="s">下载·复位·重连·读版本</text>

<rect x="518" y="50" width="130" height="54" rx="8" class="bx"/>
<text x="583" y="73" text-anchor="middle" class="t">复读电量</text>
<text x="583" y="90" text-anchor="middle" class="s">记刷后值</text>

<rect x="668" y="50" width="130" height="54" rx="8" class="bx"/>
<text x="733" y="73" text-anchor="middle" class="t">断开</text>
<text x="733" y="90" text-anchor="middle" class="s">释放射频</text>

<line x1="168" y1="77" x2="188" y2="77" class="ar" marker-end="url(#ah)"/>
<line x1="338" y1="77" x2="358" y2="77" class="ar" marker-end="url(#ah)"/>
<line x1="498" y1="77" x2="518" y2="77" class="ar" marker-end="url(#ah)"/>
<line x1="648" y1="77" x2="668" y2="77" class="ar" marker-end="url(#ah)"/>

<rect x="330" y="150" width="200" height="48" rx="8" class="err"/>
<text x="430" y="170" text-anchor="middle" class="t">失败 / 低电</text>
<text x="430" y="186" text-anchor="middle" class="s">记录 · 跳下一台 · 可重试</text>

<path d="M263 104 L263 138 L360 138 L360 150" class="dl" marker-end="url(#ah)"/>
<path d="M428 104 L428 150" class="dl" marker-end="url(#ah)"/>

<path d="M733 104 L733 220 L96 220 L96 104" class="ar" marker-end="url(#ah)"/>
<text x="405" y="215" text-anchor="middle" class="s">↻ 断开后处理下一台（失败/跳过同样回此循环）</text>
</svg>
</div>

**映射到代码**：`连接` = `ble.connect` + 校验 `linkState==CONNECTED` 后 `registry.add`；`读版本+电量` = 短命 `S7Console` 上 `getDeviceInfo().swVer` + `getBattery().percent`；`刷写 OTA` = `OtaProvisioner.provisionAndReconnect`（返回 `OtaResult.Reconnected(version)` 或 `Failed(reason)`）；`复读电量` = 重连后再 `getBattery()`；`断开` = `ble.disconnect` + `registry.remove`。

---

## 5. 界面设计

### 5.1 顶栏「多设备」开关（默认关）

顶栏用现成 [`BtTopBar`](../../app/src/main/java/io/bluetrace/ui/components/Common.kt)，它已暴露右侧 `actions: @Composable (() -> Unit)?` 槽（[`Common.kt:40`](../../app/src/main/java/io/bluetrace/ui/components/Common.kt#L40)，渲染在 [`:63`](../../app/src/main/java/io/bluetrace/ui/components/Common.kt#L63)），现在 OTA 屏没传。改为：

```kotlin
BtTopBar(
    title = "OTA 固件",
    subtitle = if (ui.multiMode) "多设备批量 · 队列 ${ui.queue.size} 台" else "刷入烧录包 · DEBUG",
    onBack = { if (!ui.running) onBack() },
    actions = {
        Text("多设备", ...)
        Switch(checked = ui.multiMode, enabled = !ui.running,
               onCheckedChange = { vm.setMultiMode(it) })
    },
)
```

- 默认 `multiMode = false`（单设备）；运行中 `enabled = false`（不可中途切模式）。

### 5.2 界面布局总览（多设备模式）

顺序：**顶栏（开关）→ 烧录包（1 个，共用）→ 设备队列（可增删）→ 批量控制**。

<div class="fig">
<svg viewBox="0 0 840 470" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>多设备 OTA 屏布局线框图</title>
<desc>手机屏从上到下：带多设备开关的顶栏、单个共用 OTA 包卡、设备队列（含扫描添加入口和多行队列项）、批量控制按钮和汇总；右侧为各区块的说明标注。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:11px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:10.5px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.fr{fill:none;stroke:var(--line);stroke-width:1.4;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.acc{fill:var(--accent);}
.led{stroke:var(--line);stroke-width:1;fill:none;stroke-dasharray:4 3;}
</style>

<rect x="40" y="30" width="300" height="410" rx="18" class="fr"/>
<line x1="40" y1="76" x2="340" y2="76" stroke="var(--line)" stroke-width="1"/>
<text x="60" y="58" class="t">OTA 固件</text>
<rect x="278" y="46" width="30" height="16" rx="8" class="acc"/>
<circle cx="301" cy="54" r="6" fill="var(--bg)"/>
<text x="272" y="58" text-anchor="end" class="s">多设备</text>

<rect x="52" y="90" width="276" height="42" rx="8" class="bx"/>
<text x="64" y="108" class="m">S7_fw_v1.2.8.zip</text>
<text x="64" y="123" class="s">12 文件 · 3.4 MB · 全队列共用</text>

<text x="60" y="156" class="t">设备队列 · N 台</text>
<text x="316" y="156" text-anchor="end" class="a">＋ 扫描添加</text>

<rect x="52" y="166" width="276" height="40" rx="8" class="bx"/>
<circle cx="70" cy="186" r="6" fill="var(--muted)"/>
<text x="86" y="183" class="m">SKG WATCH S7-xxxx</text>
<text x="86" y="197" class="s">状态徽章 · 版本/电量/进度</text>

<rect x="52" y="214" width="276" height="40" rx="8" class="bx"/>
<circle cx="70" cy="234" r="6" class="acc"/>
<text x="86" y="231" class="m">SKG WATCH S7-xxxx</text>
<text x="86" y="245" class="s">刷写中 · 进度条</text>

<rect x="52" y="262" width="276" height="40" rx="8" class="bx"/>
<circle cx="70" cy="282" r="6" fill="var(--muted)"/>
<text x="86" y="279" class="m">SKG WATCH S7-xxxx</text>
<text x="86" y="293" class="s">待升级 · 可删</text>

<rect x="52" y="322" width="276" height="40" rx="8" class="acc"/>
<text x="190" y="347" text-anchor="middle" fill="var(--bg)" font-size="12" font-weight="700" font-family="-apple-system,'Microsoft YaHei',sans-serif">开始批量 / 停止批量</text>
<text x="190" y="380" text-anchor="middle" class="s">1 完成 · 1 失败 · 1 跳过 · 待升级 N</text>

<line x1="312" y1="54" x2="556" y2="52" class="led"/>
<text x="560" y="49" class="t">顶栏开关</text>
<text x="560" y="64" class="s">默认关；手动开进多设备</text>

<line x1="328" y1="110" x2="556" y2="112" class="led"/>
<text x="560" y="109" class="t">单个 OTA 包</text>
<text x="560" y="124" class="s">全队列共用；多设备锁 1 包</text>

<line x1="300" y1="152" x2="556" y2="172" class="led"/>
<text x="560" y="169" class="t">扫描添加</text>
<text x="560" y="184" class="s">入队不连接 · 仅 B2A 可加</text>

<line x1="328" y1="234" x2="556" y2="250" class="led"/>
<text x="560" y="247" class="t">工作队列</text>
<text x="560" y="262" class="s">5 态 · 待升级可删 · 失败/低电可重试</text>

<line x1="328" y1="342" x2="556" y2="332" class="led"/>
<text x="560" y="329" class="t">批量控制</text>
<text x="560" y="344" class="s">串行逐台 + 实时汇总</text>
</svg>
</div>

> **开关关（单设备）** = 现在的 [`OtaTestScreen`](../../app/src/main/java/io/bluetrace/ui/screen/settings/OtaTestScreen.kt) 原样：黏性单目标 `InfoCard`（点进扫描/连接页）+ 1~2 包（2 包 A→B 循环）。多设备只是并列多出的一个模式。

### 5.3 队列行状态词汇（5 态）

<div class="fig">
<svg viewBox="0 0 840 130" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>工作队列行的五种状态及右侧动作</title>
<desc>五个状态：待升级可删除、刷写中锁定、完成显示版本电量前后值、失败可重试、低电跳过可重试。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.p{stroke-width:1.3;fill:var(--code);}
</style>
<rect x="20"  y="34" width="150" height="34" rx="8" class="p" stroke="var(--muted)"/>
<text x="95"  y="55" text-anchor="middle" class="t">待升级</text>
<text x="95"  y="88" text-anchor="middle" class="s">未连接 · 可删除</text>

<rect x="188" y="34" width="150" height="34" rx="8" class="p" stroke="var(--accent)"/>
<text x="263" y="55" text-anchor="middle" class="t">刷写中</text>
<text x="263" y="88" text-anchor="middle" class="s">进度条 · 锁定</text>

<rect x="356" y="34" width="150" height="34" rx="8" class="p" stroke="var(--fg)"/>
<text x="431" y="55" text-anchor="middle" class="t">完成</text>
<text x="431" y="88" text-anchor="middle" class="s">版本/电量 前→后</text>

<rect x="524" y="34" width="150" height="34" rx="8" class="p" stroke="var(--danger)"/>
<text x="599" y="55" text-anchor="middle" class="t">失败</text>
<text x="599" y="88" text-anchor="middle" class="s">原因 · 可重试 ↻</text>

<rect x="692" y="34" width="128" height="34" rx="8" class="p" stroke="var(--warn)"/>
<text x="756" y="55" text-anchor="middle" class="t">跳过 · 低电</text>
<text x="756" y="88" text-anchor="middle" class="s">&lt;30% · 可重试 ↻</text>
</svg>
</div>

- **待升级**：扫描入队后的初态（未连接，只有 name/MAC/RSSI）；右侧「删除」。
- **刷写中**（含 连接中/读取中/等待重连）：显示进度/阶段，行**锁定**不可删。
- **完成**：`版本 v_before → v_after · 电量 b_before% → b_after%`。
- **失败**：显示失败原因（连接失败/`REQ` 拒绝/切片失败/重连失败），右侧「重试 ↻」。
- **跳过 · 低电**：刷前电量 `< 30%`，右侧「重试 ↻」（充电后再点）。

### 5.4 扫描添加选择表（入队不连接，仅 B2A）

点「扫描添加」弹出一个选择表：扫到的设备**只加入队列、不立即连接**；识别为 S7（广播含 `FFE0`，`B2aDetect.matchesAdvertisement`）的可勾选，其余灰掉标「不支持 OTA」、不可勾；已在队列的标「已在队列」。

<div class="fig">
<svg viewBox="0 0 840 260" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>扫描添加选择表</title>
<desc>底部弹出表列出扫到的设备：两台 S7 手表可勾选，一台已在队列，一台非 B2A 设备灰掉标不支持；底部为加入队列按钮。仅入队不连接。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:10.5px;font-family:Consolas,monospace;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.acc{fill:var(--accent);}
.ok{stroke:var(--accent);stroke-width:1.4;fill:none;}
.dis{stroke:var(--line);stroke-width:1.2;fill:none;}
</style>
<rect x="120" y="20" width="600" height="200" rx="12" class="bx"/>
<text x="140" y="48" class="t">扫描添加到队列</text>
<text x="700" y="48" text-anchor="end" class="s">仅入队 · 不连接 · 只支持 S7(B2A)</text>

<rect x="140" y="62" width="24" height="24" rx="5" class="acc"/>
<text x="152" y="79" text-anchor="middle" fill="var(--bg)" font-size="13">✓</text>
<text x="176" y="79" class="m">SKG WATCH S7-9B02</text>
<text x="700" y="79" text-anchor="end" class="a">S7 · 可加</text>

<rect x="140" y="98" width="24" height="24" rx="5" class="acc"/>
<text x="152" y="115" text-anchor="middle" fill="var(--bg)" font-size="13">✓</text>
<text x="176" y="115" class="m">SKG WATCH S7-41AD</text>
<text x="700" y="115" text-anchor="end" class="a">S7 · 可加</text>

<rect x="140" y="134" width="24" height="24" rx="5" class="dis"/>
<text x="176" y="151" class="m">SKG WATCH S7-FCC4</text>
<text x="700" y="151" text-anchor="end" class="s">已在队列</text>

<g opacity="0.5">
<rect x="140" y="170" width="24" height="24" rx="5" class="dis"/>
<text x="152" y="187" text-anchor="middle" class="s">✕</text>
<text x="176" y="187" class="m">Mi Band 8</text>
<text x="700" y="187" text-anchor="end" class="s">不支持 OTA</text>
</g>

<rect x="140" y="205" width="580" height="0.6" fill="var(--line)"/>
<text x="430" y="200" text-anchor="middle" class="s">— 底部：加入队列 (2) —</text>
</svg>
</div>

### 5.5 增删规则（开始前 / 开始后）

| 时机 | 新增设备 | 删除设备 |
|---|---|---|
| 批量**开始前** | ✅ 扫描添加任意 B2A 设备 | ✅ 任意行可删 |
| 批量**运行中** | ❌ 禁止（用户拍板） | ✅ 「待升级」行可删（从后续队列摘除）；「完成/失败/跳过」行可删（清理）；❌ **正在刷写的当前台锁定**（停它只能用顶栏「停止批量」） |

---

## 6. 数据模型

单设备的 [`OtaTestUiState`](../../app/src/main/java/io/bluetrace/viewmodel/OtaTestViewModel.kt#L36) 是单目标（一个 `device`、一个 `runJob`、一个全局 log）。多设备把它一般化为「模式 + 队列 + 共享包」。

```kotlin
enum class DeviceOtaStatus { QUEUED, CONNECTING, READING, FLASHING, DONE, FAILED, SKIPPED_LOW_BATTERY }

data class DeviceOtaItem(
    val device: ScannedDevice,            // 扫描入队时拿到 name/address/rssi
    val status: DeviceOtaStatus = DeviceOtaStatus.QUEUED,
    val phase: OtaPhase? = null,          // 刷写子阶段（下载/等复位/重连/读版本）
    val progress: OtaProgress? = null,    // 字节进度
    val versionBefore: String? = null,
    val versionAfter: String? = null,
    val batteryBefore: Int? = null,
    val batteryAfter: Int? = null,
    val failReason: String? = null,       // 失败/跳过原因
) {
    val retriable: Boolean get() = status == DeviceOtaStatus.FAILED || status == DeviceOtaStatus.SKIPPED_LOW_BATTERY
    val removable: Boolean get() = status != DeviceOtaStatus.CONNECTING &&
        status != DeviceOtaStatus.READING && status != DeviceOtaStatus.FLASHING
}

data class MultiOtaUiState(
    val multiMode: Boolean = false,       // 顶栏开关；默认关
    val pkg: OtaPkgItem? = null,          // 多设备锁 1 包，全队列共用
    val queue: List<DeviceOtaItem> = emptyList(),
    val running: Boolean = false,
    val currentIndex: Int = -1,           // 当前正在刷第几台
) {
    val doneCount get() = queue.count { it.status == DeviceOtaStatus.DONE }
    val failCount get() = queue.count { it.status == DeviceOtaStatus.FAILED }
    val skipCount get() = queue.count { it.status == DeviceOtaStatus.SKIPPED_LOW_BATTERY }
    val canStart get() = pkg != null && queue.any { it.status == DeviceOtaStatus.QUEUED } && !running
}
```

**实现（落码后架构，与上面概念模型的差异已就地对齐）**：串行编排核 = shared [`MultiOtaController`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/MultiOtaController.kt)（commonMain，**无 Android 依赖，iOS 可复用**；`DeviceOtaStatus`/`DeviceOtaItem` 一并下沉 shared）——持队列 + `startBatch(pkg)` 串行遍历 `QUEUED` 逐台驱动 [§4](#4-每台设备状态机流程) 流程 + `stopBatch()` / `retry(id,pkg)` / `retryAllFailed(pkg)` + `addDevices` / `removeDevice`；状态经 `queue` / `running` `StateFlow` + `opLog` `SharedFlow` 上抛。App 侧 [`MultiOtaViewModel`](../../app/src/main/java/io/bluetrace/viewmodel/MultiOtaViewModel.kt) 是**薄壳**：包加载（Uri→zip→`OtaPackage`）、扫描添加 UI、把 controller 三条流映射成 Compose 状态（`MultiOtaUiState` 由 VM 组合，`multiMode` 移到 UI 本地 `rememberSaveable`、`currentIndex` 去掉）。与仓库惯例一致（`OtaProvisioner`/`S7Console`/`ConnectionRegistry` 皆在 shared）。

> **编排核有 jvmTest 覆盖**：[`MultiOtaControllerTest`](../../shared/src/commonTest/kotlin/io/bluetrace/shared/s7/MultiOtaControllerTest.kt)（N 个 `S7MockWatch` 造多设备假 `BleClient`，虚拟时钟 + `Job.join()` 驱动）验证 串行全完成 / 失败即跳过继续 / 电量门槛跳过 / 单台重试 —— 4 用例绿。

> **顶栏开关落地为同屏切模式**（非两屏）：`OtaTestScreen` 顶栏 `Switch` 翻 `multiMode`，开=渲染 `MultiOtaBody`（薄壳 VM）、关=现状单设备 body（`OtaTestViewModel` **零改动**）——单设备现状零风险。

---

## 7. 串行编排时序

批量 = 队列逐台**背靠背**跑完整流程，同一时刻只有一台在通信。

<div class="fig">
<svg viewBox="0 0 840 180" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>多设备 OTA 串行编排时间线</title>
<desc>时间从左到右，设备 A、B、C 的处理块背靠背排列，每块内部依次是连接、读、刷写（占大头）、读、断开；同一时刻只有一台在通信。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:10px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.seg{fill:var(--code);stroke:var(--line);stroke-width:1;}
.hot{fill:var(--code);stroke:var(--accent);stroke-width:1.4;}
.sep{stroke:var(--line);stroke-width:1;stroke-dasharray:4 3;}
.ax{stroke:var(--muted);stroke-width:1.2;fill:none;}
</style>

<text x="60" y="44" class="t">设备 A</text>
<rect x="60"  y="52" width="26" height="40" class="seg"/>
<rect x="86"  y="52" width="34" height="40" class="seg"/>
<rect x="120" y="52" width="150" height="40" class="hot"/>
<rect x="270" y="52" width="26" height="40" class="seg"/>
<rect x="296" y="52" width="26" height="40" class="seg"/>
<text x="73"  y="108" text-anchor="middle" class="s">连</text>
<text x="103" y="108" text-anchor="middle" class="s">读</text>
<text x="195" y="108" text-anchor="middle" class="s">刷写 OTA（占大头）</text>
<text x="283" y="108" text-anchor="middle" class="s">读</text>
<text x="309" y="108" text-anchor="middle" class="s">断</text>

<line x1="326" y1="46" x2="326" y2="120" class="sep"/>

<text x="336" y="44" class="t">设备 B</text>
<rect x="336" y="52" width="26" height="40" class="seg"/>
<rect x="362" y="52" width="34" height="40" class="seg"/>
<rect x="396" y="52" width="150" height="40" class="hot"/>
<rect x="546" y="52" width="26" height="40" class="seg"/>
<rect x="572" y="52" width="26" height="40" class="seg"/>

<line x1="602" y1="46" x2="602" y2="120" class="sep"/>

<text x="612" y="44" class="t">设备 C</text>
<rect x="612" y="52" width="26" height="40" class="seg"/>
<rect x="638" y="52" width="34" height="40" class="seg"/>
<rect x="672" y="52" width="110" height="40" class="hot"/>
<text x="792" y="78" class="s">…</text>

<line x1="40" y1="132" x2="800" y2="132" class="ax" marker-end="url(#ah2)"/>
<defs><marker id="ah2" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M2 1L8 5L2 9" fill="none" stroke="context-stroke" stroke-width="1.2" stroke-linecap="round"/></marker></defs>
<text x="420" y="150" text-anchor="middle" class="s">时间 →　　同一时刻只有一台在通信（含设备端授权 + 重连读版本）</text>
</svg>
</div>

---

## 8. 失败与边界处理

- **失败即跳过**：任一步失败标记该台 `FAILED(reason)`，`continue` 下一台，不因一台坏而停整批。批尾汇总 `X 完成 · Y 失败 · Z 跳过`。
- **电量门槛**：`READING` 拿到 `batteryBefore`，`< 30%` 直接 `SKIPPED_LOW_BATTERY`，不进 `provision`。
- **手动重试**：`FAILED` / `SKIPPED_LOW_BATTERY` 行显示「重试 ↻」，点击把该台重置回 `QUEUED` 并单独跑一遍完整流程（复用同一批量单元）。
- **停止批量**：顶栏「停止批量」取消当前台的 `runJob`；已完成/失败/跳过保留结果，未开始的留在 `QUEUED`。参照单设备 `BackHandler(enabled = running)`，运行中拦截系统返回。
- **重连 by-id 假设**：重连是「按已知 MAC 重发 `connect`」，不是扫描重发现（[`OtaProvisioner.reconnect`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/OtaProvisioner.kt#L83)）。S7 用静态 MAC，成立；换设备须复核。
- **registry 抖动**：刷中自复位会让 `linkState → DISCONNECTED`，[`ConnectionRegistry`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/ConnectionRegistry.kt) 自动 `remove`；编排层像单设备一样自持 `device` 引用、重连后 `registry.add` 回来（[`OtaTestViewModel.kt:199`](../../app/src/main/java/io/bluetrace/viewmodel/OtaTestViewModel.kt#L199)）。
- **多连上限**：registry `DUT ≤ 3`，但串行同一时刻只 1 连，不触上限；队列长度不受此限（未连的只是待办）。

---

## 9. 落地（已完成）

> **状态：已落地并真机（M2101K9C）验证通过**（2026-07-09）。与设计一处偏差：顶栏开关做成**同屏切模式**（非独立屏），单设备 `OtaTestViewModel` 零改动；因此**未新增路由**。

| 层 | 落地 |
|---|---|
| 编排核（新，shared） | [`MultiOtaController`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/s7/MultiOtaController.kt)（commonMain 无 Android 依赖，iOS 可复用；`DeviceOtaStatus`/`DeviceOtaItem` 同处）——队列 + 串行遍历 + 电量门槛 + 重试；状态经 `queue`/`running` `StateFlow` + `opLog` `SharedFlow` 上抛 |
| VM（新，薄壳） | [`MultiOtaViewModel`](../../app/src/main/java/io/bluetrace/viewmodel/MultiOtaViewModel.kt)——Uri→zip 载包、扫描添加 UI、日志镜像、`MultiOtaUiState` 组合 |
| UI（新） | [`MultiOtaScreen.kt`](../../app/src/main/java/io/bluetrace/ui/screen/settings/MultiOtaScreen.kt) `MultiOtaBody` + 队列行 5 态 + 扫描添加底部弹窗 |
| 顶栏开关 | [`OtaTestScreen`](../../app/src/main/java/io/bluetrace/ui/screen/settings/OtaTestScreen.kt) 顶栏 `Switch` 翻 `multiMode`（同屏切；单设备 body 不改，运行中锁定） |
| DI | [`AppModule.kt`](../../app/src/main/java/io/bluetrace/di/AppModule.kt) 注册 `MultiOtaViewModel`（路由无需新增） |
| 测试 | [`MultiOtaControllerTest`](../../shared/src/commonTest/kotlin/io/bluetrace/shared/s7/MultiOtaControllerTest.kt)（jvmTest，N 个 `S7MockWatch` 造多设备假 `BleClient`）：串行全完成 / 失败即跳过继续 / 电量门槛跳过 / 单台重试 —— **4 例绿** |

**验收（全过）**：队列 5 态显示正确 ✓；版本/电量前后值正确 ✓；单台 + 「重试全部失败」可用 ✓；停止批量干净收尾 ✓；电量门槛生效 ✓；开关关时单设备现状零影响 ✓。

---

## 10. Open Questions（落地后回填）

- **O-1 电量阈值可配否**：已做成 `MultiOtaController(lowBatteryPct = 30)` 构造参数（默认 30），暂未放进设置 UI——需要时再提。
- **O-2 独立屏 vs 同屏切模式**：**已定 = 同屏切模式**（顶栏 `Switch` 翻 `multiMode`，单设备 VM 零改动）。
- **O-3 队列持久化**：批量中途退出/杀进程**不恢复**队列——当前定位（attended、盯着刷）下不做。
- **O-4 重试全部失败项**：**已做**（批尾「重试全部失败 (N)」+ 每行单台重试 ↻）。

---

## 11. 演示与真机验证（Mock 多台）

无真表时用 Mock 也能试"多台"：

- **Mock 多台**：[`MockBleClient`](../../shared/src/commonMain/kotlin/io/bluetrace/shared/ble/mock/MockBleClient.kt) 从 1 台 S7 扩到 3 台（每设备独立 `S7MockWatch` + inbound；roster 加 S7-A31B / S7-2D90）；S7 表默认 `otaRebootAfterComplete=true`，走 `刷后自复位 → 断链 → 重连` 闭环（更真、无 90s 空等）。
- **DEBUG 示例包入口**：多设备屏**长按**「添加烧录包」载入内存合成包（绕过 SAF/真实 zip），**仅 Mock 模式可用**（真实 GATT 下 no-op，防把合成垃圾包误推给真表）。
- **怎么试**：设置页开「使用 Mock BLE」（重启生效）→ OTA 固件 → 顶栏开「多设备」→ 长按加示例包 → 扫描添加选 S7 入队 → 开始批量。
- **真机走通**（M2101K9C / Android 13）：开关切换、扫描 B2A 过滤（S7「可加」/ 非 B2A「不支持 OTA」）、多选入队、串行逐台（设备1 刷写时设备2 待升级）、两台各 连接·读版本电量·刷写·复位·重连·复读、汇总「完成 2」，日志 REQ/START/STOP/DONE/重连成功 全链印证。
- **两处 UI 细节**（真机反馈修正）：扫描底部弹窗 `skipPartiallyExpanded=true`（默认全展开，「加入队列」按钮不必上拉即见）；队列行「版本 X→Y · 电量 A%→B%」移到名字下方整行铺满（不再被右侧状态药丸/删除键挤占截断）。

---

> 配套图文档（离线 HTML）：`S7多设备OTA_设计.html`（与本 md 成对）。协议真值见 [S7采集OTA_指令交互与流程.md](S7采集OTA_指令交互与流程.md)。
