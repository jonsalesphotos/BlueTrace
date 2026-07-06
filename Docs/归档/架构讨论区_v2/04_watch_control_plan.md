# 04 · 手表控制界面规划（可重构 · KMP 就绪）

> 状态：规划 v1（2026-07-02）。「手表」= 佩戴式 DUT 设备（BLE 对端），本文规划的是 **App 内控制手表的界面与分层**，不是 Wear OS 端应用。
> 依赖：02（事件模型/命令面）、03（Storage/TimeSync 命令）；协议命令原语 v0.1 已有（`Command`/`Ack`，SPEC §4.8）。

## 1. 定位与范围

设置 Tab 已有占位入口：「**设备维护（DUT）**——对时/用户信息/固件日志/OTA · 后期」（实机可见，需连接 DUT 才启用）。本规划把它扩展为**设备控制台（Device Console）**，承载：

| 能力块 | 协议支撑 | 期次 |
|--------|----------|------|
| A. 能力/状态总览 | `QueryCapability`→0x11、`QueryState`→0x12 | 一期 |
| B. 传感器控制：开关/采样率 | `SetSensorEnable`、`SetSampleRate`（毫赫兹） | 一期 |
| C. 打包旋钮 | `SetPacking(batch_samples, frame_period_ms)` | 一期（高级区） |
| D. 设备端算法 | `SelectAlgorithm`、`AlgoResult` 流展示 | 一期（只读）→ 二期（配置） |
| E. 对时 | `TimeSyncPing/Pong`（03 §4） | 一期 |
| F. 存储管理 | `QueryStorage`/`DeleteSession`/`FormatStorage`（03） | 一期（随离线采集） |
| G. 写用户信息 / 固件日志 | 需新增 Command（协议 v1.1 再议） | 二期 |
| H. OTA | 需专门传输通道（可复用 03 块传输反向） | 二期 |

**明确不做**：采集会话的启停不进控制台（属采集 Tab 主流程）；控制台是「维护/调参」场景。

## 2. 界面规划（沿 V4 设计语言）

```
设置 Tab ▸ 设备维护（未连接=灰显，副标题提示"先连接设备"）
└─ 设备控制台（子页，隐藏底部 Tab，App Bar 返回箭头 —— 与其它设置子页同构）
   ├─ 顶部：设备卡（名称/MAC/固件版本/RSSI/连接态 pill；多设备时横向切换 chips）
   ├─ 状态区：对时状态（偏移/RTT/上次对时时间 + 「立即对时」）、存储占用条（复用 StorageBreakdown 组件）
   ├─ 传感器区：每路 SensorCap 一行 = 开关 + 采样率下拉（词表来自 supported_rates_mhz，显示 Hz 保留小数）
   │            行尾实际生效值（DeviceState.current_rate_mhz，含被设备夹紧后的真实值 ≠ 请求值时琥珀标注）
   ├─ 高级区（默认折叠）：SetPacking 两个旋钮 + 恢复默认；设备端算法多选（能力表驱动）
   └─ 危险区：清空设备存储（FormatStorage，红色 + 二次确认对话框）
```

交互纪律（继承 V4 已验证的模式）：

- **能力表驱动 UI**：界面完全由 `DeviceCapability` 渲染，无硬编码传感器列表——新固件加传感器，App 不发版自动出现（与 scenes.json 词表驱动同思路）；
- **请求-确认-对账**三拍：控件立即进「等待」态（cmd_id 挂起）→ `Ack` 到达定格 → 随后 `QueryState` 对账刷新实际生效值；Ack 超时 3s → 控件回滚 + Snackbar 重试；
- 采集运行中进入控制台 → 传感器/打包区**只读锁定**（避免边采边改毁数据一致性），顶部提示条说明。

## 3. 分层设计（重构友好 + KMP）

**逻辑全部下沉 `shared` commonMain，Android 只留 Compose 薄壳** —— 与现有 `DefaultSessionController` 模式完全一致，iOS 后期 SwiftUI 直接对接同一控制器。

```
shared/src/commonMain/kotlin/io/bluetrace/shared/control/
├── DeviceCommandBus.kt      # 命令收发核心：send(cmd)→Flow 挂起等 Ack(cmd_id 配对)+超时；串行队列
├── DeviceControlSession.kt  # 每设备控制会话：capability/state/timeSync/storage 的 StateFlow 快照
│                            #   refresh() = QueryCapability+QueryState；命令乐观更新+对账回滚
├── ControlModels.kt         # ControlUiState(sealed)/SensorRow/PendingCommand 等纯模型
└── (依赖 02 的 ProtocolEvent.CommandAck/Capability/State —— 事件来源与采集共流水线)

app/src/main/java/io/bluetrace/
├── ui/screen/console/DeviceConsoleScreen.kt   # Compose 薄壳：collectAsState + 发意图
└── viewmodel/DeviceConsoleViewModel.kt        # 生命周期壳：持有 DeviceControlSession
```

关键接口草案：

```kotlin
class DeviceCommandBus(
    private val writer: suspend (ByteArray) -> Unit,   // BleClient 写通道（02 CommandEncoder 编码后）
    private val acks: Flow<ProtocolEvent.CommandAck>,  // 来自 DeviceParserHost 事件流
    private val timeoutMs: Long = 3_000,
) {
    /** 串行下发；挂起直到 Ack 或超时。cmd_id 自增配对。 */
    suspend fun send(command: DeviceCommand): AckResult
}

class DeviceControlSession(deviceId: String, bus: DeviceCommandBus, events: Flow<ProtocolEvent>) {
    val state: StateFlow<ConsoleState>       // Loading / Ready(capability, deviceState, pending) / Error
    suspend fun refresh()                    // QueryCapability + QueryState
    suspend fun setSensor(id: Int, enabled: Boolean)
    suspend fun setRate(id: Int, rateMilliHz: Int)
    suspend fun syncTime(): TimeSyncResult   // 3 次取 rtt 最小（03 §4）
    suspend fun formatStorage()
}
```

**为什么这就是「适合重构」的切法**：
- `DeviceCommandBus` 不认识 UI，也不认识具体协议（靠 02 的 `CommandEncoder` 注入）——S7 若也要控制台，换 Profile 即得；
- 乐观更新/回滚/对账全在 commonMain，**commonTest 用 Fake bus 全覆盖**（Ack 超时、乱序 Ack、夹紧值对账三类用例），UI 层无逻辑可错；
- Compose 薄壳无状态提升负担，后续换 M3 Adaptive 双栏（平板）只动 UI。

## 4. 里程碑（与协议冻结解耦）

| 里程碑 | 内容 | 依赖 | 验收 |
|--------|------|------|------|
| **W1** | `control/` 包 + Fake 回放测试（Mock Profile 注入假 Ack/Capability） | 02 的 R1–R3 | commonTest 全绿；无 UI |
| **W2** | 控制台只读版：能力/状态/存储/对时展示 + 刷新 | W1 + 协议 0x11/0x12 固件可用 | 真机连 DUT 显示真实能力表 |
| **W3** | 传感器开关/采样率/打包写路径（三拍交互） | W2 + Ack 语义冻结 | 边界：夹紧值琥珀提示、超时回滚 |
| **W4** | 存储管理 + FormatStorage 危险区（随 03 离线采集一起联调） | 03 落地 | 删除/清空后 SessionCatalog 一致 |
| W5（二期） | 写用户信息/固件日志/OTA | 协议 v1.1 | — |

W1 现在就能开工（纯 shared + 测试，零硬件依赖），W2 起随协议冻结节奏推进。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| 固件 Ack 语义不全（status 错误码未定义） | W2 前与固件端冻结错误码表；未知码一律按失败回滚展示 detail |
| 采集中修改配置毁一致性 | §2 只读锁定；协议层若仍收到写命令，固件应拒绝并回非 0 status |
| 多设备并发控制台 | 一期限制单设备进入（切换即离开）；`DeviceControlSession` 天然按设备隔离，后续放开无架构债 |
| iOS 迁移时 SwiftUI 状态桥接 | `ConsoleState` 保持纯 data class + sealed，SKIE/KMP-NativeCoroutines 直接桥 |
