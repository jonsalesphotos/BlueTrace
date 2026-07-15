# 通用 OTA 分派 · 设计（任务 #27）

> 2026-07-15 · 分支 `task/27-generic-ota-design` · 状态：**设计稿，待拍板后分阶段落码**
> 起源：2026-07-15 Codex 一轮复核发现②——W5 把 OTA 入口泛化到 `firmwareUpdate != null`，但执行链是 S7 专属（zip loader / 策略 / S7Console），异构设备（ZX）入队会被灌 S7 指令；当时临时把两屏收紧回 `S7FirmwareUpdateFactory` 类型判定，通用分派另立本任务。
> 前置事实：抽象层 W1–W6 已闭环（判据"新增协议 = 一个包 + 一行注册"在**采集/控制面**成立）；**OTA 面是该判据唯一尚未覆盖的分面**。

## 结论（前置）

**不要一次性"通用化 OTA"**。测绘显示耦合分三层，难度天差地别，必须分阶段：

1. **纯类型皮**（好换）：两个 VM 的 `import ...s7.*` + 直接 `new S7FirmwareUpdateStrategy/S7Console`、`OtaToolSupport` 锚具体类型 —— 机械改动。
2. **缺口**（要新建抽象）：**包加载器根本没有框架位置** —— `OtaZipLoader` 在 `:app`、硬编码 S7 zip 格式与 `S7FileTrans.FT_FW`、Koin 单例、两个 VM 编译期直调；`DeviceProfile` 三分面里唯独没有"包怎么来"。ZX 连 loader 都不存在（`ZxPackage()` 只在测试里手搓）。
3. **语义级假设**（最难，且未必该通用化）：`MultiOtaController` 的流程骨架就是照 S7 写的 —— 电量门槛、独立 READING 状态、**"升级后同一 BLE id 复读电量"**。

**故 #27 拆成 #27A（单设备通用化，含契约）与 #27B（批量，先评估再决定要不要通用化）**，中间设审查闸门。**当前 `OtaToolSupport` 的 S7 限制在 #27A 落地前不得放开** —— 否则 ZX 入队即被灌 B2A 指令。

## 一个被忽略的债：通用工厂签名本身已被 S7 污染

```kotlin
// shared/device/FirmwareUpdate.kt:70-81 现状
interface FirmwareUpdateFactory {
    fun create(
        ble: BleClient, device: ScannedDevice, scope: CoroutineScope,
        clock: EpochClock, zone: TimeZoneProvider,
        abortScope: CoroutineScope,
        reconnectScanMs: Long = 60_000,   // <-- "自复位后扫描回连"是 S7 私有假设
        onLog: (String) -> Unit = {},
    ): FirmwareUpdateStrategy
}
```

`ZxFirmwareUpdateFactory` 的 KDoc 明说 `scope`/`clock`/`zone`/`abortScope`/`reconnectScanMs`/`onLog` **全部未使用**。W4 已经做对过一次——把 `onOtaPhase`/`onOtaProgress`（S7 细进度）**排除在通用工厂之外**、改由具体策略构造点注入。`reconnectScanMs` 是同一类东西，却漏进了通用签名。

**决策 D-1**：`reconnectScanMs` 从通用工厂签名下沉为 S7 私有构造参数（由 `S7DeviceProfile` 在构造自己的工厂时从 `EngineeringConfig` 取）。`abortScope` **保留**在通用签名——"善后要跑在调用方生命周期之外"是真正的跨协议需求（ZX 不用属于合理的"可选不使用"，不是语义污染）。

## 现状 vs 目标

<div class="fig">
<svg viewBox="0 0 840 330" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>OTA 执行链：现状 S7 硬编码 vs 目标按 profile 分派</title>
<desc>现状下 OTA 屏直接构造 S7 zip 加载器与 S7 策略，判定锚 S7 工厂类型；目标是屏只依赖 DeviceProfile 的升级面与包加载面，按识别结果分派到各协议实现。</desc>
<style>
.t{fill:var(--fg);font-size:11.5px;font-weight:700;font-family:Consolas,monospace;}
.s{fill:var(--muted);font-size:10.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.h{fill:var(--fg);font-size:12px;font-weight:700;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bad{fill:var(--code);stroke:var(--danger);stroke-width:1.5;}
.ok{fill:var(--code);stroke:var(--accent);stroke-width:1.5;}
.d{fill:var(--danger);font-size:11.5px;font-weight:700;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-size:11.5px;font-weight:700;font-family:Consolas,monospace;}
</style>
<defs>
<marker id="a2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="var(--muted)"/></marker>
</defs>
<text x="20" y="20" class="h">现状</text>
<rect x="20" y="30" width="170" height="40" rx="3" class="bx"/>
<text x="105" y="47" text-anchor="middle" class="t">OTA 屏 / VM</text>
<text x="105" y="62" text-anchor="middle" class="s">单设备 + 多设备</text>
<rect x="230" y="30" width="180" height="40" rx="3" class="bad"/>
<text x="320" y="47" text-anchor="middle" class="d">OtaZipLoader</text>
<text x="320" y="62" text-anchor="middle" class="s">:app · S7 zip 格式写死</text>
<rect x="450" y="30" width="180" height="40" rx="3" class="bad"/>
<text x="540" y="47" text-anchor="middle" class="d">S7FirmwareUpdateStrategy</text>
<text x="540" y="62" text-anchor="middle" class="s">直接 new · 不查工厂</text>
<rect x="670" y="30" width="150" height="40" rx="3" class="bad"/>
<text x="745" y="47" text-anchor="middle" class="d">S7Console</text>
<text x="745" y="62" text-anchor="middle" class="s">读版本 / 电量</text>
<line x1="190" y1="50" x2="228" y2="50" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<line x1="410" y1="50" x2="448" y2="50" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<line x1="630" y1="50" x2="668" y2="50" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<text x="20" y="96" class="s">判定锚 S7FirmwareUpdateFactory 具体类型 · 异构设备一律拒绝 · 新增协议 = 改屏改 VM</text>

<line x1="20" y1="112" x2="820" y2="112" stroke="var(--line)" stroke-width="1"/>

<text x="20" y="136" class="h">目标（#27A）</text>
<rect x="20" y="146" width="170" height="44" rx="3" class="ok"/>
<text x="105" y="164" text-anchor="middle" class="a">OTA 屏 / VM</text>
<text x="105" y="179" text-anchor="middle" class="s">只认通用类型</text>
<rect x="230" y="146" width="180" height="44" rx="3" class="bx"/>
<text x="320" y="164" text-anchor="middle" class="t">DeviceProfile</text>
<text x="320" y="179" text-anchor="middle" class="s">identify() 一次识别</text>
<rect x="450" y="146" width="180" height="44" rx="3" class="ok"/>
<text x="540" y="164" text-anchor="middle" class="a">firmwareUpdate 面</text>
<text x="540" y="179" text-anchor="middle" class="s">工厂 + 包加载面（新）</text>
<line x1="190" y1="168" x2="228" y2="168" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<line x1="410" y1="168" x2="448" y2="168" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>

<rect x="670" y="130" width="150" height="34" rx="3" class="bx"/>
<text x="745" y="151" text-anchor="middle" class="t">S7 实现</text>
<rect x="670" y="172" width="150" height="34" rx="3" class="bx"/>
<text x="745" y="193" text-anchor="middle" class="t">ZX 实现</text>
<rect x="670" y="214" width="150" height="34" rx="3" class="bx"/>
<text x="745" y="235" text-anchor="middle" class="s">未来协议…</text>
<line x1="630" y1="168" x2="668" y2="150" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<line x1="630" y1="168" x2="668" y2="188" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>
<line x1="630" y1="168" x2="668" y2="228" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#a2)"/>

<rect x="20" y="270" width="800" height="44" rx="3" class="ok"/>
<text x="420" y="289" text-anchor="middle" class="a">验收：新增协议 = 协议包 + 固件包解析器 + 一行 catalog 注册；屏与编排器零协议判断</text>
<text x="420" y="306" text-anchor="middle" class="s">判定放开为 firmwareUpdate != null（届时才安全）</text>
</svg>
</div>

## #27A · 单设备通用化（含契约）

### D-2：包加载分两层——平台解容器 / 协议解语义

这是本设计的核心裁定。`OtaZipLoader` 现在把两件事揉在一起：**解 zip 容器**（平台活，Android `java.util.zip`，`commonMain` 不可用）与**解读 S7 语义**（推送序、`FT_FW` 类型、字库文件名约定、校验规则）。拆开：

```kotlin
// shared/device/FirmwareUpdate.kt（新增）
/** 容器解出的原始条目: 名字 + 字节. 平台侧产出, 协议侧解读.  */
data class RawFwEntry(val name: String, val bytes: ByteArray)

/** 协议私有的"原始条目 -> 本协议 FwPackage"解析面; 挂在升级面上, 由 profile 提供.  */
interface FwPackageLoader {
    /** 解析失败即抛(fail fast), 由调用方转成 UI 错误.  */
    fun load(entries: List<RawFwEntry>): FwPackage
}
```

- **`:app` 侧**保留一个**协议中立**的 `RawPackageReader`：URI → `List<RawFwEntry>`（是 zip 就解成多条目；非 zip 就单条目）。它不认识任何协议。
- **协议侧**（`shared/s7/S7FwPackageLoader`）把 `RawFwEntry` 列表按 S7 规则排序/校验/打类型，产出 `OtaPackage`。`OtaPackageValidator` 整段搬进来，逻辑零改动。
- ZX 侧顺带补上真 loader（现在只在测试里手搓 `ZxPackage()`），把"注释里的承诺"兑现，也让 W6 判据在 OTA 面有真样本。

**为什么不把 loader 直接挂 `DeviceProfile`**：包加载是升级面的一部分，无升级面就无包。挂在 `FirmwareUpdateFactory` 上内聚更好：

```kotlin
interface FirmwareUpdateFactory {
    /** 本协议的固件包解析面.  */
    val packageLoader: FwPackageLoader
    fun create(ble, device, scope, clock, zone, abortScope, onLog): FirmwareUpdateStrategy
    //                                                ^ reconnectScanMs 已按 D-1 下沉
}
```

### D-3：UI 只认通用进度，S7 细阶段经 `detail` 透传

现状 `OtaTestUiState.phase: OtaPhase?`（S7 细枚举）直达 UI，两个 Screen 各自重复一份 `OtaPhase.label()`（含"扫描回连"），进度速度显示按 `== OtaPhase.Downloading` 判断。

改：UI 状态改 `FwUpdateProgress`（`phase: FwUpdatePhase` 粗五阶段 + `percent` + `bytesPerSec` + `detail`）。

- 速度显示判据 `OtaPhase.Downloading` → `FwUpdatePhase.Transferring`；
- **"扫描回连""等待复位"等 S7 细文案走 `detail`**——W4 设计的 `detail` 字段正是为此，通道现成；
- 两份重复的 `label()` 合成一份通用 `FwUpdatePhase.label()`。

需要细阶段观察的消费方（如按细阶段计时算均速）**直接构造具体策略注入 `onOtaPhase`**——W4 已裁定的现成逃生口，不为它污染通用层。

### D-4：中止文案按能力说话

`OtaTestScreen.kt:141` 的"会向设备发送重启指令使其复位"是 S7 语义；ZX 的 `abort()` 是空操作。改：文案由策略提供（如 `FirmwareUpdateStrategy.abortHint: String?`，null 即用中性文案）。**这是唯一需要给通用接口加成员的 UI 需求**，其余全走 `detail`。

### D-5：判定放开（**必须最后做**）

`OtaToolSupport.supportsOtaTool` 从 `is S7FirmwareUpdateFactory` 放开为 `firmwareUpdate != null`。**顺序红线：D-2/D-3/D-4 全部落地并过闸后才可执行 D-5**；提前放开 = ZX 入队被灌 B2A 指令（正是 Codex 一轮发现②）。

### #27A 验收判据

1. **ZX 走通单设备 OTA 全程零 S7 指令**——帧捕获断言：不出现任何 B2A/FILE_TRANS 帧（对照 W4 已有的"传输中 abort 不发 RESET"帧捕获测试写法）；
2. `app` 层 `import io.bluetrace.shared.s7.*` 在单设备 OTA 链路清零（`OtaToolSupport`/`OtaTestViewModel`/`OtaTestScreen`）；
3. S7 单设备 OTA 行为**零变化**（现有测试全绿 + 真机冒烟一轮）；
4. 新增协议接入 OTA = 协议包内加一个 `FwPackageLoader` + 工厂挂上，**屏与 VM 零改动**。

## #27B · 批量 OTA（先评估，不预设通用化）

测绘结论：`MultiOtaController` **物理上在 `io.bluetrace.shared.s7` 包内**，却自称"KMP 通用编排核"。它的流程骨架带三条 S7 语义假设：

| 假设 | 位置 | 对 ZX 这类协议 |
| --- | --- | --- |
| 电量门槛（`lowBatteryPct=30`，读数走短命 `S7Console`） | `MultiOtaController.kt:76`/:64/:372-393 | 原地生效无掉电变砖风险，门槛空转 |
| 独立 `READING` 状态（连接后单读版本+电量） | `:24-33`/:296-359 | 无需要，多余一步 |
| **升级后用同一 `device`（同 BLE id）复读电量** | `:341-342` | ZX 侥幸成立；"升级后切 Bootloader 服务/换地址"的协议直接读错设备 |

**建议（待拍板）**：**#27B 不追求"名义通用"**。三选一，倾向 ② ——

1. 抽公共骨架 `MultiUpdateController`（队列/重试/租约/停止善后）+ 协议钩子（`preflight`/`postflight` 可空）—— 收益真实但改动面大，且"同 ID 回连"是结构级假设，抽象要小心；
2. **正名 + 边界固化**：`MultiOtaController` 保持 S7 专属并改名（如 `S7MultiOtaController`）、多设备屏保持 `PROFILE_S7` 收紧，**把"批量 OTA 是 S7 工具"写成显式设计约束**。理由：批量 OTA 是 DEBUG 工具屏、手头只有 S7 真设备、通用化收益未被真实需求验证；等第二种真设备进场再谈 ①。W6 的判据本就是"新增协议 = 一个包 + 一行注册"，**没承诺"每个 DEBUG 工具都通用"**；
3. 现状不动 —— 不可取：`s7` 包里放"通用编排核"这个矛盾会持续误导后来人。

**注意**：租约（`OtaOperationGate`）与七轮收口打磨的停止/善后模型是**协议中立**的，无论 #27B 选哪条都应保留在通用层。

## 落码顺序与闸门

| 阶段 | 内容 | 闸门 |
| --- | --- | --- |
| #27A-1 | 契约：`RawFwEntry`/`FwPackageLoader`/工厂签名净化（D-1、D-2） | 单测：S7 loader 搬迁后校验规则零改动（既有用例全绿）+ ZX loader 新样本 |
| #27A-2 | 单设备链路通用化（D-3、D-4） | S7 行为零变化（既有测试全绿）；UI 只剩通用类型 |
| #27A-3 | 判定放开（D-5） | **ZX 单设备 OTA 帧捕获零 S7 指令**；真机 S7 冒烟一轮 |
| #27B | 按拍板结果执行 | 见上 |

## Open Questions（待用户拍板）

1. **#27B 走哪条**？（倾向 ②"正名 + 边界固化"）
2. `RawPackageReader` 的非 zip 分支（单 `.bin` 直接当单条目）是否现在就要，还是等真有此类协议？（倾向：接口留位，实现先只做 zip + 单文件兜底，成本近乎为零）
3. `FirmwareUpdateStrategy.abortHint` 是否值得为它动通用接口？替代方案是中止文案一律中性（"将中止本次刷写"），S7 的"发重启指令"细节移进日志/detail——**若倾向少动接口，可选此替代**。
