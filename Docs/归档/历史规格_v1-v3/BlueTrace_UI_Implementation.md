# BlueTrace UI 技术实施说明

> **⚠️ V4 精简 UI 口径（2026-06-16）**：按 [V4 设计契约 §九](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 开发 —— 扁平设备连接（DUT ≤3 + 心率带参考 ≤1、无角色槽）· 传感器纯开关（透明传输 / 不控采样率）· 采集运行照竞品 Data Collection（简单实时数据区 + Start/End 标签 + 暂停 + 长按 2 秒结束）· 异常三态 + 运行日志 · 用户（原受试者）。波形⇄分包流 / 观测面板 / 运行中控制面板 / 采样率配置 / 打包页 **降后期或移除**；协议层高/低频为链路事实保留。下文旧 UI 描述以此为准。

> 本文档面向 Android 开发与项目排期，描述 BlueTrace 一期的导航状态机、页面与服务边界、Fake 到真实 BLE 的替换路径，以及前台服务、后台任务、文件系统的接入顺序。**本文只讲"怎么搭"，不重复描述界面视觉与各屏状态——各屏长什么样、有哪些状态以 [v4_android.html 原型](../prototypes/v4_android.html) 为准。**
>
> 页面范围、用户流程和产品规则见 [v4_android.html 原型](../prototypes/v4_android.html)（含每屏 UX 交互规格）与 [BlueTrace_Design_System.md](../product/BlueTrace_Design_System.md)；V4 收敛口径以 [V4 设计契约](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 为准（底部三 Tab）。需求见 [REQUIREMENTS.md](../../REQUIREMENTS.md)；UX_Flows 已归档 [legacy](../legacy/BlueTrace_UX_Flows.md)。

---

## 1. 实施策略

一期建议采用“导航与状态先行，服务能力后接”的策略。

```text
UI Navigation + Fake State
  → FakeSessionController
  → ForegroundService shell
  → BLE scan/connect
  → BLE data streaming
  → File persistence/export
  → Recovery + background work
```

核心原则：

- UI 层只观察状态和发送用户意图，不直接持有 BLE 连接对象。
- 真实 BLE 接入前，先用 Fake Controller 跑通页面跳转和状态分支。
- 前台服务接管“采集运行生命周期”，但不把页面导航逻辑写进 Service。
- 文件保存、导出、上传、日志这类能力通过独立 Repository / Worker 接入。
- 每个阶段都必须能独立验收，避免 UI、BLE、文件、系统权限问题混在一起调试。

---

## 2. 推荐模块边界

```text
ui/
  MainTabsScaffold        // 底部三 Tab：采集 / 数据 / 设置；子页与采集运行隐藏 Tab
  collect/
    CollectHubScreen      // 采集中枢：设备 / 受试者 / 模式 + 「开始采集」
    DeviceScanScreen      // 设备扫描/分配（搜索 + Service UUID + RSSI + 连接）
    SubjectSelectScreen   // 受试者选择
    SubjectEditScreen     // 受试者编辑（别名/性别/生日/身高/体重）
    SensorConfigScreen    // 传感器总控 + 设备端算法（无打包策略页，D-5）
    DataCollectionScreen  // 采集运行（复用 v3 外观）
    SessionSummaryScreen  // 结束摘要（D-6 文件夹）
  data/
    DataListScreen        // Raw Data File（按会话文件夹，D-6）
    SessionDetailScreen
  settings/
    SettingsScreen        // 环境与权限复查 / GNSS / 受试者管理 / 导出位置 / 关于
    PermissionGateScreen  // 首启 / 硬权限门控

viewmodel/
  CollectHubViewModel
  PermissionGateViewModel
  DeviceScanViewModel
  SubjectViewModel
  DataCollectionViewModel
  DataListViewModel

domain/
  SessionController
  DeviceScanner
  SubjectRepository       // 受试者本地 CRUD（V4 一期）
  SessionRepository
  ExportRepository
  PermissionRepository

service/
  BlueTraceForegroundService
  BleSessionEngine
  BleConnectionManager

worker/
  UploadWorker
  LogUploadWorker
  CleanupWorker
```

职责划分：

| 模块 | 职责 |
| --- | --- |
| Screen | 渲染状态、收集用户操作 |
| ViewModel | 组合页面状态、调用 domain 接口 |
| SessionController | 管理采集会话状态机 |
| DeviceScanner | 扫描和筛选 BLE 设备 |
| ForegroundService | 维持采集期间的系统级生命周期和通知 |
| BleSessionEngine | 真实 BLE 连接、订阅、数据接收 |
| Repository | session 元信息、文件、导出、权限快照 |
| Worker | 可延迟的上传、补传、清理、日志任务 |

---

## 3. 导航与状态模型

页面跳转不建议只靠“点击跳页面”维护，而应由 App 状态驱动。

建议定义核心状态：

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data class Preparing(val draft: DeviceAssignmentDraft) : SessionState
    data class Running(
        val sessionId: String,
        val startedAt: Instant,
        val roles: Map<DeviceRole, RoleRuntimeState>,
        val file: ActiveFileState,
        val streamMode: StreamMode,
    ) : SessionState
    data class Ending(val sessionId: String) : SessionState
    data class Ended(val summary: SessionSummary) : SessionState
    data class Failed(val reason: SessionFailure) : SessionState
}
```

设备角色状态：

```kotlin
enum class DeviceRole { DUT, REFERENCE }

sealed interface RoleRuntimeState {
    data object NotAssigned : RoleRuntimeState
    data class Connecting(val peripheralId: String) : RoleRuntimeState
    data class Collecting(val peripheralId: String, val rssi: Int?) : RoleRuntimeState
    data class Reconnecting(val peripheralId: String, val attempt: Int) : RoleRuntimeState
    data class Failed(val peripheralId: String?, val reason: RoleFailure) : RoleRuntimeState
}
```

导航规则：

| 条件 | 目标页面 |
| --- | --- |
| 首次启动 或 启动静默检查发现硬权限缺失 | PermissionGateScreen（门控，无返回） |
| 否则（非首启且静默通过） | MainTabsScaffold → 采集 Tab（落地中枢） |
| 采集 Tab → 设备入口（环境就绪） | DeviceScanScreen（隐藏底部 Tab） |
| 采集 Tab → 设备入口（环境不完整） | PermissionGateScreen（手动，可返回） |
| 采集 Tab → 受试者入口 | SubjectSelectScreen / SubjectEditScreen |
| 设置 Tab → 环境与权限检查 | PermissionGateScreen（手动，可返回） |
| SessionState.Running | DataCollectionScreen（全屏，隐藏 Tab，返回≠停止） |
| SessionState.Ended | SessionSummaryScreen / SessionDetailScreen |
| 查看数据 | 数据 Tab → DataListScreen（会话文件夹） |

> **导航修订（V4 · 2026-06-16）**：落地改为 **MainTabsScaffold 的采集 Tab**；底部三 Tab（采集/数据/设置）为唯一主 IA，进入任一子页或采集运行**隐藏底部 Tab**。启动先做**静默环境检查**，仅"首启"或"硬权限缺失"才门控弹 PermissionGate，其余直接进采集 Tab。设备扫描不再开屏即扫，仅由采集 Tab 设备入口按需进入。硬权限阻断守在"进入采集/扫描"入口。首启标记用 `AppPreferences`（SharedPreferences）持久化。

重要约束：

- `DeviceAssignmentDraft` 只在开始采集前可编辑。
- 点 Start 后 draft 冻结为正式 `DeviceAssignment`。
- `RoleScanScreen(role)` 只返回一个候选设备，不直接启动采集。
- `DataCollectionScreen` 不持有 BLE 连接引用，只观察 `SessionState`。

---

## 4. 页面跳转先行阶段

第一阶段目标：用 `FakeSessionController`（不接真实 BLE）跑通完整 Navigation Graph 与各屏状态分支。

> **页面流、各屏视觉与状态以 [v4_android.html 原型](../prototypes/v4_android.html)（当前 · 含每屏 UX 交互规格）+ [V4 设计契约 §九/§十](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 为准**（UX_Flows 已归档 [legacy](../legacy/BlueTrace_UX_Flows.md)），本文不再重复描述（导航骨架见 §3 导航规则表，已 V4 对齐：底部三 Tab + 子页/采集运行隐藏 Tab）。

验收（用 Fake 数据驱动，对照原型）：

- 跑通整条导航：启动 → 采集 Tab → 设备连接（扁平列表 · DUT ≤3 + 参考心率带 ≤1）→ 用户 → 采集模式 → 数据采集 → 长按 2 秒结束 → 结束摘要；
- 用 Fake 注入会话三态 **采集中 / 暂停·重连 / 已停止** 验证 UI 切换正确（无法处理的错误进运行日志、不打断）；
- 进入任一子页与采集运行时**隐藏底部 Tab**，返回键 ≠ 停止；
- 各屏长什么样、有哪些组件不在此列举——对照原型即可。

---

## 5. Fake Controller 契约

Fake Controller 不是临时乱写的 mock，而是后续真实实现的接口替身。

建议接口：

```kotlin
interface SessionController {
    val state: StateFlow<SessionState>

    suspend fun start(assignment: DeviceAssignment, config: SessionConfig)
    suspend fun stop(reason: StopReason)
    suspend fun requestReconnect(role: DeviceRole)
    suspend fun sendCommand(role: DeviceRole, payload: ByteArray)
}
```

Fake 实现需要支持：

- start 后进入 `Running`
- 定时增加 elapsed time
- 生成模拟 Hex 数据
- 模拟 DUT / REFERENCE 断线
- 模拟 reconnect 成功 / 失败
- stop 后进入 `Ended`
- 生成 `SessionSummary`

这样 UI 流程、异常 banner、停止确认、结束摘要都能在无硬件环境下验收。

---

## 6. 前台服务接入

采集运行时必须由前台服务承载系统生命周期。

建议结构：

```text
ViewModel.start()
  → SessionController.start()
  → Context.startForegroundService(...)
  → BlueTraceForegroundService.startForeground(notification)
  → BleSessionEngine.start(assignment, config)
  → SessionController.state 更新
```

`BlueTraceForegroundService` 职责：

- 创建和更新采集通知。
- 保证采集期间进程优先级。
- 承载 BLE session 生命周期。
- 处理通知上的 Open / Stop action。
- 在 stop / failure 后清理通知和资源。

不建议放进 Service 的职责：

- 页面跳转决策。
- UI 文案组合。
- 文件列表展示。
- 用户编辑 draft。

Manifest 需要预留前台服务类型。具体类型按实际能力确认：

```xml
<service
    android:name=".service.BlueTraceForegroundService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|location|dataSync" />
```

GNSS 为一期正式功能（F-GPS-1）→ 保留 `location`；`dataSync`（上传）属二期，可收敛。

---

## 7. BLE 接入顺序

BLE 建议在前台服务 shell 跑通后接入。

推荐顺序：

1. `DeviceScanner` 接入真实 BLE scan。
2. `RoleScanScreen` 展示真实扫描结果。
3. 单 DUT 连接和订阅数据。
4. DUT + REFERENCE 双连接。
5. 数据流写入 `SessionState` 采样缓存。
6. 断线检测和 reconnect。
7. 采集恢复和已知 MAC 定向重扫。

BLE 层建议暴露领域模型，不把平台对象泄漏到 UI：

```kotlin
data class ScannedPeripheral(
    val id: String,
    val name: String?,
    val address: String?,
    val rssi: Int?,
    val serviceUuids: List<String>,
    val roleHint: DeviceRole?,
)
```

```kotlin
interface DeviceScanner {
    fun scan(filter: ScanFilter): Flow<List<ScannedPeripheral>>
    suspend fun stop()
}
```

注意事项：

- 扫描权限、蓝牙开关、定位服务状态由 `PermissionRepository` 统一快照。
- UI 不直接处理 Android `BluetoothDevice` / GATT 对象。
- 双设备采集时，单个 role 失败不能直接终止整个 session，除非产品规则要求。

---

## 8. 文件保存与导出

文件层建议独立为 `SessionRepository` 和 `ExportRepository`。

采集中写入：

```text
BleSessionEngine
  → SampleFrame / RawPacket
  → SessionRepository.append(...)
  → ActiveFileState 更新大小、最后写入时间
```

采集结束：

```text
stop()
  → close rawdata writer
  → write session_manifest.json
  → generate SessionSummary
  → expose to SessionHistory
```

建议 session 目录：

```text
files/
  sessions/
    Wear_shb_20260521_153000_de54/          // 一会话一文件夹（D-6），名 = 模式_受试者_时间_设备
      raw/  dut.hexlog  reference.hexlog     // 原始 HEX 行日志 = source of truth（<epochMs>: HEX，可重放，后期 zstd）
      csv/  ppg.csv ecg.csv imu.csv mag.csv  // 解码后按模块 CSV
      csv/  ppg_acc.csv                       // 组合包兼容 CSV（汇顶 PPG+ACC 等，1→N 文件）
      gps.csv                                 // 本机 GPS（若开启 GNSS，F-GPS-1）
      session_manifest.json
```

> **存储模型（D-6）**：每会话一个文件夹；**原始 HEX 行日志为主体（source of truth）**，解码 CSV / 组合包兼容 CSV 为显示与分析派生物，即便解码逻辑或 schema 变更也能回放重解。

manifest 至少包含：

- sessionId
- startTime / endTime
- **subject**（别名/性别/生日/身高/体重）
- **mode**（Wear / Unwear）
- sampling config（启用传感器/采样率/算法；**不含 BLE 链路参数**，D-5）
- device roles
- file list
- app version
- permission / GNSS snapshot
- stop reason
- error summary

导出到 Downloads 时，不要暴露私有目录路径给用户作为主要信息，只展示用户可访问目录，例如：

```text
Downloads/BlueTrace/session_20260521_153000.zip
```

---

## 9. 后台任务边界

后台任务用于可延迟、可重试、不要求实时的工作。

适合 WorkManager：

- 上传历史 session。
- 上传日志。
- 网络恢复后补传。
- 清理过期缓存。
- 大文件压缩导出。

不适合普通后台任务：

- 正在进行的 BLE 采集主链路。
- 需要实时维持连接的 session。
- 用户明确正在观察的数据流。

规则：

```text
实时采集 = ForegroundService
延迟上传 / 清理 / 补传 = WorkManager
```

如果后续 Worker 需要长时间运行并展示进度，再按 Android 版本要求补充 foreground worker 类型声明。

---

## 10. 异常恢复策略

一期至少覆盖显式异常提示：

| 异常 | UI 表现（V4 精简三态 + 运行日志） | 行为 |
| --- | --- | --- |
| 连接断开（蓝牙关 / 设备走远 / 通信报错） | "暂停 · 重连中" | 暂停 → 自动重连；重连后原始 HEX 日志续写续解析，不丢数据 |
| 坏包 / CRC / 解码失败 / 未知类型 | 进运行日志（不打断采集） | 丢弃该包，原始 HEX 仍落盘可重放，不弹分级 Banner |
| 存储不足（写入失败） | "已停止" | 唯一"已停止"语义=不可继续；安全保存后引导清理 / 导出 |
| 权限撤销 | 回权限门控 | 权限全局硬门；清理不可用 session / 等待重授权 |

恢复能力分级：

| 等级 | 能力 |
| --- | --- |
| P0 | 明确提示异常，不静默失败 |
| P1 | 同进程内 reconnect |
| P2 | App 切后台后返回仍能看到 session |
| P3 | 进程被杀后通过 pending session 尝试恢复 |
| P4 | 开机 / 系统恢复通知引导用户重扫设备 |

一期建议做到 P0-P2，P3/P4 可作为后续增强。

---

## 11. 验收检查表

UI / Navigation：

- 权限、设备、扫描、采集、结束、文件页面均可达。
- Fake 状态能覆盖 running / ended / failed。
- 采集中按返回键不会直接停止。
- 非采集页面能显示 running session banner。

ForegroundService：

- Start 后通知出现。
- 切后台后通知仍存在。
- 通知 Open action 回到采集页。
- Stop 后通知消失且资源释放。

BLE：

- 能扫描真实设备。
- 能过滤自己的 DUT（Service UUID）+ 标准心率带参考（HRS 0x180D）。
- 能连接单设备并接收数据。
- 断开其中一台时，UI 状态正确（该台转"暂停·重连"、连接计数更新，其余继续）。

File：

- rawdata 可持续写入。
- stop 后 manifest 完整生成。
- history 能读取 session summary。
- export 到 Downloads 成功。
- 删除前有确认。

Recovery：

- 蓝牙关闭、权限撤销、存储失败均有明确提示。
- App 从后台返回时状态一致。
- Service 异常停止后不会留下错误通知。
