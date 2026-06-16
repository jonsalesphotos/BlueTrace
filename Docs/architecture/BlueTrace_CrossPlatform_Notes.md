# BlueTrace 跨平台技术说明（Android + iOS）

> **⚠️ V4 精简 UI 口径（2026-06-16）**：iOS 同样按 [V4 设计契约 §九](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 的精简 UI 对齐 —— 扁平设备连接（DUT ≤3 + 参考 ≤1）· 纯开关采集类型 · Creek 式数据采集（简单实时数据区 + Start/End + 暂停 + 长按结束）· 异常三态 + 运行日志 · 用户。波形/分包流/观测/控制面板/采样率为后期。

> 主架构（Android 详版）见 [BlueTrace_Architecture.md](BlueTrace_Architecture.md)。本文聚焦**把 BlueTrace 扩到 iOS 时真正分叉的技术点**，以及跨平台代码组织建议。产品/交互层的平台差异见 [../product/BlueTrace_UX_Flows.md §6](../product/BlueTrace_UX_Flows.md)。

---

## 1. 技术路线选型（先定这个）

| 方案 | 共享什么 | 优点 | 代价 | 适配度 |
| --- | --- | --- | --- | --- |
| **原生双端**（Kotlin + Swift） | 不共享代码，只共享设计/协议 | 各端最佳性能与原生体验；BLE/后台直达系统 API | 两套实现、两份维护 | 高，但人力翻倍 |
| **KMP + 各端原生 UI**（推荐） | 共享领域层（协议解析/打包/会话编排/文件），UI 各端原生（Compose / SwiftUI） | 复杂且易错的**协议与解析层只写一遍**；UI 仍原生 | 需搭 KMP 工程；BLE 仍各端适配 | **高** |
| Compose Multiplatform | 共享领域 + 大部分 UI | UI 也大量复用 | iOS Compose 生态较新；BLE 仍需 expect/actual | 中 |
| Flutter / RN | 共享几乎全部 | 一套 UI 跑两端 | 高频多路 BLE + 后台采集在跨端框架上风险高；偏离已有 Kotlin-BLE 架构 | 中低（本项目不推荐） |

**建议**：**KMP 共享领域层 + 各端原生 BLE/UI**。
- 共享（commonMain）：`ParseResult` / `SensorSample` / 帧解析（高频批包拆包、低频组帧拆分）/ 打包策略计算 / 会话状态机 / CSV 行格式 / manifest 模型。这些是纯逻辑、最该一次写对。
- 各端（androidMain / iosMain）：BLE 栈、权限、后台、文件落盘——用 `expect/actual` 暴露统一接口。

## 2. BLE 栈对照

| 维度 | Android | iOS |
| --- | --- | --- |
| 库 | Kotlin-BLE-Library v2.0（CentralManager/Peripheral/Flow） | **Core Bluetooth**（`CBCentralManager` / `CBPeripheral`） |
| 角色 | Central | Central |
| 设备标识 | MAC 地址（可用 `getPeripheralById`） | **不暴露 MAC**，用系统分配的 `identifier`(UUID)；跨重启可能变 |
| 扫描过滤 | ScanFilter / Service UUID | `scanForPeripherals(withServices:)` |
| MTU | 协商，可请求最大值 | 系统自动协商，用 `maximumWriteValueLength(for:)` 查 |
| 通知订阅 | characteristic.subscribe()（Flow） | `setNotifyValue(true)` + delegate 回调 |

> **设备恢复差异**：iOS 不给 MAC，靠 `identifier`，且该值与系统层绑定关系有关；未绑定设备跨重启可能拿不到同一 `identifier`，恢复更依赖**按 Service UUID 重扫**。Android 的"持久化 MAC"在 iOS 要换成"持久化 identifier + Service UUID 兜底"。

## 3. 后台采集（最大分叉）

### 3.1 Android（已在主架构 §13 详述）
- 前台服务 `foregroundServiceType="connectedDevice"` + `PARTIAL_WAKE_LOCK`。
- 必须由前台用户动作启动（避免 `ForegroundServiceStartNotAllowedException`）。
- 进程被杀靠 `CollectionResumer` + 通知引导用户回 App。

### 3.2 iOS
- **没有前台服务/WakeLock 概念**。后台靠 **Background Modes → `bluetooth-central`** 能力。
- **State Preservation & Restoration**：创建 `CBCentralManager` 时带 `CBCentralManagerOptionRestoreIdentifierKey`；App 被系统终止后，因 BLE 事件被拉起时，系统通过 `centralManager(_:willRestoreState:)` 把之前连接/正在连接的 peripheral 交还——这是 iOS 版"进程恢复"的核心。
- **后台限制**：后台扫描必须带 Service UUID（不能通配）、被合并节流；后台不能更新常驻通知（iOS 用系统状态栏蓝条提示"App 正在后台使用蓝牙"）。
- **本地通知**仅用于提示用户（如"采集已恢复"/"采集异常"），不能像 Android 那样靠通知"保活"。

### 3.3 两端统一的产品目标
锁屏/切后台采集不中断；进程被杀后能恢复——**目标一致，机制不同**。领域层（会话状态、续写同一文件、对齐锚点）共享；保活/恢复触发各端 `actual` 实现。

## 4. 权限模型对照

| 能力 | Android | iOS（Info.plist 用途文案） |
| --- | --- | --- |
| 蓝牙 | `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`（运行时） | `NSBluetoothAlwaysUsageDescription` |
| 定位（扫描依赖） | `ACCESS_FINE_LOCATION`（按 neverForLocation 决定） | iOS 蓝牙扫描**不需要定位权限**（与 Android 不同） |
| 通知 | `POST_NOTIFICATIONS`（API 33+） | `UNUserNotificationCenter` 授权 |
| 后台 | 前台服务相关权限 | Background Modes 勾选 `bluetooth-central` |

> iOS 权限是**一次性弹窗**，必须在 Info.plist 写清楚用途文案，否则上架被拒。BlueTrace 的"用途说明层"（UX 文档屏 P0）在 iOS 上尤其重要。

## 5. 文件与导出对照

| 维度 | Android | iOS |
| --- | --- | --- |
| 私有存储 | `getExternalFilesDir(...)` | App 沙盒 `Documents/` |
| 导出公共可见 | MediaStore / 公共 Downloads | 写入 `Documents/` + `UIDocumentPicker` / 分享 Sheet；或开启"支持文件 App 共享"让用户在 **Files App** 看到 |
| 用户找文件 | 文件管理器 / Downloads | **Files App**（需 `UISupportsDocumentBrowser` / `LSSupportsOpeningDocumentsInPlace`） |

CSV 行格式、目录命名（`session_YYYYMMDD_HHmmss/` + 每路 CSV + manifest）两端共享。

## 6. 共享 vs 平台特定（代码分层建议）

```text
commonMain（KMP 共享 · 纯 Kotlin · 可单测）
  ├─ protocol/        帧定义、高频批包拆包、低频组帧拆分、CRC 校验
  ├─ model/           SensorSample、DeviceRole、ParseResult、Manifest
  ├─ session/         会话状态机、按角色编排（与平台 BLE 解耦的接口）
  ├─ packing/         批包大小/组帧周期 → 带宽预估
  └─ csv/             行格式、表头

androidMain（actual）
  ├─ ble/   Kotlin-BLE-Library v2.0 适配
  ├─ background/  ForegroundService + WakeLock
  ├─ permission/  运行时权限
  └─ storage/  MediaStore 导出

iosMain（actual）
  ├─ ble/   Core Bluetooth 适配 + State Restoration
  ├─ background/  bluetooth-central + willRestoreState
  ├─ permission/  Info.plist + 授权
  └─ storage/  Documents + Files 共享
```

**判断某段代码放哪**：碰系统 API（BLE/权限/后台/文件）→ 平台特定；只处理字节与状态 → 共享。共享层越厚，两端越不容易出现"行为不一致"的 bug。

## 7. 落地里程碑（在主架构一期基础上叠加 iOS）

| 阶段 | 目标 |
| --- | --- |
| X0 | 抽出 commonMain 领域层（协议/解析/打包/会话/CSV），JVM 单测覆盖 |
| X1 | Android 端接 actual（沿用主架构 §1–§17） |
| X2 | iOS 端接 Core Bluetooth actual：扫描/连接/订阅/写指令打通 |
| X3 | iOS 后台模式 + State Restoration；进程恢复对齐 |
| X4 | iOS 文件导出（Files App 共享）；双端走查体验一致性 |
