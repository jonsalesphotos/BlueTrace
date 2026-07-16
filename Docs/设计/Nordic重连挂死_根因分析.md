# Nordic 重连服务发现挂死 · 根因分析（任务 #25）

> 2026-07-15 · 分支 `task/25-nordic-reconnect-hang` · 库版本锁定 **Kotlin-BLE-Library 2.0.0-beta03**
> 证据基础 = Maven Central 官方 sources jar（`client-core` / `client-android` / `client-core-android`）逐行核对 + 可执行测试 [`NordicOperationMutexPinTest`](../../app/src/test/java/io/bluetrace/data/android/NordicOperationMutexPinTest.kt)。
> 现象与两轮缓解的历史记录见 [`context/设备指令抽象层_执行笔记.md`](../context/设备指令抽象层_执行笔记.md) 2026-07-15 条。

## 终局（2026-07-16 后记，读本文先看这里）

本文主体写于 2026-07-15，其"已落规避"一节描述的 **sweep 方案次日被真机证伪并撤除**；后续三轮真机实验（sweep→代际→看门狗）与分层观测 fork 的完整过程见 CHANGELOG 2026-07-16 条与 [`../真机证据/nordic25_20260716/`](../真机证据/nordic25_20260716/)。最终事实与决定：

- **A/B 判决（同机同表同栈，hold 模式=连接后 12s 不取消）**：自写 `AndroidBleClient` 8 次请求 **0 挂死**；Nordic beta03 7 次请求 **3 挂死（43%）**，且唯一一次取消发生在全部挂死**之后**——排除"取消致回调不来"。四层分层观测（fork 加 `DISC_REQUEST`/`DISC_CALLBACK`/`DISC_CONSUMED` 三观测点）判决：Framework 从未拒绝（`started=false` 0 次）；**丢失发生在 `gatt.discoverServices()` 返回 true 与原生回调之间，且只在 Nordic 侧**；SharedFlow 事件管道清白（`subs>=1`、`tryEmit=true`、CALLBACK 与 CONSUMED 数量恒等）。时序相关性：3/3 挂死均为 **MTU 协商落在发现在飞期间**（库自身注释即警告并发 GATT 操作会丢回调）——只报相关性，未证机制。
- **用户拍板（2026-07-16）**：**自写 AndroidBleClient 继续默认；Nordic 保留为可选实验后端（不删除）；#24B（转默认）无限期暂停**。将来若重启转默认：补 Nordic 连接槽 + 上游/fork 修复 + 固定 MAC 50 次连接/断开与 OTA 回归。
- **上游 issue 已提交**：[NordicSemiconductor/Kotlin-BLE-Library#337](https://github.com/nordicsemi/Kotlin-BLE-Library/issues/337)（英文全量证据版；本仓 [`Nordic重连挂死_issue草稿.md`](Nordic重连挂死_issue草稿.md) 为其底稿）。
- **实验代码封存**：sweep（已证伪）、ManagerGen 代际、断开看门狗、观测 fork 接线全部封存在 `origin/task/25-nordic-reconnect-hang`（**不合 main**——代际方案有三条已确认的所有权竞态，修复方案=完整连接槽状态机，留待将来转默认时做）；观测 fork 本体在 `E:\_nordic_fork`（官方 tag beta03 + 3 处只读日志）。
- **连带发现（比库缺陷更深的自家架构病）**：**孤儿连接**——9 处连接入口全跑 `viewModelScope`，退屏腰斩 connect 后物理连接无人持有（设备停广播 → 五个页面同时看不见、断不开）。修法 = `BleConnectionCoordinator`（app 级连接事务宿主，与后端无关），提交 1 已入 main。

## 结论（前置）

**这是库缺陷，不是我们的用法问题**，且是**两个 bug 叠在同一段三行代码里**：

1. **触发器** —— 库把 `gatt.discoverServices()` 的 **`false` 返回值直接丢弃**。原生层拒绝启动发现时，库已把状态置成 `Discovering`，此后回调永不到达，状态**永久停在 Discovering**。~~这就是我们观测到的"服务发现挂 10s 超时"~~〔⚠️ 07-16 勘误：分层观测显示全部取样挂死 `started=true`（Framework 从未拒绝），**本次超时的实测触发不是 false 返回**，而是"返回 true 后原生回调丢失"（与 MTU 重叠强相关，见终局节）。false 丢弃仍是真实的库代码缺陷（issue #337 缺陷 1），只是与本次实测触发解耦〕。
2. **放大器** —— 那次挂住的发现，**持有全进程唯一的一把静态 Mutex 且永不释放**。库内其余一切 GATT 操作（读/写/MTU/PHY/连接优先级）都排在它后面 => 此后**任何设备的任何 GATT 操作永久挂起**。这就是"重试必挂""仅杀进程可解"。

一次普通的发现失败，被库的全局锁设计放大成**全进程蓝牙功能永久瘫痪**。

**我们此前的头号假设（"取消订阅的 CCC 写在全局锁上泄漏"）已被证伪**，这解释了为什么按该假设做的"断开换序"缓解**换序后仍复现**。

## 三个原假设的判决

| 原假设（W1.5 / 2026-07-15 备案） | 判决 | 依据 |
| --- | --- | --- |
| `OperationMutex` 是全局单例 | ✅ **坐实** | `object OperationMutex` + 单个 `private val lock`（`OperationMutex.kt`）；javap 见 `public static final INSTANCE` + `private static final Mutex lock`。注释原文即"只允许一个 BLE 操作并发执行" |
| 取消订阅触发的 CCC disable 写泄漏全局锁 | ❌ **推翻** | 写/读/订阅全走 `OperationMutex.withLock`，即 kotlinx `Mutex.withLock`，**自带 try/finally**；测试对照组证明取消后锁正常释放 |
| `serviceDiscoveryRequested` / `servicesDiscovered` 闩在主动断开后不复位 | ❌ **推翻** | `servicesDiscovered` 在 `invalidateServices()` 中复位（Peripheral.kt:357）；`serviceDiscoveryRequested` 是**故意**不复位，库注释写明"用于重连时让既有观察者拿到服务"（:365-369）——设计如此，非缺陷 |
| —— | ✅ **真凶（新）** | 服务发现是全库**唯一**用裸 `lock()`（无 finally）的地方，且**丢弃 `discoverServices()` 的 false 返回值** |

## 根因链

<div class="fig">
<svg viewBox="0 0 840 400" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>Nordic beta03 服务发现全局锁泄漏链</title>
<desc>服务发现在异步协程中用裸 lock 取得全进程唯一的 OperationMutex，随后调用返回 false 被丢弃的原生发现；三条解锁路径全部依赖永不到达的事件，锁被永久持有，导致全进程 GATT 操作排队。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:700;font-family:Consolas,monospace;}
.s{fill:var(--muted);font-size:10.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.h{fill:var(--muted);font-size:11px;font-weight:700;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.bad{fill:var(--code);stroke:var(--danger);stroke-width:1.5;}
.d{fill:var(--danger);font-size:12px;font-weight:700;font-family:Consolas,monospace;}
</style>
<defs>
<marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="var(--muted)"/></marker>
</defs>
<rect x="20" y="14" width="800" height="32" rx="3" class="bx"/>
<text x="420" y="35" text-anchor="middle" class="t">OperationMutex = object + 单个 static Mutex —— 全进程共用一把</text>

<rect x="20" y="72" width="360" height="46" rx="3" class="bx"/>
<text x="200" y="92" text-anchor="middle" class="t">discoverServices()  Peripheral.kt:495</text>
<text x="200" y="109" text-anchor="middle" class="s">servicesDiscovered = true（同步置位）</text>

<rect x="20" y="134" width="360" height="46" rx="3" class="bad"/>
<text x="200" y="154" text-anchor="middle" class="d">lock(ServicesChanged)  :504</text>
<text x="200" y="171" text-anchor="middle" class="s">裸 lock —— 全库唯一无 try/finally 处</text>

<rect x="20" y="196" width="360" height="46" rx="3" class="bad"/>
<text x="200" y="216" text-anchor="middle" class="d">impl.discoverServices()  :510</text>
<text x="200" y="233" text-anchor="middle" class="s">返回 false 被丢弃（NativeExecutor.kt:139）</text>

<rect x="20" y="258" width="360" height="46" rx="3" class="bad"/>
<text x="200" y="278" text-anchor="middle" class="d">_services = Discovering  :509</text>
<text x="200" y="295" text-anchor="middle" class="s">回调永不到达 —— 状态永久停此</text>

<line x1="200" y1="118" x2="200" y2="132" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#ar)"/>
<line x1="200" y1="180" x2="200" y2="194" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#ar)"/>
<line x1="200" y1="242" x2="200" y2="256" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#ar)"/>
<line x1="200" y1="304" x2="200" y2="326" stroke="var(--muted)" stroke-width="1.5" marker-end="url(#ar)"/>

<text x="430" y="64" class="h">解锁只有三条路 —— 全部依赖事件到达</text>
<rect x="430" y="72" width="390" height="46" rx="3" class="bx"/>
<text x="444" y="92" class="t">× ServicesDiscovered → unlock  :411</text>
<text x="444" y="109" class="s">回调不来</text>
<rect x="430" y="134" width="390" height="46" rx="3" class="bx"/>
<text x="444" y="154" class="t">× ServiceDiscoveryFailed → unlock  :466</text>
<text x="444" y="171" class="s">回调不来</text>
<rect x="430" y="196" width="390" height="46" rx="3" class="bx"/>
<text x="444" y="216" class="t">× invalidateServices() → unlock  :360</text>
<text x="444" y="233" class="s">依赖断链事件；原生栈已卡则同样不来</text>
<rect x="430" y="258" width="390" height="46" rx="3" class="bx"/>
<text x="444" y="278" class="t">其余 GATT 操作 withLock(owner=null)</text>
<text x="444" y="295" class="s">读 / 写 / MTU / PHY / 优先级 —— 全部排队</text>

<rect x="20" y="326" width="800" height="48" rx="3" class="bad"/>
<text x="420" y="346" text-anchor="middle" class="d">锁被永久持有 → 全进程任何设备的任何 GATT 操作永久挂起</text>
<text x="420" y="364" text-anchor="middle" class="s">与实测"重试必挂""仅杀进程可解"吻合</text>
</svg>
</div>

## 源码证据（beta03 sources jar，逐条可核）

**触发器 —— 返回值被丢弃**

- `client-core` `Peripheral.kt:201` 接口声明：`suspend fun discoverServices(uuids: List<Uuid>): Boolean`（**有返回值**）
- `client-android` `NativeExecutor.kt:139-142` 实现：`return gatt?.discoverServices() ?: false` —— 原生拒绝启动发现、或 gatt 已被 close 时返回 `false`
- `client-core` `Peripheral.kt:510` 调用点：`impl.discoverServices(uuids)` —— **返回值被丢弃，没有任何 if/check**

`BluetoothGatt.discoverServices()` 返回 false 是 Android 的既定契约（发现无法启动时）。库无视它 => 无回调 => 状态卡死。

**放大器 —— 裸 lock 无 finally**

```kotlin
// client-core Peripheral.kt:495-512（原文节选）
private fun discoverServices(uuids: List<Uuid>) {
    if (!servicesDiscovered) {
        servicesDiscovered = true          // 同步置位
        scope.launch {                     // 锁在异步协程里才拿
            try {
                OperationMutex.lock(ServicesChanged)   // 裸 lock —— 无 finally
            } catch (e: IllegalStateException) {
                logger?.warn(Layer.GATT, e)            // 吞掉后继续无锁裸奔
            }
            logger?.trace(Layer.GATT) { "Discovering services" }
            _services.update { RemoteServices.Discovering }
            impl.discoverServices(uuids)   // 返回值丢弃; 之后一切靠回调事件解锁
        }
    }
}
```

三条解锁路径：`ServicesDiscovered`(:411) / `ServiceDiscoveryFailed`(:466) / `invalidateServices()`(:354-364，经断链事件)。**全部依赖事件到达**，无超时、无 finally、无兜底。

**owner 语义形同虚设**

owner token 是 `GattEvent.kt:88` 的 `data object ServicesChanged` —— 全进程唯一实例，被所有 peripheral 共用。后果：
- 第二台设备发现时 `lock(ServicesChanged)` 因 owner 相同**直接抛 `IllegalStateException`**（kotlinx `Mutex` 契约），库 catch 后**不持锁继续执行发现**，串行保证在此失效；
- 任一设备的解锁事件可解掉另一台设备持有的锁。

**为什么能确定"锁不是挂 Discovering 的原因，而是后果"**

除服务发现外，全库 GATT 操作均用 `withLock(owner = null)`（`BaseRemoteCharacteristic.kt:173/223`、`core-android Peripheral.kt:479/522/599/636/701/743` 等）。我们真机日志中重连的 **MTU 协商成功（mtu=247）**，而 MTU 请求正是走 `withLock`（`core-android Peripheral.kt:599`）—— 这证明**那一刻锁是空闲的**。所以顺序是：发现失败 → 制造幽灵锁 → 后续全挂。

## 可执行证据

[`NordicOperationMutexPinTest`](../../app/src/test/java/io/bluetrace/data/android/NordicOperationMutexPinTest.kt)（app JVM 单测，**不需要真机/蓝牙**，3 例全绿）直接驱动库的真实 `OperationMutex`：

| 用例 | 钉死的行为 |
| --- | --- |
| `operationMutex_isProcessWide_notPerPeripheral` | 设备 B 的操作被设备 A 的锁挡住 => 锁是全进程一把，不是 per-peripheral/per-CentralManager |
| `rawLock_leaksForever_whenHolderCancelled` | 裸 `lock()` 的持有者被取消后**锁仍被持有**；对照组证明 `withLock` 路径取消后正常释放（=证伪 CCC 写假设） |
| `ownerToken_isSharedSingleton_soAnyoneCanUnlockAnyone` | 同 owner 二次 lock 抛 ISE（库吞掉后无锁裸奔）；任一方可解他方的锁 |

它同时是**跳闸丝**：将来升 Nordic 版本时若本测试失败，说明上游改了行为，届时可评估撤掉本项目侧规避。

## 尚未闭环：触发器的原生成因（需真机）

已证明的是"**一旦**原生 `discoverServices()` 返回 false，库必然永久卡死并瘫痪全进程"。但**原生层为何在主动断开后的重连中返回 false**，仍需真机对照实验确认。候选：

- 主动断开路径只调 `gatt.disconnect()` **不调 `gatt.close()`**（`NativeExecutor.kt:266-274`；`close()` 才两者都调，:276-295）—— client interface 未释放；
- `connect()` 会在 connectGatt 前 close 旧 gatt（:129-136），但**未等断链完成**即 close+重连，是 Android 上的已知竞态温床；
- 反复挂死后"force-stop 首连也挂、需开关蓝牙"表明**系统栈自身**已脏（进程级锁无法解释这一层）——指向原生 GATT client 资源泄漏。

**需真机的三组对照**（沿用既定计划）：① 不取消订阅；② 物理断链后取消订阅；③ 新建 Peripheral/CentralManager 后回连。

## ~~已落规避（本分支）~~〔⚠️ 2026-07-16 真机证伪已撤除，见文首「终局」——解锁放行的是陈旧且不可取消的 disconnect，它读到新一代的 gatt 并将其断开〕

[`NordicBleClient.sweepGhostServiceDiscoveryLock`](../../app/src/main/java/io/bluetrace/data/android/NordicBleClient.kt) —— teardown 后经宽限期（4s，让库自身 `invalidateServices` 先有机会）检查 `OperationMutex.holdsLock(ServicesChanged)`，仍持锁即判定幽灵并强制 `unlock`。

- **收益**：把"全进程永久瘫痪（仅开关蓝牙可解）"降级为"本次连接失败，可立即重试"。**不修触发器**，只拆放大器。
- **实现要点**：清扫**独立成协程**，不可串在 `disconnectBounded` 之后——后者实证可能永不返回，串行会让清扫永远等不到执行。
- **边界（已接受）**：若此刻恰有另一台设备的发现正常在飞，会提前解掉它的锁，使发现期间的 GATT 串行保证失效。本项目设备会话本就串行（DeviceSessionManager 单 Mutex、多设备 OTA 逐台），并发发现不成立；代价一侧是整个 App 蓝牙瘫痪到用户手动开关蓝牙，故接受。
- **状态**：⏳ **真机未验**，需随下条验收一并跑。

## 验收标准（真机，需用户在场）

1. 同一真机主动断开 → 原进程回连 **≥50 次**：`Discovering` 挂死 **0 次**、无需开关蓝牙；
2. OTA 后复位回连红线继续通过（既有 W1.6 记分卡不回归）；
3. 若规避生效但仍偶发单次发现失败（触发器未修），需确认失败可被上层 provisioner 的 60s 预算自然重试救回。

**在上述验收通过前，Nordic 不得转默认（#24B 的硬前置）。** 上游 v2 仍标 Beta，亦支持此判断。

## 上游 issue（已提交）

**[NordicSemiconductor/Kotlin-BLE-Library#337](https://github.com/nordicsemi/Kotlin-BLE-Library/issues/337)**（2026-07-16，英文全量证据版）。底稿留档：[`Nordic重连挂死_issue草稿.md`](Nordic重连挂死_issue草稿.md)。
