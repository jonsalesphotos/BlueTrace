# 通用 OTA 分派 · 设计（任务 #27）

> 2026-07-15 · 分支 `task/27-generic-ota-design`
> **v2（按用户纠正重写）**：v1 曾建议把批量控制器改名 `S7MultiOtaController` 并固化为"S7 专属工具"——**该建议已撤回**，方向是反的：批量 OTA 是**软件功能**，协议是**注入的插件**，把软件功能反向绑死到设备型号是架构债而不是边界固化。
> 一阶段（协议正名 S7→B2A）**已落码**，见 CHANGELOG。本文余下为二~四阶段设计。

## 命名与分层原则（本设计的地基）

**设备名称会变，GATT UUID 才是稳定锚点。**

- 设备名称（`SKG WATCH S7-FCC4`）**只用于 UI 展示**，可随产品改名；
- **GATT UUID（FFE0）是识别锚点**，且 **UUID 不得直接写进批量控制器的 if/when**；
- Catalog 按 UUID **识别一次**，随后把**协议能力对象注入**软件编排器；
- **单设备 OTA 与批量 OTA 都是通用软件功能**；
- FILE_TRANS、包格式、复位、回连、传输态门控 —— 全属**协议插件**。

<div class="fig">
<svg viewBox="0 0 840 340" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>正确的 OTA 分层：UUID 识别一次，能力注入通用软件编排器</title>
<desc>GATT UUID 只负责识别，DeviceProfileCatalog 据此注入升级能力对象（含包解析器、升级策略、中止语义、回连验证语义）；批量与单设备 OTA 是通用软件编排器，只持有队列、重试、租约、停止善后，对每个任务调用注入的能力，自身不含任何协议或产品名分支。</desc>
<style>
.t{fill:var(--fg);font-size:11.5px;font-weight:700;font-family:Consolas,monospace;}
.s{fill:var(--muted);font-size:10.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.ok{fill:var(--code);stroke:var(--accent);stroke-width:1.5;}
.a{fill:var(--accent);font-size:11.5px;font-weight:700;font-family:Consolas,monospace;}
.lb{fill:var(--muted);font-size:10.5px;font-weight:700;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
</style>
<defs>
<marker id="a3" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="var(--muted)"/></marker>
</defs>
<rect x="20" y="14" width="240" height="34" rx="3" class="bx"/>
<text x="140" y="35" text-anchor="middle" class="t">GATT UUID（FFE0）</text>
<text x="270" y="35" class="lb">只负责识别</text>
<line x1="140" y1="48" x2="140" y2="66" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a3)"/>
<rect x="20" y="68" width="240" height="34" rx="3" class="bx"/>
<text x="140" y="89" text-anchor="middle" class="t">DeviceProfileCatalog</text>
<text x="270" y="89" class="lb">识别一次 · 注入能力</text>
<line x1="140" y1="102" x2="140" y2="120" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a3)"/>

<rect x="20" y="122" width="240" height="140" rx="3" class="ok"/>
<text x="140" y="143" text-anchor="middle" class="a">FirmwareUpdateCapability</text>
<text x="36" y="166" class="s">├─ 固件包解析器</text>
<text x="36" y="186" class="s">├─ 升级策略</text>
<text x="36" y="206" class="s">├─ 中止语义</text>
<text x="36" y="226" class="s">└─ 回连 / 验证语义</text>
<text x="140" y="250" text-anchor="middle" class="s">＝ 协议插件（B2A / ZX / 未来）</text>

<line x1="260" y1="192" x2="330" y2="192" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a3)"/>
<text x="266" y="185" class="lb">注入</text>

<rect x="335" y="122" width="290" height="140" rx="3" class="ok"/>
<text x="480" y="143" text-anchor="middle" class="a">OTA 软件编排器（单 / 批量）</text>
<text x="351" y="166" class="s">├─ 队列 · 重试</text>
<text x="351" y="186" class="s">├─ Gate / Lease 所有权</text>
<text x="351" y="206" class="s">├─ 停止与善后</text>
<text x="351" y="226" class="s">└─ 逐任务调用注入的能力</text>
<text x="480" y="250" text-anchor="middle" class="s">零协议分支 · 零产品名 · 零 UUID</text>

<rect x="660" y="122" width="160" height="42" rx="3" class="bx"/>
<text x="740" y="140" text-anchor="middle" class="t">B2A 实现</text>
<text x="740" y="156" text-anchor="middle" class="s">FILE_TRANS · 复位回连</text>
<rect x="660" y="172" width="160" height="42" rx="3" class="bx"/>
<text x="740" y="190" text-anchor="middle" class="t">ZX 实现</text>
<text x="740" y="206" text-anchor="middle" class="s">原地生效 · 不断连</text>
<rect x="660" y="222" width="160" height="40" rx="3" class="bx"/>
<text x="740" y="247" text-anchor="middle" class="s">未来协议…</text>
<line x1="625" y1="192" x2="656" y2="150" stroke="var(--muted)" stroke-width="1.2" marker-end="url(#a3)"/>
<line x1="625" y1="192" x2="656" y2="192" stroke="var(--muted)" stroke-width="1.2" marker-end="url(#a3)"/>
<line x1="625" y1="192" x2="656" y2="240" stroke="var(--muted)" stroke-width="1.2" marker-end="url(#a3)"/>

<rect x="20" y="284" width="800" height="42" rx="3" class="ok"/>
<text x="420" y="302" text-anchor="middle" class="a">改设备广播名 → 仍广播 FFE0 → 仍识别为同一协议 → 编排器一行不改</text>
<text x="420" y="319" text-anchor="middle" class="s">产品改名不应看起来像要换协议实现</text>
</svg>
</div>

## 一阶段 · 协议正名（✅ 已落码）

`shared.s7` → `shared.b2a`；`S7Xxx` → `B2aXxx`（27 个类型）；`object S7` → `object B2a`；`PROFILE_S7` → `PROFILE_B2A`；`S7_TEST_MAC` → `TEST_DUT_MAC`（它指一台物理测试设备、不是协议，故取中性角色名）。FFE0/FFE1/FFE2 继续作为 GATT 识别与通道声明。

**严格保护未动**（改了即行为/事实变化）：~~`"SKG.S7.B2A"`（`PROFILE_B2A` 的值）~~〔✅ 07-16 已另行改为 `"B2A.0xFFE0"`，独立提交〕、`"SKG WATCH S7-FCC4"`（真实广播名，设备事实）、`"s7-fcc4"`（Mock roster 设备 id）。

**顺带勘误**：`Device.kt` 原 KDoc 称"正式识别按广播名前缀 `SKG WATCH S7-`"——与实际代码矛盾（`B2aDetect.matchesAdvertisement` 按广播 FFE0 判定），已改写为"profileId 是识别**结果**的稳定标识而非判据；识别锚点是 GATT UUID；产品名只用于 UI 展示"。

零行为改动的证明：216/0 + 9/0 + 33 个测试类，与基线逐条相同。

~~**遗留待决**：`PROFILE_B2A` 的值~~〔✅ 07-16 已落：`"B2A.0xFFE0"`（比本节旧提案 `B2A.SKG.0xFFE0` 更彻底——厂商也退出），独立提交 `2aa0c14` 可回退〕

## 二阶段 · 真正分离批量编排与协议

`MultiOtaController` 移出 `b2a` 包 → 通用 OTA 包（如 `shared/device/ota/`），**只保留软件职责**：队列与状态、批次开始/停止/重试、`OtaOperationGate` 与所有权（Lease/RunToken）模型、失败记录与日志、逐台调度。

**它不再引用**：`B2aConsole`、`B2aFirmwareUpdateStrategy`、`OtaPackage`、`FFE0/FFE1/FFE2`、任何产品或协议名。

每个队列项在**入队时**保存识别出的能力：

```kotlin
data class FirmwareUpdateJob(
    val device: ScannedDevice,
    val capability: FirmwareUpdateCapability,  // catalog 按 UUID 识别后注入
    val pkg: FwPackage,
)
```

执行时只调用：

```kotlin
val strategy = job.capability.createStrategy(...)
strategy.run(job.pkg, onProgress)
```

**注**：租约与七轮收口打磨出的停止/善后所有权模型（Lease CAS / RunToken 仲裁 / finally 的 isActive 联用）是**协议中立**的软件资产，整体留在通用层。

### 顺带清掉的债：通用工厂签名已被协议污染

```kotlin
// 现状 shared/device/FirmwareUpdate.kt
fun create(..., abortScope: CoroutineScope, reconnectScanMs: Long = 60_000, onLog: ...): FirmwareUpdateStrategy
//                                          ^ "自复位后扫描回连"是 B2A 私有假设
```

`ZxFirmwareUpdateFactory` 的 KDoc 明说 `scope/clock/zone/abortScope/reconnectScanMs/onLog` **全部未使用**。W4 已经做对过一次——把 `onOtaPhase`/`onOtaProgress` 排除在通用工厂外、改具体策略构造点注入；`reconnectScanMs` 是同类东西却漏了进来。

**D-1**：`reconnectScanMs` 下沉为 B2A 私有构造参数（由 `B2aDeviceProfile` 构造自己的能力时从 `EngineeringConfig` 取）。`abortScope` **保留**在通用签名——"善后跑在调用方生命周期之外"是真正的跨协议软件需求（ZX 不用属合理的"可选不使用"，不是语义污染）。

## 三阶段 · 预检走通用分面

批量控制器现在直接 `B2aConsole` 读版本、电量（`readVersionBattery`/`readBattery`）——拆掉，复用 W3 的 `DeviceControl` 六分面：

| 预检项 | 走通用分面 | 分面为 null 时 |
| --- | --- | --- |
| 电量门槛 | `control.battery.percent()` | 跳过门槛（无电量能力即无此保护） |
| 版本读取 | `control.info.get()` | 跳过版本展示 |

**低电量阈值属于批量 OTA 的软件策略，不属于 B2A 协议** —— 它留在编排器，但**数据来源**改为通用分面。

协议特有部分继续留在策略内部：FILE_TRANS 命令、传输态中止门控、**永不发送 STOP**、复位命令、同设备 ID 回连、60s 扫描预算、升级包文件顺序。

**已知结构级假设待处理**：`MultiOtaController` 升级后用**同一 `device`（同 BLE id）**复读电量。对 B2A 成立（自复位回连同 id），对"升级后切 Bootloader 服务/换地址"的协议会读错设备。二阶段抽公共骨架时，应由策略返回升级后的设备标识（或声明"身份不变"），而不是编排器写死假设。

## 四阶段 · 固件包解析注入

Android 侧只负责**解容器**（协议中立）：

```
Uri / zip / bin  →  List<RawFwEntry>(name, bytes)
```

协议侧只负责**解语义**：

```
List<RawFwEntry>  →  本协议的 FwPackage
```

```kotlin
// shared/device/ 新增
data class RawFwEntry(val name: String, val bytes: ByteArray)
interface FwPackageLoader { fun load(entries: List<RawFwEntry>): FwPackage }  // 失败即抛, fail fast
```

- `OtaZipLoader` 现在把两件事揉在一起（解 zip + 认 B2A 的推送序/`FT_FW`/字库名约定/校验规则）。拆开后：`:app` 留一个协议中立的 `RawPackageReader`（它不认识任何协议）；`OtaPackageValidator` 整段搬进 `B2aFwPackageLoader`，**逻辑零改动**。
- **loader 挂在能力对象上**（而非 `DeviceProfile` 顶层）：无升级面即无包，内聚更好。
- ZX 顺带补上真 loader —— 现在 `ZxPackage()` 只在测试里手搓，KDoc 的"由本协议 loader 产出"是空头承诺。

这样**新增协议只需提供**：DeviceProfile、GattSpec（UUID）、FwPackageLoader、FirmwareUpdateStrategy/Factory、catalog 一行注册。**单设备与批量页面均无需修改。**

## 验收标准

1. **任意修改设备广播名**，只要仍广播 FFE0，就识别为同一协议；
2. 通用 OTA UI、ViewModel、批量控制器中**没有 S7 / B2a / FFE0 分支**；
3. **两个 fake 协议跑同一个批量控制器**，各自调用注入的策略；
4. **ZX 升级链中不得出现任何 B2A / FILE_TRANS 帧**（帧捕获断言，仿 W4"传输中 abort 不发 RESET"写法）；
5. **B2A 当前 OTA 行为与四条真机红线零变化**（写流控 / 中止门控·永不 STOP / 扫描回连≥60s / 停止善后 scope）；
6. 一批任务先限定**同一 protocolId + 兼容固件包** —— 这是**包兼容约束，不是设备名称约束**；控制器结构仍支持以后扩展为混合协议队列。

## 落码顺序与闸门

| 阶段 | 内容 | 闸门 |
| --- | --- | --- |
| ✅ 一 | 协议正名（纯搬迁） | 216/0 + 9/0 与基线逐条相同 |
| 二 | 批量编排与协议分离（含 D-1 签名净化） | 验收 2 + 3；B2A 行为零变化 |
| 三 | 预检走通用分面 | 分面 null 时优雅跳过；验收 5 |
| 四 | 包解析注入 | 验收 4；B2A 校验规则搬迁后零改动 |
| — | 判定放开 `firmwareUpdate != null` | **必须最后**：提前放开 = ZX 入队被灌 B2A 指令 |

## Open Questions

1. ~~**`PROFILE_B2A` 的值**~~〔✅ 2026-07-16 已落：定为 `"B2A.0xFFE0"`（厂商与设备名全部退出，比本节旧提案更彻底），独立提交可回退〕 ~~`"SKG.S7.B2A"` 是否改为 `"B2A.SKG.0xFFE0"`~~（对齐 `PROFILE_HRS = "HeartRate.SIG.0x180D"` 的"协议.组织.UUID"惯例，并把 UUID 锚进标识）？**代价**：写进 manifest 的历史会话数据不再匹配（demo 阶段惯例是"直接替换不做迁移"，故成本低）。**倾向：改**，但作为独立可回退提交。
2. ~~`RawPackageReader` 的非 zip 分支~~〔⛔ 2026-07-16 用户收回：**RAW/通用包读取层暂缓设计**，现阶段保持既有 B2A 包加载逻辑，不提前冻结未知需求——本文四阶段中涉 `RawFwEntry` 的部分随之冻结〕
3. 中止文案（现 UI 硬编码"会向设备发送重启指令使其复位"，对 ZX 是错的）：给策略加 `abortHint: String?`，还是文案一律中性、协议细节移进 `detail`/日志？**倾向：后者**，不为一句文案动通用接口。
