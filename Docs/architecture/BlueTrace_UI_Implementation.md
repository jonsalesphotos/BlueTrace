# BlueTrace UI 技术实施说明

> 本文档面向 Android 开发与项目排期，描述 BlueTrace 一期的导航状态机、页面与服务边界、Fake 到真实 BLE 的替换路径，以及前台服务、后台任务、文件系统的接入顺序。
>
> 页面范围、用户流程和产品规则见 [BlueTrace_UI_Design.md](BlueTrace_UI_Design.md)。

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
  PermissionGateScreen
  DeviceAssignmentScreen
  RoleScanScreen
  DataCollectionScreen
  SessionHistoryScreen
  SessionDetailScreen

viewmodel/
  PermissionGateViewModel
  DeviceAssignmentViewModel
  RoleScanViewModel
  DataCollectionViewModel
  SessionHistoryViewModel

domain/
  SessionController
  DeviceScanner
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
| 硬权限缺失 | PermissionGateScreen |
| 权限就绪且无运行 session | DeviceAssignmentScreen |
| 用户为某个 role 选设备 | RoleScanScreen(role) |
| SessionState.Running | DataCollectionScreen |
| SessionState.Ended | SessionSummary / SessionDetail |
| 查看历史 | SessionHistoryScreen |

重要约束：

- `DeviceAssignmentDraft` 只在开始采集前可编辑。
- 点 Start 后 draft 冻结为正式 `DeviceAssignment`。
- `RoleScanScreen(role)` 只返回一个候选设备，不直接启动采集。
- `DataCollectionScreen` 不持有 BLE 连接引用，只观察 `SessionState`。

---

## 4. 页面跳转先行阶段

第一阶段目标是构建完整 Navigation Graph 和页面状态分支。

需要先跑通：

```text
Splash / App launch
  → PermissionGate
  → DeviceAssignment
  → RoleScan(role)
  → DeviceAssignment
  → DataCollection
  → StopConfirm
  → SessionSummary
  → SessionDetail / SessionHistory
```

同时实现以下 UI 状态：

| 状态 | 验证点 |
| --- | --- |
| 权限缺失 | 权限页展示缺项，Start 禁用 |
| 设备未连接 | DeviceAssignment 可编辑，Start 禁用 |
| 单设备已连接 | Start 可用 |
| 双设备已连接 | DUT / REFERENCE 均显示在线 |
| 采集中 | 进入 DataCollection，返回键被拦截 |
| 单设备断线 | amber banner + reconnect 入口 |
| 全部失败 | red banner + stop / recover 入口 |
| 采集结束 | summary 显示文件、时长、设备 |

这阶段使用 `FakeSessionController`，不要接真实 BLE。

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

如果一期不做 GNSS 或上传，`location` / `dataSync` 可按实现范围收敛。

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
    session_20260521_153000_xxx/
      dut.rawdata
      reference.rawdata
      session_manifest.json
```

manifest 至少包含：

- sessionId
- startTime / endTime
- user
- project / sampling config
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

| 异常 | UI 表现 | 行为 |
| --- | --- | --- |
| 蓝牙关闭 | red banner | 暂停 / 失败，提供打开蓝牙入口 |
| 单设备断线 | amber banner | 允许 DUT 继续，REFERENCE 重连 |
| 全部设备失败 | red banner | Stop / Recover |
| 权限撤销 | PermissionGate | 清理不可用 session 或等待授权 |
| 存储失败 | red banner | Stop & Export / 清理空间 |

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
- 能过滤 DUT / REFERENCE。
- 能连接单设备并接收数据。
- 双设备断开其中一个时，UI 状态正确。

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
