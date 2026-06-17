# BlueTrace Android BLE 数据采集 App 架构设计

> **⚠️ V4 精简 UI 口径（2026-06-16 · 按此开发，详见 [V4 设计契约 §九](../reviews/BlueTrace_V4_设计契约_2026-06-16.md)）**：扁平设备连接（**DUT ≤3 + 心率带参考 ≤1**、单击连接/断开、无角色槽）· 传感器**纯开关**（透明传输、不控采样率、不外露高/低频）· 采集运行照竞品 Data Collection（设备卡 + 采集类型勾选 + 简单实时数据区 + Start/End 标签 + 暂停 + 长按 2 秒结束）· 异常**三态**（采集中/暂停重连/已停止）+ 运行日志（不分级、不单路降级）· **用户**（原"受试者"）· 权限全局门。**降后期/移除**：波形⇄分包流双视图、只读观测面板、运行中控制面板/指令日志、采样率配置、打包策略页。**注**：协议层「高频批包/低频组帧/分片重组」仍为链路事实（见 [Protocol.md](BlueTrace_Protocol.md)，保留），仅 UI 不暴露。下文若有旧 UI 描述（波形/分包流屏、采样率配置、控制面板、角色化等），**以本标注与契约 §九 为准**。

> 本文档基于 **Kotlin-BLE-Library v2.0**（本仓库 `version/2.0` 分支：`CentralManager` + `Peripheral` + 协程/Flow 风格 API），目标 Android 平台：
>
> - `minSdk = 29`（Android 10，D-7）
> - `compileSdk = 35` / `targetSdk = 35`（Android 15）
> - Kotlin 2.x、Jetpack Compose（Compose BOM）、Material 3
>
> 参考实现：本仓库 `sample/src/main/.../scanner/ScannerViewModel.kt` 与 `scanner/profile/LedButtonProfile.kt`，以及社区 [Android-nRF-Blinky](https://github.com/NordicSemiconductor/Android-nRF-Blinky) 的 Profile 抽象方式。

> **⚠️ V4 / D-7 对齐说明（2026-06-16，最新口径，覆盖下文历史表述）**：本文若与 [V4 设计契约](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 冲突，以契约为准。
> - **minSdk = 29**（D-7）：下文所有 **API 24–28 兼容分支、`coreLibraryDesugaring`（java.time）、`WRITE_EXTERNAL_STORAGE`(maxSdk≤28) 导出降级**均**作废**——导出统一走 MediaStore（API 29+），历史代码段仅作参考。
> - **信息架构 = V4 底部三 Tab**（采集 / 数据 / 设置）；v3 向导式仅 legacy 参考；采集运行与子页隐藏 Tab。
> - **文件模型 = D-6**：每会话一文件夹，**原始 HEX 行日志为 source of truth** + 解码 CSV + 组合包兼容 CSV + manifest（下文"按角色分 CSV"按 D-6 读）。
> - **manifest 增 `subject`（别名/性别/生日/身高/体重）+ `mode`（Wear/Unwear）**。
> - **GNSS 一期正式功能**（F-GPS-1）：本机 GPS 作一路数据源写入会话独立 CSV；FGS 增 `location` 类型 + while-in-use 定位。
> - **App 不配置 BLE 链路参数**（D-5），无带宽硬闸（D-9）；带宽/丢包/CRC 仅运行时只读观测。

---

## 0. 审查结论

整体方案方向合理，使用 Kotlin-BLE-Library v2.0 的 `CentralManager` / `Peripheral` / Flow API 做 Central 侧采集是可执行的；Profile、Parser、Pipeline、Sink 分层也能把 BLE 协议、数据解析和文件落盘解耦，后续替换真实 DUT 协议的改动面可控。

需要收敛的风险点有四个：

1. **设备数量按"1..N 可变"设计，单设备是常见路径，多设备（DUT + REFERENCE，乃至更多角色）是架构原生支持的能力**。统一通过 `MultiDeviceController.start(DeviceAssignment)` 进入会话，`DeviceAssignment` 内部是 `Map<DeviceRole, Peripheral>`，允许只填一个角色（如只接 DUT 做单设备闭环），也允许同时填多个角色做对照采集。`SessionState.Running.byRole` 在单角色时退化为一个键值对，UI 仅渲染一个设备卡片，**不需要"单设备"和"多设备"两套代码路径**。
2. **Android 12+ 扫描权限要在"是否用于位置推断"上二选一**。本方案选择"不声明 `neverForLocation`"，因此 `ACCESS_FINE_LOCATION` 不能只写 `maxSdkVersion="30"`；如果产品确认扫描结果绝不用于位置推断，才反过来给 `BLUETOOTH_SCAN` 加 `neverForLocation` 并把定位权限限制到 API 30。
3. **Android 14+ `connectedDevice` FGS 的硬前置不是 `POST_NOTIFICATIONS`**。硬前置是 manifest 中声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，并满足 connected-device 类型的运行时前置条件；本 App 实际需要 `BLUETOOTH_CONNECT`。`POST_NOTIFICATIONS` 仍建议在开始采集前请求，但拒绝通知权限不等于不能启动 FGS。
4. **未绑定设备恢复不能依赖"MAC 缓存命中/未命中"语义**。本库 Android 实现可以用 `getPeripheralById(address)` 从 MAC 构造 `Peripheral`；真正的不确定性在于设备是否仍使用同一地址、是否在范围内、以及 `AutoConnect` 何时完成。若设备使用可轮换随机地址且不绑定，仅持久化 MAC 不可靠，必须通过扫描的 Service UUID / 名称等信息重新识别。

## 1. 目标与范围

BlueTrace 是一个面向 BLE 传感器的**数据采集** App，一次会话支持 **1..N 个设备并行采集**。核心使用场景是 **PPG 信号评测**，但底层架构对设备数量不做硬约束：

- **DUT (Device Under Test，待测设备)**：自研 PPG 设备，私有协议，输出原始 PPG 波形 / 心率推算值。**单设备闭环**（只接 DUT 看自己的数据）是最常见的日常用法。
- **REFERENCE (参考设备)**：标准心率带（如 Polar H10 / Wahoo Tickr），SIG Heart Rate Service (`0x180D`)，输出 BPM 与可选 RR 间期。**对照采集**（DUT + REFERENCE 同时连）用于评测 DUT 的心率算法准确度。
- **未来扩展**：再加血氧仪 PulseOximeter Service (`0x1822`) 等其它角色，只新增一个 Profile + 在 `ProfileRegistry` 注册一行即可。

一次"会话 (Session)" = 一份 `DeviceAssignment`（`Map<DeviceRole, Peripheral>`，**`size >= 1`**）：

- `size == 1`（仅 DUT 或仅 REFERENCE）：单设备会话，UI 渲染一个设备卡片，落一个 CSV。
- `size == 2`（DUT + REFERENCE）：对照会话，两台并行采集，各自落自己的 CSV，由会话级 manifest 关联。
- `size > 2`：未来扩展，N 角色并行。

UI 在"设备分配"页让用户**选择本次启用哪些角色**，未选择的角色就不在 `DeviceAssignment` 里。运行时编排、状态聚合、文件落盘对设备数量完全透明——**不存在"单设备"和"多设备"两套代码路径**。

### 1.1 核心能力

- 请求并管理 Android BLE 扫描、连接、（可选）定位和文件保存所需权限。
- 扫描周围 BLE 设备，按"目标角色"过滤展示（DUT 列表 / REFERENCE 列表），支持分别绑定；用户可只绑定一个角色就开始采集。
- **支持 1..N 个设备同时连接采集**：每个连接独立的协程会话、独立的 Profile、独立的 CSV 文件；本期重点验证 1 个和 2 个设备两种典型组合。
- 每台设备自动发现服务、开启通知 / 指示，按设备协议解析数据。
- 接收数据通过角色相关的强类型 `Flow<RoleData>` 分发给上层。
- 数据落盘按 **D-6 文件夹模型**：每会话一文件夹，**原始 HEX 行日志为 source of truth** + 解码 CSV + 组合包兼容 CSV；会话级 `session_manifest.json` 记录开始/结束时间、**`subject`（受试者）/ `mode`（Wear/Unwear）**、设备 MAC、采样起点 wallclock + monotonic 时间，用于多设备后期对齐。
- 对权限拒绝、蓝牙关闭、单设备断开、单设备超时等失败做**按角色隔离恢复**——某一角色掉线不影响其它角色；单设备会话退化为整个会话受影响。
- 支持后台持续采集（前台服务 + `connectedDevice` 类型，Android 14+ 强制要求）。

### 1.2 协议占位

- **DUT**：Service UUID、Write/Notify Characteristic UUID 以常量形式预留在 `BlueTraceUuids`，对接真实设备时只需替换并实现 `DutProfileImpl` + `DutDataParser`，上层零改动。
- **REFERENCE**：实现标准 Heart Rate Service (`0x180D` + `0x2A37`)，开箱即用 Polar / Wahoo / Garmin 等市售心率带。

## 2. 技术选型

| 维度 | 选择 |
| --- | --- |
| Language | Kotlin 2.x，目标 JVM 17 |
| UI | Jetpack Compose（Compose BOM）+ Material 3，edge-to-edge |
| Navigation | `androidx.navigation:navigation-compose`（Type-safe Routes） |
| Architecture | MVVM + 单向数据流（UDF），ViewModel 暴露 `StateFlow<UiState>` |
| Async | Kotlin Coroutines + Flow |
| **BLE 核心** | **本仓库 `Kotlin-BLE-Library v2.0`**：`:client-android`（native）+ `:environment-android` + `:environment-android-compose`；测试用 `:client-android-mock` + `:environment-android-mock`。本 App 仅作为 Central（扫描 + 连接 + GATT 操作），**不使用 advertiser 模块**，不集成 `:advertiser-*` 依赖、不声明 `BLUETOOTH_ADVERTISE` 权限 |
| DI | Hilt（Hilt Navigation Compose 注入 ViewModel） |
| 日志 | SLF4J + `no.nordicsemi.android:log-timber` 桥接到 Timber（与库自带日志统一） |
| Storage | App 私有外部 `Context.getExternalFilesDir(...)`；可选导出走 MediaStore Downloads |
| 后台 | Foreground Service，`foregroundServiceType="connectedDevice"`（Android 14+ 必需声明并在 manifest 加权限） |
| Lint / 兼容 | `desugaring`（`java.time.*` 在 API 24 上可用）；AndroidX Splash Screen；Predictive Back（API 33+ 开启 `enableOnBackInvokedCallback`） |
| Build | Gradle Version Catalog（本仓库已用 `libs` + `nordic`） |

> 关键变更点（对比早期方案）：**不再使用** `no.nordicsemi.android:ble`（旧 `BleManager`）和 `BluetoothLeScannerCompat`。所有 BLE 行为统一走 v2.0 的 `CentralManager` / `Peripheral` / `peripheral.profile(...)`。

## 3. 工程结构与依赖

建议新建独立 app 模块 `:bluetrace-app`（与本仓库 `:sample` 并列），避免业务代码与 sample 混在一起。

### 3.1 Gradle 依赖（在 `bluetrace-app/build.gradle.kts` 中）

```kotlin
plugins {
    alias(libs.plugins.nordic.application.compose) // 复用本仓库已有的 nordic plugin
    alias(libs.plugins.nordic.hilt)
}

android {
    namespace = "com.example.bluetrace"
    defaultConfig {
        applicationId = "com.example.bluetrace"
        minSdk = 29                              // D-7：Android 10
        targetSdk = 35
    }
    // D-7 简化：minSdk 29 起 java.time.* 原生可用，已去掉 coreLibraryDesugaring
    flavorDimensions += listOf("mode")
    productFlavors {
        create("native") { isDefault = true; dimension = "mode" }
        create("mock")   { dimension = "mode" }
    }
}

dependencies {
    // BLE 核心（v2.0，仓库内 project 依赖）—— 只作为 Central，不引入 advertiser
    // 注：:client-android 已通过 api(...) 传递依赖 :environment-android + :client-core-android，
    //     :client-android-mock 同理传递 :client-core-mock + :environment-android-mock。
    "nativeImplementation"(project(":client-android"))
    "mockImplementation"(project(":client-android-mock"))
    // 仅 Compose 端 LocalEnvironmentOwner 需要显式引入，全 flavor 都用
    implementation(project(":environment-android-compose"))
    // debug 构建走 mock env 让 Compose Preview 可跑（不依赖真实蓝牙硬件）
    debugImplementation(project(":environment-android-mock"))

    // Compose UI / Navigation / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.splashscreen)

    // Logging：SLF4J → Timber（库本身用 SLF4J）
    implementation(libs.slf4j.timber)

    // D-7：minSdk 29 起无需 desugaring（java.time.* 原生可用）
}
```

### 3.2 包结构

```text
com.example.bluetrace
├── app
│   ├── BlueTraceApplication.kt
│   └── di
│       ├── EnvironmentModule.kt        // 提供 NativeAndroidEnvironment + AndroidEnvironment
│       ├── CentralManagerModule.kt     // 提供 CentralManager.Factory.native(env, scope)
│       └── AppScopeModule.kt           // 提供 ApplicationScope = SupervisorJob + Dispatchers.Default
├── permissions
│   ├── BlePermissionFacade.kt          // 薄包装：组合 AndroidEnvironment + 需请求的运行时权限列表
│   └── ui
│       └── PermissionGateScreen.kt
├── scan
│   ├── ScanViewModel.kt
│   ├── ScanScreen.kt
│   └── DiscoveredDevice.kt             // 与 sample/common/DiscoveredDevice.kt 类似
├── profile                             // 每种设备协议一个子包，统一实现 SensorProfile<T>
│   ├── SensorProfile.kt                // 抽象：data Flow<T> + start(scope) + sendCommand?
│   ├── DeviceRole.kt                   // enum class: DUT, REFERENCE, ... (扩展开放)
│   ├── ProfileRegistry.kt              // Map<DeviceRole, ProfileFactory>，决定每个角色用哪个 Profile
│   ├── dut                             // 待测 PPG 设备（私有协议）
│   │   ├── DutUuids.kt
│   │   ├── DutProfile.kt
│   │   ├── DutProfileImpl.kt
│   │   ├── DutSample.kt                // data class: PPG 波形 / HR / 时间戳
│   │   └── DutDataParser.kt
│   └── reference                       // 参考心率带（SIG Heart Rate Service 0x180D）
│       ├── HeartRateProfile.kt
│       ├── HeartRateProfileImpl.kt
│       └── HeartRateSample.kt          // data class: bpm + rrIntervals + sensorContact
├── session
│   ├── DeviceAssignment.kt             // data class: Map<DeviceRole, Peripheral>，UI 选完设备后冻结
│   ├── MultiDeviceController.kt        // 顶层：派发到 N 个 PerDeviceSession，并行
│   ├── PerDeviceSession.kt             // 单设备一个会话：连接→安装 profile→起 pipeline→重连循环
│   ├── SessionState.kt                 // sealed: Map<DeviceRole, DeviceState> + 聚合状态
│   ├── CollectionPipeline.kt           // notify → parse → sink，泛型化以支持任意 SampleType
│   ├── CollectionSessionStore.kt       // DataStore 持久化 Map<DeviceRole, MAC + fileName>
│   └── CollectionResumer.kt            // 进程被杀后逐角色恢复
├── data
│   ├── ParseResult.kt
│   └── SensorSample.kt                 // 标记接口；各 profile 子包定义具体 data class
├── storage
│   ├── SensorDataSink.kt               // 泛型化：interface SensorDataSink<T : SensorSample>
│   ├── CsvSensorDataSink.kt            // Channel + 单消费者；header 由 SampleType 决定
│   ├── SessionManifest.kt              // 会话 JSON：开始/结束时间 + 每角色文件名 + MAC + monotonic 起点
│   └── ExportToDownloads.kt            // MediaStore，API 24+ 兼容路径
├── service
│   ├── CollectionForegroundService.kt  // connectedDevice 类型，Android 14+；含 WakeLock
│   └── BootCompletedReceiver.kt        // 设备重启后发通知引导用户回 App，不直接启 FGS（§13.6）
// ── 以下为 Phase 6（二期：服务器远控/上传）才引入，本期不实现 ──
// ├── work
// │   ├── CsvUploadWorker.kt              // 周期上传 CSV 到服务器
// │   ├── FileRotationWorker.kt           // 每日清理过期 CSV
// │   └── CollectionWatchdogWorker.kt     // FGS 被杀后的恢复通知
└── ui
    ├── BlueTraceNavHost.kt
    ├── DataCollectionScreen.kt
    ├── components
    └── theme
```

## 4. 架构分层

```text
Compose UI
  ↕ collectAsStateWithLifecycle / 事件回调
ViewModel (state holder, no Android BLE API)
  ↕ suspend / Flow
Session Layer            ←─ profile/, session/  （应用级编排，纯 Kotlin + 协程）
  ↕
Profile Layer            ←─ SensorProfile<T>：业务化封装 RemoteCharacteristic
  ↕
Kotlin BLE Library v2.0  ←─ CentralManager / Peripheral / RemoteService
  ↕
Android Bluetooth Stack
```

职责边界：

- **UI**：只显示状态、转发用户事件，不感知 BLE / Peripheral 类型。
- **ViewModel**：`combine` 多个 `StateFlow` 派生 UI 状态；不持有任何长生命周期 BLE 对象的引用。
- **Session**：拥有"连接 → 订阅 → 采集"协程编排，管理 connection scope 的生命周期。
- **Profile**：把 `RemoteCharacteristic` 包装成业务语义 API（`notifications: Flow<ByteArray>` / `sendCommand(...)`）。换协议只动这层。
- **Parser**：纯 Kotlin，便于 JVM 单测；不依赖任何 Android 类。
- **Sink**：只负责数据落盘，不参与 BLE 协议判断。

## 5. Manifest 与权限

### 5.1 Manifest（`AndroidManifest.xml`）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <!-- 必需硬件 -->
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>

    <!-- Android 11 (API ≤ 30) 兼容的旧权限 -->
    <uses-permission android:name="android.permission.BLUETOOTH"        android:maxSdkVersion="30"/>
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"  android:maxSdkVersion="30"/>

    <!-- Android 12+ (API 31+) 蓝牙运行时权限 -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

    <!-- 定位：BlueTrace 不声明 neverForLocation，按"扫描结果可能用于设备位置推断"处理 -->
    <!-- Android 11 及以下 BLE 扫描需要定位权限；Android 12+ 未声明 neverForLocation 时也声明并请求 -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
    <!-- 如果产品确认 Android 12+ 绝不基于扫描结果推断位置，则改为：
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                     android:usesPermissionFlags="neverForLocation"/>
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                     android:maxSdkVersion="30"/>
    并在 NativeAndroidEnvironment.getInstance(..., isNeverForLocationFlagSet = true) 中保持一致。 -->

    <!-- 前台服务（Android 14+ 必须声明对应类型权限） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>

    <!-- 通知（Android 13+，前台服务通知需要） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <!-- CPU 唤醒锁：保证 Doze / 国产 ROM 休眠下高频采集不掉帧 -->
    <uses-permission android:name="android.permission.WAKE_LOCK"/>

    <!-- 仅用于 API 24-28 把 CSV 复制到公共 Downloads 目录（API 29+ 走 MediaStore，无需此权限） -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
                     android:maxSdkVersion="28"/>

    <!-- 注意：本 App 仅作为 Central，不做 BLE 广播，故不声明 BLUETOOTH_ADVERTISE -->


    <application
        android:name=".app.BlueTraceApplication"
        android:enableOnBackInvokedCallback="true"
        android:theme="@style/Theme.BlueTrace"
        tools:targetApi="35">

        <activity android:name=".MainActivity"
                  android:exported="true"
                  android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service
            android:name=".service.CollectionForegroundService"
            android:foregroundServiceType="connectedDevice"
            android:exported="false"/>
    </application>
</manifest>
```

要点：

- 为覆盖 **API 29–30**（旧蓝牙权限模型），需同时保留旧的 `BLUETOOTH` / `BLUETOOTH_ADMIN`（带 `maxSdkVersion="30"`）和新的（API 31+）`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`。
- BlueTrace 是"采集"场景，**不**使用 `usesPermissionFlags="neverForLocation"`，因此 `AndroidEnvironment.isLocationRequiredForScanning` 在 Android 12+ 也会是 `true`，需要声明并请求 `ACCESS_FINE_LOCATION`。如果产品上确认"扫描结果不会用于位置推断"，再给 `BLUETOOTH_SCAN` 加 `neverForLocation`，把 `ACCESS_FINE_LOCATION` 改为 `maxSdkVersion="30"`，并在构造 `NativeAndroidEnvironment` 时传 `isNeverForLocationFlagSet = true`。
- Android 13+ 前台服务通知涉及 `POST_NOTIFICATIONS`，建议在开始采集前请求；用户拒绝时 FGS 仍可启动，但通知不会出现在通知抽屉，只会在系统的活动应用/任务管理入口中显示。
- Android 14+ 启动 connectedDevice 类型前台服务前，本 App 至少要已有 `BLUETOOTH_CONNECT` 运行时授权，否则 GATT 操作和 `startForeground(...)` 的 connected-device 类型前置条件都不成立。
- Splash Screen API 走 `androidx.core:core-splashscreen`，对 API 24 起兼容。
- `enableOnBackInvokedCallback="true"` 启用预测式返回（API 33+），Compose `BackHandler` 自动适配。

### 5.2 运行时权限策略（按 SDK 版本）

| SDK | 必须运行时请求 |
| --- | --- |
| 24–28 | `ACCESS_FINE_LOCATION`（扫描必需）；如需"导出到公共 Downloads"还要 `WRITE_EXTERNAL_STORAGE` |
| 29–30 | `ACCESS_FINE_LOCATION`（扫描必需） |
| 31–32 | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`，**如未设 `neverForLocation`** 还要声明并请求 `ACCESS_FINE_LOCATION` |
| 33+ | 同上，外加 `POST_NOTIFICATIONS`（仅当使用前台服务通知时） |

**真理之源是 `AndroidEnvironment`**（蓝牙、定位、Scan/Connect 权限），不要自己写 `Build.VERSION.SDK_INT` 分支。`WRITE_EXTERNAL_STORAGE`（API ≤ 28）和 `POST_NOTIFICATIONS`（API ≥ 33）这两个在 `AndroidEnvironment` 范围之外，需要自己用 `ContextCompat.checkSelfPermission` 检查。

### 5.3 前台服务的额外前置条件（Android 14+）

启动 `connectedDevice` 类型的 FGS 之前，**必须满足系统硬前置**，否则 `Service.startForeground(...)` 可能抛 `SecurityException` / `MissingForegroundServiceTypeException` / `ForegroundServiceTypeException`：

1. Manifest 已声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`，且 `<service>` 带 `android:foregroundServiceType="connectedDevice"`。
2. connected-device 类型要求至少满足一个运行时/声明条件；本 App 走 BLE GATT，因此要求 `BLUETOOTH_CONNECT` 已运行时授权（API 31+）。
3. `POST_NOTIFICATIONS` 已运行时授权（API 33+）不是启动 FGS 的硬前置，但建议在开始采集前请求，否则用户看不到通知抽屉里的前台服务通知。
4. App 当前处于**前台**或处于"FGS 允许启动的豁免状态"（Android 12+ 起，从后台启动 FGS 会抛 `ForegroundServiceStartNotAllowedException`）。落地做法：**永远由 UI 层的用户操作触发**（例如点击"开始采集"按钮）调用 `ContextCompat.startForegroundService(...)`，不要在 `BroadcastReceiver` / `JobScheduler` / `WorkManager` 的后台回调里启动。
5. 通知 channel 已经在 `Application.onCreate` 提前创建（`NotificationManagerCompat.createNotificationChannel`）。

## 6. Environment、Hilt 与协程作用域

> **作用域决策**：本 App 有**前台服务长驻**特性，BLE 与 Environment 必须能在 Activity 被销毁后继续可用，否则用户退出 UI 时 Hilt 把 `CentralManager.close()` 一调，后台采集立刻被打断。因此 **Environment、CentralManager、AppScope 全部使用 `@InstallIn(SingletonComponent::class)` + `@Singleton`**，跟随 Application 进程存活。
>
> **对比库 README 的示例**：README 给的 Hilt 模板用的是 `@InstallIn(ActivityRetainedComponent::class) + @ActivityRetainedScoped`，并在 `ActivityRetainedLifecycle.addOnClearedListener { env.close() }` 中显式释放。那个写法适合"全部 BLE 操作发生在 UI 周期内"的轻量 sample；本 App 的 FGS 长驻模式必须避免任何"Activity 销毁就 close" 的行为。**两种作用域返回的 `NativeAndroidEnvironment` 实例底层都是同一个**——库内部是 `lateinit var instance` 进程级 singleton（见 `NativeAndroidEnvironment.getInstance`），DI 作用域影响的只是 `close()` 何时被调到。
>
> `NativeAndroidEnvironment` 注册的 `BroadcastReceiver` 在进程死亡时由系统回收，正常路径不需要显式 `close()`；如确需主动释放（如某些测试场景），通过显式接口在 `FGS.onDestroy` 或 `Application.onTerminate`（仅模拟器有效）中调用。

### 6.1 EnvironmentModule

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object EnvironmentModule {

    @Singleton
    @Provides
    fun provideNativeEnvironment(
        @ApplicationContext context: Context,
    ): NativeAndroidEnvironment =
        NativeAndroidEnvironment
            // 与 AndroidManifest 中是否使用 neverForLocation 必须保持一致
            .getInstance(context, isNeverForLocationFlagSet = false)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AndroidEnvironmentBindModule {
    @Binds abstract fun bindEnvironment(env: NativeAndroidEnvironment): AndroidEnvironment
}
```

### 6.2 BlePermissionFacade（只读 + 计算缺失项，不重复造状态）

```kotlin
class BlePermissionFacade @Inject constructor(
    private val env: AndroidEnvironment,
) {
    data class Snapshot(
        val bluetoothOn: Boolean,
        val locationOn: Boolean,
        val scanGranted: Boolean,
        val connectGranted: Boolean,
        val locationGranted: Boolean,
        val needsLocation: Boolean,
        val needsBluetoothRuntimePerms: Boolean,
    )

    fun snapshot() = Snapshot(
        bluetoothOn = env.isBluetoothEnabled,
        locationOn  = env.isLocationEnabled,
        scanGranted    = env.isBluetoothScanPermissionGranted,
        connectGranted = env.isBluetoothConnectPermissionGranted,
        locationGranted= env.isLocationPermissionGranted,
        needsLocation  = env.isLocationRequiredForScanning,
        needsBluetoothRuntimePerms = env.requiresBluetoothRuntimePermissions,
    )

    fun missingRuntimePermissions(): List<String> = buildList {
        if (env.requiresBluetoothRuntimePermissions) {
            if (!env.isBluetoothScanPermissionGranted)    add(AndroidEnvironment.Permission.BLUETOOTH_SCAN)
            if (!env.isBluetoothConnectPermissionGranted) add(AndroidEnvironment.Permission.BLUETOOTH_CONNECT)
        }
        if (env.isLocationRequiredForScanning && !env.isLocationPermissionGranted) {
            add(AndroidEnvironment.Permission.ACCESS_FINE_LOCATION)
        }
    }
}
```

UI 端用 `accompanist-permissions` 或 `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`。在 Compose 中读环境变化用：

```kotlin
val env = LocalEnvironmentOwner.current   // 由 :environment-android-compose 提供
val bluetoothOn by env.bluetoothState.collectAsStateWithLifecycle()
val locationOn  by env.locationState.collectAsStateWithLifecycle()
```

### 6.3 CentralManagerModule

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object CentralManagerModule {

    @AppScope
    @Singleton
    @Provides
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("BleScope"))

    @Singleton
    @Provides
    fun provideCentralManager(
        env: AndroidEnvironment,
        @AppScope scope: CoroutineScope,
    ): CentralManager = CentralManager.Factory.native(env, scope)
}
```

> mock flavor 里把 `CentralManager.mock(env, scope).apply { simulatePeripherals(...) }` 替换上去即可。
> 进程级 Singleton 意味着这两个对象会一直存在到 App 进程被系统杀掉，正符合"前台服务连续采集"的语义。任何 ViewModel / Service 都拿同一个实例，避免 Activity 重建时丢连接。

## 7. 扫描模块

直接使用 `CentralManager.scan(...)`，不再包一层 Repository。

```kotlin
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val centralManager: CentralManager,
    private val permissions: BlePermissionFacade,
    @AppScope private val scope: CoroutineScope,
) : ViewModel() {

    data class UiState(
        val permissions: BlePermissionFacade.Snapshot,
        val isScanning: Boolean,
        val devices: List<DiscoveredDevice>,
        val error: String? = null,
    )

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(_devices, _isScanning, _error) { d, s, e ->
        UiState(permissions.snapshot(), s, d, e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        UiState(permissions.snapshot(), false, emptyList()))

    private var job: Job? = null

    fun onStartScan(timeout: Duration = 10.seconds) {
        if (job?.isActive == true) return
        job = centralManager
            .scan(timeout) {
                // 可选过滤：限定目标 Service UUID
                // ServiceUUID(BlueTraceUuids.SERVICE)
            }
            .onStart { _isScanning.value = true; _error.value = null }
            .filterNot { it.peripheral.name.isNullOrBlank() }  // 默认隐藏无名设备
            .distinctByPeripheral()
            .map { DiscoveredDevice(it.peripheral, it.rssi, System.currentTimeMillis()) }
            .onEach { d ->
                _devices.update { list ->
                    if (list.any { it.peripheral.identifier == d.peripheral.identifier }) {
                        list.map { if (it.peripheral.identifier == d.peripheral.identifier) d else it }
                    } else list + d
                }
            }
            .catch { _error.value = it.message }
            .onCompletion { _isScanning.value = false }
            .launchIn(scope)
    }

    fun onStopScan() = job?.cancel()
}
```

策略：

- **默认 10 s 超时**，不做无限扫描，避免 Android 7+ 的"每 30 s 不能超过 5 次扫描"限制。
- `distinctByPeripheral()`（库自带扩展）按 peripheral 去重；UI 内额外按 `identifier` 更新 RSSI。
- 默认隐藏无名设备；如需调试，加一个"显示无名设备"开关绕过 `filterNot`。
- 连接前应主动 `onStopScan()` 释放系统扫描器。
- **扫描是"前台一次性动作"**，不进入 FGS，也不上 WorkManager（理由见 §13.4）。库 v2.0 暂不支持 `PendingIntent` 后台扫描，"App 关闭后设备进入范围自动发现"这种场景必须改用 §13.6 的 `AutoConnect + 持久化 MAC` 替代方案。

## 8. Profile 抽象与设备角色

> 核心思想：把"设备协议"和"设备角色"解耦。每个 BLE 协议实现一个 `SensorProfile<TSample>`（产出强类型的 `Flow<TSample>`）；上层只按"角色 (`DeviceRole`)"对设备进行分配，运行时由 `ProfileRegistry` 决定某个角色用哪个 Profile 实现。这样**新增一个第三方传感器只是新增一个 Profile 实现 + 在 registry 注册一行**，不动 controller / pipeline / UI。
>
> **关于设备数量**：`DeviceAssignment` 是 `Map<DeviceRole, Peripheral>`，只要求 `size >= 1`。只填 DUT 是单设备会话，DUT + REFERENCE 是双设备对照，未来填 DUT + REFERENCE + SPO2 就是三设备扩展。**所有下层组件对设备数量透明**——`MultiDeviceController` 用 `assignment.devices.forEach { ... }` 启动每个角色的 `PerDeviceSession`，1 个就启 1 个 `launch`，N 个就启 N 个 `launch`。

### 8.1 DeviceRole 与 SensorSample

```kotlin
enum class DeviceRole {
    DUT,         // 待测设备（PPG）
    REFERENCE,   // 参考心率带
    // 未来扩展：SPO2, ECG, IMU, ...
}

/** 所有传感器数据样本的标记接口；具体字段由各 Profile 子包定义。 */
sealed interface SensorSample {
    val receivedAtEpochMillis: Long
    val monotonicNanos: Long
    /** 用于 CSV 表头与解析时的稳定列序。 */
    fun csvRow(): List<String>
}

fun csvHeader(role: DeviceRole): List<String> = when (role) {
    DeviceRole.DUT -> listOf(
        "receivedAtEpochMillis", "monotonicNanos",
        "sequence", "ppgValue", "derivedBpm",
    )
    DeviceRole.REFERENCE -> listOf(
        "receivedAtEpochMillis", "monotonicNanos",
        "bpm", "rrIntervalsMs", "sensorContact", "energyExpendedKJ",
    )
}

data class DutSample(
    override val receivedAtEpochMillis: Long,
    override val monotonicNanos: Long,
    val sequence: Int,
    val ppgValue: Int,          // 原始 PPG ADC
    val derivedBpm: Float?,     // 设备端推算心率
) : SensorSample {
    override fun csvRow() = listOf(
        receivedAtEpochMillis.toString(), monotonicNanos.toString(),
        sequence.toString(), ppgValue.toString(), derivedBpm?.toString().orEmpty(),
    )
}

data class HeartRateSample(
    override val receivedAtEpochMillis: Long,
    override val monotonicNanos: Long,
    val bpm: Int,
    val rrIntervalsMs: List<Int>,    // 标准 HRS 可携带多个 RR
    val sensorContact: Boolean?,
    val energyExpendedKJ: Int?,
) : SensorSample {
    override fun csvRow() = listOf(
        receivedAtEpochMillis.toString(), monotonicNanos.toString(),
        bpm.toString(), rrIntervalsMs.joinToString(";"),
        sensorContact?.toString().orEmpty(), energyExpendedKJ?.toString().orEmpty(),
    )
}
```

### 8.2 SensorProfile 接口

```kotlin
/**
 * 设备协议抽象。每个具体协议（DUT / HeartRate / SpO2...）实现一个，
 * 产出强类型 [Flow<TSample>]。安装时一次性绑定到某个 [Peripheral]。
 */
interface SensorProfile<TSample : SensorSample> {
    val role: DeviceRole
    val samples: Flow<TSample>           // 已解析后的强类型数据流
    val rawNotifications: Flow<ByteArray>? get() = null   // 可选：调试用原始字节流
    suspend fun sendCommand(payload: ByteArray) = Unit    // 可选：DUT 才用
}

/**
 * Profile 工厂：在连接 scope 内创建并安装一个 Profile。
 * 在 [scope] 取消时（连接断开）自动停止解析与订阅。
 */
fun interface ProfileFactory {
    suspend fun install(
        peripheral: Peripheral,
        scope: CoroutineScope,
        block: suspend (profile: SensorProfile<*>) -> Unit,
    )
}

/** 由 DI 注入。新增协议只需在这里 put 一个 entry。 */
@Singleton
class ProfileRegistry @Inject constructor(
    private val dut: DutProfileFactory,
    private val heartRate: HeartRateProfileFactory,
) {
    operator fun get(role: DeviceRole): ProfileFactory = when (role) {
        DeviceRole.DUT       -> dut
        DeviceRole.REFERENCE -> heartRate
    }
}
```

### 8.3 实现 1：DUT Profile（私有协议占位）

```kotlin
@OptIn(ExperimentalUuidApi::class)
object DutUuids {
    val SERVICE     : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR  : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
}

class DutProfileImpl(
    private val peripheral: Peripheral,
    private val notifyChar: RemoteCharacteristic,
    private val writeChar:  RemoteCharacteristic,
    private val parser:     DutDataParser,
) : SensorProfile<DutSample> {

    override val role = DeviceRole.DUT

    override val samples: Flow<DutSample> = notifyChar
        .subscribe(onSubscription = { Timber.i("DUT subscribed: $uuid") })
        .mapNotNull { (parser.parse(it) as? ParseResult.Success<DutSample>)?.data }

    override suspend fun sendCommand(payload: ByteArray) {
        val type = if (writeChar.properties.contains(CharacteristicProperty.WRITE_WITHOUT_RESPONSE))
            WriteType.WITHOUT_RESPONSE else WriteType.WITH_RESPONSE
        val maxLen = peripheral.maximumWriteValueLength(type)
        payload.chunked(maxLen).forEach { writeChar.write(it, type) }
    }
}

@OptIn(ExperimentalUuidApi::class)
class DutProfileFactory @Inject constructor(private val parser: DutDataParser) : ProfileFactory {
    override suspend fun install(
        peripheral: Peripheral, scope: CoroutineScope,
        block: suspend (SensorProfile<*>) -> Unit,
    ) = peripheral.profile(DutUuids.SERVICE, required = true) { svc ->
        val notify = svc.characteristics.first { it.uuid == DutUuids.NOTIFY_CHAR }
        val write  = svc.characteristics.first { it.uuid == DutUuids.WRITE_CHAR }
        require(notify.isSubscribable()) { "DUT notify char not subscribable" }
        block(DutProfileImpl(peripheral, notify, write, parser))
    }
}
```

### 8.4 实现 2：标准心率带 Profile（SIG Heart Rate Service `0x180D`）

参考 [Bluetooth SIG Heart Rate Service 1.0](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/) 与 [Heart Rate Measurement `0x2A37`](https://www.bluetooth.com/specifications/gatt/characteristics/) 规范。

```kotlin
@OptIn(ExperimentalUuidApi::class)
object HeartRateUuids {
    val SERVICE              : Uuid = Uuid.parse("0000180D-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: Uuid = Uuid.parse("00002A37-0000-1000-8000-00805f9b34fb")
    val BODY_SENSOR_LOCATION : Uuid = Uuid.parse("00002A38-0000-1000-8000-00805f9b34fb")
}

class HeartRateProfileImpl(
    private val hrChar: RemoteCharacteristic,
) : SensorProfile<HeartRateSample> {

    override val role = DeviceRole.REFERENCE

    override val samples: Flow<HeartRateSample> = hrChar
        .subscribe(onSubscription = { Timber.i("HRS subscribed") })
        .map { parseMeasurement(it, System.currentTimeMillis(), System.nanoTime()) }

    /**
     * 标准帧格式（first byte = flags）：
     *   bit0: 0 = HR uint8, 1 = HR uint16
     *   bit1-2: sensor contact status (00/01 = not supported, 10 = no contact, 11 = contact)
     *   bit3: 0 = energy expended not present, 1 = present (uint16, kJ)
     *   bit4: 0 = RR interval not present, 1 = present (uint16 array, 1/1024 s)
     */
    private fun parseMeasurement(data: ByteArray, wall: Long, mono: Long): HeartRateSample {
        val flags = data[0].toInt() and 0xFF
        var offset = 1
        val bpm: Int = if ((flags and 0x01) == 0) {
            (data[offset].toInt() and 0xFF).also { offset += 1 }
        } else {
            ((data[offset].toInt() and 0xFF) or
             ((data[offset + 1].toInt() and 0xFF) shl 8)).also { offset += 2 }
        }
        val contact: Boolean? = when ((flags shr 1) and 0x03) {
            0b10 -> false; 0b11 -> true; else -> null
        }
        val energy: Int? = if ((flags and 0x08) != 0) {
            ((data[offset].toInt() and 0xFF) or
             ((data[offset + 1].toInt() and 0xFF) shl 8)).also { offset += 2 }
        } else null
        val rrs = mutableListOf<Int>()
        if ((flags and 0x10) != 0) {
            while (offset + 1 < data.size) {
                val raw = (data[offset].toInt() and 0xFF) or
                          ((data[offset + 1].toInt() and 0xFF) shl 8)
                rrs += (raw * 1000) / 1024     // 1/1024 s → ms
                offset += 2
            }
        }
        return HeartRateSample(wall, mono, bpm, rrs, contact, energy)
    }
}

@OptIn(ExperimentalUuidApi::class)
class HeartRateProfileFactory @Inject constructor() : ProfileFactory {
    override suspend fun install(
        peripheral: Peripheral, scope: CoroutineScope,
        block: suspend (SensorProfile<*>) -> Unit,
    ) = peripheral.profile(HeartRateUuids.SERVICE, required = true) { svc ->
        val hr = svc.characteristics.first { it.uuid == HeartRateUuids.HEART_RATE_MEASUREMENT }
        require(hr.isSubscribable()) { "HRS Measurement char not subscribable" }
        block(HeartRateProfileImpl(hr))
    }
}
```

**为什么把心率带当作"标准库代码"实现一次就一劳永逸**：Polar H10、Wahoo Tickr、Garmin HRM-Pro、Coros HRM 等市面所有 SIG 兼容心率带都实现 `0x180D`，**同一份 `HeartRateProfileImpl` 全部能用**。

### 8.5 错误映射（多设备语义）

> **重要**：v2.0 库的 `profile(required = true)` 在缺失服务时**不会**抛 `OperationFailedException`，而是把 `peripheral.state` 切到 `ConnectionState.Disconnected(reason = RequiredServiceNotFound)`。识别这个失败的正确方式是**观察 `peripheral.state`**，不是 try/catch profile 调用。同理，`peripheral.state` 也是断线、设备出范围等所有连接相关失败的真理之源。

| 库信号 | 应用层 `DeviceState.Reason` | 影响范围 |
| --- | --- | --- |
| `peripheral.state == Disconnected(reason = RequiredServiceNotFound)`（来自 `profile(required = true)` 未命中服务） | `DeviceNotSupported` | 仅该角色，**不应再重连**——同一设备没换协议前怎么连都是这个错 |
| `NoSuchElementException`（在 `svc.characteristics.first { ... }` 找不到 characteristic） | `DeviceNotSupported` | 仅该角色，**不应再重连** |
| `OperationFailedException(reason = SubscribeNotPermitted)` | `SubscriptionFailed` | 仅该角色 |
| `OperationFailedException(reason = WriteNotPermitted / GattError / RequestNotSupported)` | `WriteFailed` | 仅该角色 |
| `OperationFailedException(reason = ...)` 其它 GATT 状态 | `Unknown`，记日志带 `OperationStatus` 文本 | 仅该角色 |
| `ConnectionFailedException(reason)` | `ConnectionTimeout` / `Disconnected`（按 `reason` 进一步细分） | 仅该角色，触发重连循环 |
| `peripheral.state == Disconnected(reason = LinkLoss / Timeout / TerminatePeerUser)` | `Disconnected` | 仅该角色，触发 `AutoConnect` 等回；适合走 §9.2 的重连循环 |
| `peripheral.state == Disconnected(reason = UnsupportedAddress)` | `Disconnected` | 仅该角色；MAC 已轮换 → 触发重扫（§13.6.2 fallback） |
| `PeripheralNotConnectedException`（操作进行中被取消） | `Disconnected` | 仅该角色 |
| `InvalidAttributeException`（服务被无效化 / 设备发了 Service Changed） | `Disconnected`，下一次循环会重新发现服务 | 仅该角色 |
| `SecurityException` | `PermissionDenied` | **全局**——但注意：Android 实际行为是撤销 `BLUETOOTH_CONNECT` 会**杀进程**，所以这条更多在 mock / 边缘场景里出现；正常路径靠下次冷启动 `CollectionResumer` 检查 `env.isBluetoothConnectPermissionGranted` |
| 蓝牙关闭（`env.bluetoothState != POWERED_ON`） | `BluetoothOff` | **全局** |

`OperationStatus` 的具体名称见 `core/src/main/java/no/nordicsemi/kotlin/ble/core/OperationStatus.kt`。常用的有：`Success` / `ReadNotPermitted` / `WriteNotPermitted` / `RequestNotSupported` / `InsufficientAuthentication` / `InsufficientEncryption` / `AttributeNotFound` / `SubscribeNotPermitted` / `GattError` / `ConnectionTimeout` / `Busy` / `RequestFailed`。**注意全部是 PascalCase `object`，不是 SCREAMING_SNAKE 常量**。

## 9. 连接编排（MultiDeviceController + PerDeviceSession）

### 9.1 连接选项

> 库里属性名是 `automaticallyRequestHighestValueLength`（见 `client-core-android/.../CentralManager.kt`），README 示例中的 `automaticallyRequestHighestMtu` 不存在。

```kotlin
private val firstConnectOptions = CentralManager.ConnectionOptions.Direct(
    timeout = 10.seconds,
    retry = 3,
    retryDelay = 1.seconds,
    preferredPhy = listOf(Phy.PHY_LE_2M),
    automaticallyRequestHighestValueLength = true,
)

private val reconnectOptions = CentralManager.ConnectionOptions.AutoConnect(
    automaticallyRequestHighestValueLength = true,
)
```

- **首连**：用户首次点击连接走 `Direct`，库已内置 `retry + retryDelay`，失败抛 `ConnectionFailedException(reason)`。
- **断线恢复**：之后切到 `AutoConnect`，由系统协议栈完成断连恢复，不耗 App 电；`centralManager.connect(...)` 会一直 suspend 直到设备真正可达。

### 9.2 单设备会话：PerDeviceSession（带断线自动重连）

库 v2.0 **没有** `DeviceDisconnectedException`，断连信号通过三条途径表达：

1. `peripheral.state: StateFlow<ConnectionState>` 转到 `ConnectionState.Disconnected(reason)`；其中 `reason` 可能是 `LinkLoss` / `Timeout(duration)` / `TerminatePeerUser` / `TerminateLocalHost` / `UnsupportedAddress` / `RequiredServiceNotFound` / `Cancelled` / `Success` / `Unknown(status)` 等（见 `ConnectionState.Disconnected.Reason`）。
2. `centralManager.connect(peripheral, Direct(...))` 会在首连失败时抛 `ConnectionFailedException(reason)`；使用 `Direct` 时连接 scope 在断开后被库自动取消，scope 内未完成的 GATT 操作会抛 `PeripheralNotConnectedException`。
3. `peripheral.profile(serviceUuid, required = true)` 在服务缺失时**不抛异常**，而是把连接状态切到 `Disconnected(reason = RequiredServiceNotFound)`。要识别"设备根本没有这个 Service"必须观察 `peripheral.state`，不能 try/catch profile。

一台设备的完整生命周期封装到 `PerDeviceSession`：首连用 `Direct`，后续切 `AutoConnect`，会话级 `while` 循环承载重连。**一台设备的失败完全不影响另一台设备的会话。**

> **关于 profile 注册顺序**：库 API 文档明确建议 **`peripheral.profile(serviceUuid, ...)` 在 `connect(...)` 之前调用**——profile 内部走 `services()` flow，本身就能等服务发现完成。本设计为了让 `_state.value` 状态机更线性，把 profile 注册放在 connect 之后（与 sample 行为一致，可工作），代价是一次 `RequiredServiceNotFound` 断连。如果以后想把 `Subscribing` 状态发布得更早，可以重排为：先注册 profile，再 `connect()`，再 `awaitDisconnection()`。

```kotlin
class PerDeviceSession(
    val role: DeviceRole,
    val peripheral: Peripheral,
    private val centralManager: CentralManager,
    private val profileFactory: ProfileFactory,
    private val pipelineFactory: CollectionPipeline.Factory,
    private val sinkFactory: SensorDataSink.Factory,
) {
    private val _state = MutableStateFlow<DeviceState>(DeviceState.Connecting)
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    suspend fun run() = try {
        var attempt = 0
        while (coroutineContext.isActive) {
            val options = if (attempt == 0) firstConnectOptions else reconnectOptions
            attempt += 1
            try {
                _state.value = DeviceState.Connecting
                centralManager.connect(peripheral, options)
                runConnected()
                // runConnected 正常返回 = 设备断开但 scope 未异常。
                // 此时 peripheral.state.value 是 Disconnected(reason)；据 reason 决定是否继续 while。
                val reason = (peripheral.state.value as? ConnectionState.Disconnected)?.reason
                when (reason) {
                    // 设备根本没这个 Service，再重连结果一样 → 终止该角色，其它角色不受影响
                    ConnectionState.Disconnected.Reason.RequiredServiceNotFound -> {
                        _state.value = DeviceState.Failed(DeviceState.Reason.DeviceNotSupported)
                        break
                    }
                    // MAC 已轮换 → 走重扫流程（由 UI 触发 onScanForKnownDevice / replaceDevice）
                    ConnectionState.Disconnected.Reason.UnsupportedAddress -> {
                        _state.value = DeviceState.Failed(DeviceState.Reason.Disconnected)
                        break
                    }
                    // 其余 LinkLoss / Timeout / TerminatePeerUser / Unknown → 走 AutoConnect 重连循环
                    else -> _state.value = DeviceState.Connecting
                }
            } catch (e: ConnectionFailedException) {
                Timber.w("[$role] connect failed (attempt=$attempt): ${e.reason}")
                _state.value = DeviceState.Failed(DeviceState.Reason.ConnectionTimeout, e)
                delay(2.seconds)
            } catch (e: PeripheralNotConnectedException) {
                Timber.w("[$role] dropped during operation, will reconnect")
                _state.value = DeviceState.Connecting
            } catch (e: InvalidAttributeException) {
                Timber.w("[$role] services invalidated, will reconnect")
                _state.value = DeviceState.Connecting
            } catch (e: NoSuchElementException) {
                Timber.w("[$role] characteristic missing inside profile block; treating as unsupported")
                _state.value = DeviceState.Failed(DeviceState.Reason.DeviceNotSupported, e)
                break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        _state.value = DeviceState.Failed(e.toReason(), e)
        Timber.e(e, "[$role] session terminated")
    } finally {
        runCatching { peripheral.disconnect() }
        _state.value = DeviceState.Stopped
    }

    private suspend fun runConnected() = coroutineScope {
        peripheral.state
            .onEach { Timber.i("[$role] ${peripheral.address} -> $it") }
            .launchIn(this)

        profileFactory.install(peripheral, this) { profile ->
            _state.value = DeviceState.Subscribing
            // 注意：每个 role 拿一个独立的 Sink 实例，落自己的文件
            val sink = sinkFactory.create(role, peripheral.address)
            val pipeline = pipelineFactory.create(role, sink)
            val pipeJob = pipeline.start(this, profile.samples)        // 强类型 Flow<TSample>

            _state.value = DeviceState.Collecting(role, 0, 0)
            pipeline.counters.onEach { (saved, invalid) ->
                _state.update {
                    (it as? DeviceState.Collecting)?.copy(savedCount = saved, invalidCount = invalid) ?: it
                }
            }.launchIn(this)

            try { pipeJob.join() } finally { sink.close() }
        }

        // 等待 peripheral 真的断开后再返回，让外层 while 可以读 peripheral.state.value 判断 reason。
        peripheral.state.first { it is ConnectionState.Disconnected }
    }
}
```

### 9.3 多设备编排：MultiDeviceController

顶层只持有"会话 (Session)"概念：一个 Session = 一组 `Map<DeviceRole, Peripheral>`，**大小可以是 1（单设备会话）也可以是 N（多角色对照）**。每个角色启一个 `PerDeviceSession`，跑在同一个 `supervisorScope` 下的兄弟协程——其中一个失败抛异常不会取消另一个。单设备会话退化为只有一个子协程的特殊情况，代码路径完全相同。

```kotlin
@Singleton
class MultiDeviceController @Inject constructor(
    private val centralManager: CentralManager,
    private val profileRegistry: ProfileRegistry,
    private val pipelineFactory: CollectionPipeline.Factory,
    private val sinkFactory: SensorDataSink.Factory,
    private val sessionStore: CollectionSessionStore,
    @AppScope private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private val sessions = mutableMapOf<DeviceRole, PerDeviceSession>()

    /**
     * 一次性传入本次会话所有角色的设备分配。
     *
     * - [DeviceAssignment.devices] 必须非空（size >= 1）。
     * - 单设备会话（如只填 DUT 或只填 REFERENCE）走完全相同的代码路径，
     *   只是 supervisorScope 下的兄弟协程数量退化为 1。
     * - 多设备会话（DUT + REFERENCE，或未来更多角色）也是同样的循环展开。
     *
     * UI 层在 DeviceAssignmentScreen 内组装 [DeviceAssignment] 后调用。
     * 注意：FGS 必须由 UI 在调用本方法前先 startForegroundService（§13.1）。
     */
    fun start(assignment: DeviceAssignment) {
        require(assignment.devices.isNotEmpty()) { "DeviceAssignment must contain at least one device" }
        if (sessionJob?.isActive == true) return
        sessions.clear()

        // 1) 构造 PerDeviceSession
        assignment.devices.forEach { (role, peripheral) ->
            sessions[role] = PerDeviceSession(
                role, peripheral, centralManager,
                profileRegistry[role], pipelineFactory, sinkFactory,
            )
        }

        // 2) 派生聚合状态：把每个 PerDeviceSession.state 合到 SessionState
        val statesFlow: Flow<Map<DeviceRole, DeviceState>> =
            combine(sessions.map { (role, s) -> s.state.map { role to it } }) { pairs ->
                pairs.toMap()
            }

        // 3) 在 supervisorScope 下并行启所有设备；状态聚合也挂在同一个 sessionJob 下，
        // stop() 取消 sessionJob 时不会留下悬挂的 collect。
        sessionJob = appScope.launch(CoroutineName("session")) {
            supervisorScope {
                launch(CoroutineName("session-state")) {
                    statesFlow.collect { perDevice ->
                        _state.value = SessionState.Running(perDevice)
                    }
                }

                // 先持久化"用户想恢复的会话"，各 PerDeviceSession 进入采集后再补齐文件名和计数。
                sessionStore.markStarted(assignment)

                sessions.values.forEach { perDevice ->
                    launch(CoroutineName("dev-${perDevice.role}")) { perDevice.run() }
                }
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        sessions.clear()
        _state.value = SessionState.Idle
        appScope.launch { sessionStore.markStopped() }
    }

    /** 单设备热替换 / 重连：拿掉一台设备的 PerDeviceSession，换一个新的 peripheral。 */
    fun replaceDevice(role: DeviceRole, newPeripheral: Peripheral) {
        sessions[role]?.also {
            // 这里需要单独取消该 PerDeviceSession 对应的 child Job
            // 实现细节略，思路是给每个 PerDeviceSession 关联一个 Job 引用
            // 取消旧 role job → 创建新的 PerDeviceSession → 在同一个 session scope 下重新 launch
        }
    }
}
```

### 9.4 会话级状态与"部分失败"语义

```kotlin
sealed interface DeviceState {
    data object Connecting : DeviceState
    data object Subscribing : DeviceState
    data class Collecting(val role: DeviceRole, val savedCount: Long, val invalidCount: Long) : DeviceState
    data class Failed(val reason: Reason, val cause: Throwable? = null) : DeviceState
    data object Stopped : DeviceState

    enum class Reason {
        ConnectionTimeout, DeviceNotSupported, SubscriptionFailed,
        WriteFailed, Disconnected, PermissionDenied, BluetoothOff,
        StorageFailed, Unknown,
    }
}

sealed interface SessionState {
    data object Idle : SessionState
    data class Running(val byRole: Map<DeviceRole, DeviceState>) : SessionState {
        /** 全部成功在 Collecting → "全部正常" */
        val allCollecting: Boolean get() = byRole.values.all { it is DeviceState.Collecting }
        /** 至少一台失败 → UI 显示 partial-degraded */
        val anyFailed: Boolean get() = byRole.values.any { it is DeviceState.Failed }
        /** 全部失败 → 整个会话不可用 */
        val allFailed: Boolean get() = byRole.isNotEmpty() && byRole.values.all { it is DeviceState.Failed }
    }
}
```

**关键决策**：

- **某个角色失败不影响其它角色**：会话内部用 `supervisorScope` 让兄弟协程彼此独立；多设备时 UI 显示"REFERENCE 已连接，DUT 正在重连"是正常态；**单设备会话只有一个角色，"部分失败"退化为"该设备失败"**。
- **全局失败的情况才停整个会话**：`SessionState.Running.allFailed` 或 `SessionState` 中某个 `DeviceState.Failed(reason)` 是 `BluetoothOff / PermissionDenied`（这俩是 BluetoothAdapter 全局事件，所有设备一起断）。单设备会话下 `allFailed` 等价于 `anyFailed`。
- **用户可主动剔除某个角色重选**：UI 上每个角色卡片右上角有"换一台"按钮，调 `controller.replaceDevice(role, newPeripheral)`。多设备会话中换一个不影响另一个；单设备会话相当于重新选设备。
- **运行中追加 / 移除角色**（可选，二期）：未来如果允许"先 DUT 单独跑，半程加入 REFERENCE 做对照"，可在 `MultiDeviceController` 上新增 `addDevice(role, peripheral)` / `removeDevice(role)` 方法，基于现有 `supervisorScope` 下挂兄弟协程的方式实现。本期为了简化，会话边界 = `start(assignment)` ~ `stop()`。

### 9.5 Android BLE 多连接的实际限制

| 限制 | 实际值 | 影响 |
| --- | --- | --- |
| 同时连接的最大数量 | SIG 规范允许 7；多数 OEM 实际可靠在 4–6 | 本 App 单设备 / 双设备远在限制内 |
| 多连接下的 MTU | 每个连接独立协商 MTU，互不干扰 | 各 profile 独立调 `requestHighestValueLength` |
| 并行 GATT 操作 | 同一连接内部 GATT 操作必须串行（库 v2.0 内部已串行化） | 不同连接的操作可并行 |
| 通知接收带宽 | 同一 host 总带宽有限（约 6 packets/connection interval） | 高频 PPG (≥ 100 Hz) + HR (1 Hz) 完全够 |
| 与 Wi-Fi 共存 | 2.4G 频段争用，可能丢包 | 高频采集建议提示用户切 5G Wi-Fi |

**结论**：
- **单设备会话**对 Android BLE 协议栈是最轻量的负载，所有手机都可靠运行。
- **2 设备并行采集**对协议栈仍是轻量级负载，**不需要任何特殊优化**。
- **扩展到 4 个角色仍可靠**；超过 5 个建议做压力测试，并提示用户检查 Wi-Fi 频段。

### 9.6 取舍说明

- **首连用 `Direct`**：失败语义清晰（`ConnectionFailedException(reason)`），能快速反馈"设备不在范围"。
- **重连切 `AutoConnect`**：走系统协议栈，不耗 App 唤醒，设备一回到范围立刻恢复。
- **`while` 循环退出条件**：只有 `CancellationException`（用户 `stop()` 或 `appScope` 销毁）和非可恢复异常（`SecurityException` 等）。
- **若要"连续重连 N 次仍失败就停"**：把 `attempt` 与 `maxAttempts` 比较，超出 `break` 即可，只影响当前设备，其他角色不受影响。

### 9.7 异常 → Reason 映射

```kotlin
fun Throwable.toReason(): DeviceState.Reason = when (this) {
    is TimeoutCancellationException     -> DeviceState.Reason.ConnectionTimeout
    is ConnectionFailedException        -> DeviceState.Reason.ConnectionTimeout
    is PeripheralNotConnectedException  -> DeviceState.Reason.Disconnected
    is InvalidAttributeException        -> DeviceState.Reason.Disconnected
    is NoSuchElementException           -> DeviceState.Reason.DeviceNotSupported
    is SecurityException                -> DeviceState.Reason.PermissionDenied
    is IOException                      -> DeviceState.Reason.StorageFailed
    is OperationFailedException         -> when (reason) {
        OperationStatus.SubscribeNotPermitted -> DeviceState.Reason.SubscriptionFailed
        OperationStatus.WriteNotPermitted,
        OperationStatus.RequestNotSupported,
        OperationStatus.GattError             -> DeviceState.Reason.WriteFailed
        else                                  -> DeviceState.Reason.Unknown
    }
    else -> DeviceState.Reason.Unknown
}

/**
 * 库的 [Disconnected.Reason] → 应用层 Reason（在 PerDeviceSession.run 的 while 内使用）。
 */
fun ConnectionState.Disconnected.Reason?.toAppReason(): DeviceState.Reason = when (this) {
    null,
    ConnectionState.Disconnected.Reason.Success                  -> DeviceState.Reason.Disconnected
    ConnectionState.Disconnected.Reason.RequiredServiceNotFound  -> DeviceState.Reason.DeviceNotSupported
    is ConnectionState.Disconnected.Reason.Timeout               -> DeviceState.Reason.ConnectionTimeout
    ConnectionState.Disconnected.Reason.LinkLoss,
    ConnectionState.Disconnected.Reason.TerminatePeerUser,
    ConnectionState.Disconnected.Reason.TerminateLocalHost,
    ConnectionState.Disconnected.Reason.Cancelled,
    ConnectionState.Disconnected.Reason.UnsupportedAddress       -> DeviceState.Reason.Disconnected
    ConnectionState.Disconnected.Reason.InsufficientAuthentication,
    ConnectionState.Disconnected.Reason.UnsupportedConfiguration -> DeviceState.Reason.Unknown
    is ConnectionState.Disconnected.Reason.Unknown               -> DeviceState.Reason.Unknown
}
```

UI 层状态来源：`combine(controller.state, env.bluetoothState, env.locationState) → UiState`，**蓝牙/定位状态来自 `AndroidEnvironment`，不自己监听 BroadcastReceiver**。

## 10. 数据解析层（纯 Kotlin）

每个角色（`DUT` / `REFERENCE` / ...）有自己的 `Parser<TSample>`，**互不影响**：

- `DUT`：`DutDataParser` —— 私有协议，按 §8.3 占位帧格式做最小长度 / 帧头 / checksum 校验。
- `REFERENCE`：解析直接内嵌在 `HeartRateProfileImpl.parseMeasurement(...)`（§8.4），因为 SIG HRS 字节布局完全标准化，没必要再抽一层。

### 10.1 通用 ParseResult

```kotlin
sealed interface ParseResult<out T : SensorSample> {
    data class Success<T : SensorSample>(val data: T) : ParseResult<T>
    data class InvalidFrame(val reason: String, val raw: ByteArray) : ParseResult<Nothing>
}

fun interface SensorDataParser<T : SensorSample> {
    fun parse(raw: ByteArray,
              receivedAt: Long = System.currentTimeMillis(),
              monotonicNanos: Long = System.nanoTime()): ParseResult<T>
}
```

### 10.2 DUT 解析（私有协议占位）

```kotlin
class DutDataParser : SensorDataParser<DutSample> {
    override fun parse(raw: ByteArray, receivedAt: Long, monotonicNanos: Long): ParseResult<DutSample> {
        if (raw.size < MIN_LEN) return ParseResult.InvalidFrame("too short ${raw.size}", raw)
        if (raw[0] != 0xAA.toByte() || raw[1] != 0x55.toByte())
            return ParseResult.InvalidFrame("bad header", raw)
        // ... LEN / SEQ / PPG / CHECKSUM / 0x0D 0x0A 校验 ...
        return ParseResult.Success(DutSample(
            receivedAtEpochMillis = receivedAt,
            monotonicNanos = monotonicNanos,
            sequence = /* ... */ 0,
            ppgValue = /* ... */ 0,
            derivedBpm = /* ... */ null,
        ))
    }
    companion object { private const val MIN_LEN = 10 }
}
```

帧格式占位（拿到真实协议替换）：

```text
AA 55 | LEN | SEQ | PPG_LO PPG_HI | BPM | CHECKSUM | 0D 0A
```

### 10.3 设计原则

- 不抛异常到上层，解析失败返回 `InvalidFrame`。
- `monotonicNanos` 用 `System.nanoTime()`，墙钟跳变（NITZ / 用户改时间）也能算真实采样间隔。
- **时间戳必须在 Profile 收到 notify 的同一时刻打**（在 `subscribe().map { ... }` 第一时间），而不是落 sink 时再打——后者会包含队列等待时间，丢失精度。

## 11. 数据流水线（按角色独立运行，泛型化）

每个 `PerDeviceSession` 拥有一个 `CollectionPipeline<TSample>`，**与其它角色完全隔离**：DUT 的解析失败不会影响 REFERENCE 的落盘。

```kotlin
class CollectionPipeline<T : SensorSample>(
    private val role: DeviceRole,
    private val sink: SensorDataSink<T>,
) {
    private val _saved = MutableStateFlow(0L)
    private val _invalid = MutableStateFlow(0L)
    val counters: Flow<Pair<Long, Long>> = combine(_saved, _invalid, ::Pair)

    /**
     * source 是 Profile 已经解析过的强类型 Flow<TSample>。
     * 严禁使用 GlobalScope；scope 必须由 PerDeviceSession 传入，
     * 这样连接断开 → scope 取消 → pipeline 协程自动终止。
     */
    fun start(scope: CoroutineScope, source: Flow<T>): Job = source
        .buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        .onEach { sample ->
            sink.write(sample)
            _saved.update { it + 1 }
        }
        .catch { Timber.e(it, "[$role] pipeline crashed") }
        .launchIn(scope)

    /** 由调用方负责传入"解析失败计数" Flow（直接从 profile 暴露的话）。 */
    fun observeInvalid(scope: CoroutineScope, invalidFromProfile: Flow<ParseResult.InvalidFrame>) {
        invalidFromProfile
            .onEach {
                _invalid.update { it + 1 }
                Timber.w("[$role] invalid frame: ${it.reason}")
            }
            .launchIn(scope)
    }

    fun interface Factory {
        fun <T : SensorSample> create(role: DeviceRole, sink: SensorDataSink<T>): CollectionPipeline<T>
    }
}
```

> 关键变化：上游 `source` 不再是原始 `Flow<ByteArray>`，而是 Profile 解析后的 `Flow<TSample>`。**Parser 与 Profile 绑定（§8.3 / §8.4），Pipeline 只做"落盘 + 统计"**。这样泛型不会爆炸到 Pipeline 里。

背压策略：

- 默认 `BufferOverflow.SUSPEND`：BLE 流被反压时降速（采集场景宁可慢一点不丢数据）。
- 高频 PPG (≥ 200 Hz) 场景如果担心反压拖慢通知接收，可切 `BufferOverflow.DROP_OLDEST`，并把丢弃计数也暴露到 UI。

## 12. 本地文件保存（D-6：每会话一文件夹 · 原始 HEX 日志为主体 + 解码 CSV + 会话 manifest）

### 12.1 目录结构

```text
/Android/data/com.example.bluetrace/files/Documents/BlueTrace/
└── session_20260520_141532/                 ← 一次会话一个目录
    ├── session_manifest.json                ← 元数据：开始/结束、设备列表、对齐点
    ├── DUT_AA-BB-CC-DD-EE-01.csv            ← 待测设备数据
    ├── DUT_AA-BB-CC-DD-EE-01.csv.1          ← 文件滚动后的下一片
    └── REFERENCE_AA-BB-CC-DD-EE-02.csv      ← 参考心率带数据
```

**关键决策**：每次会话一个独立目录，方便整体导出/上传；每角色一个 CSV，避免在写入端做时间对齐（对齐留给后期分析）。

### 12.2 会话目录与文件命名

```kotlin
class SessionDirectory @Inject constructor(@ApplicationContext context: Context) {
    private val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        .let { File(it, "BlueTrace") }.apply { mkdirs() }

    fun newSession(): File {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        return File(root, "session_$ts").apply { mkdirs() }
    }

    fun csvFor(sessionDir: File, role: DeviceRole, address: String): File {
        val safeAddr = address.replace(":", "-")
        return File(sessionDir, "${role.name}_$safeAddr.csv")
    }
}
```

**路径无需任何存储运行时权限**（API 24+ Scoped 路径）。

### 12.3 SensorDataSink<T>（泛型 Sink）

```kotlin
interface SensorDataSink<T : SensorSample> {
    suspend fun write(sample: T)
    suspend fun close()
    fun interface Factory {
        fun <T : SensorSample> create(role: DeviceRole, address: String): SensorDataSink<T>
    }
}
```

实现仍走 Channel + 单消费者协程，**CSV 表头由 role 对应的 sample 类型动态生成**（§8.1 的 `csvHeader(role)`）：

```kotlin
class CsvSensorDataSink<T : SensorSample>(
    private val outputFile: File,
    private val role: DeviceRole,
    scope: CoroutineScope,
    private val rotateBytes: Long = 16L * 1024 * 1024,
) : SensorDataSink<T> {

    private val channel = Channel<T>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val writerJob: Job = scope.launch(Dispatchers.IO) {
        var writer = openWriter(outputFile, header = !outputFile.exists() || outputFile.length() == 0L)
        var bytes = outputFile.length()
        var pending = 0
        var lastFlush = System.currentTimeMillis()
        try {
            for (sample in channel) {
                val line = sample.csvRow().joinToString(",") + "\n"
                writer.write(line)
                bytes += line.length
                if (++pending >= 50 || System.currentTimeMillis() - lastFlush > 1_000) {
                    writer.flush(); pending = 0; lastFlush = System.currentTimeMillis()
                }
                if (bytes >= rotateBytes) {
                    writer.close()
                    val rotated = nextRotatedFile(outputFile)
                    writer = openWriter(rotated, header = true)
                    bytes = 0
                }
            }
        } finally { writer.flush(); writer.close() }
    }

    override suspend fun write(sample: T) = channel.send(sample)
    override suspend fun close() { channel.close(); writerJob.join() }

    private fun openWriter(file: File, header: Boolean): BufferedWriter {
        val w = FileOutputStream(file, /* append = */ true).bufferedWriter(Charsets.UTF_8)
        if (header) w.write(csvHeader(role).joinToString(",") + "\n")
        return w
    }

    private fun nextRotatedFile(original: File): File {
        // DUT_AA-BB-CC-DD-EE-01.csv → DUT_AA-BB-CC-DD-EE-01.csv.1 / .2 / ...
        val base = original.name
        var idx = 1
        while (File(original.parentFile, "$base.$idx").exists()) idx++
        return File(original.parentFile, "$base.$idx")
    }
}
```

### 12.4 会话 Manifest（用于多设备时间对齐）

会话开始时写一个 JSON，记录"绝对时间锚点"与"monotonic 锚点"，后期把两台设备数据合在一起时**用 monotonic 差对齐**，不受系统墙钟跳变影响：

```kotlin
@Serializable
data class SessionManifest(
    val sessionId: String,                     // 例如 "session_20260520_141532"
    val startEpochMillis: Long,
    val startMonotonicNanos: Long,             // 会话起点的 System.nanoTime()，所有 sample.monotonicNanos 都减它
    val endEpochMillis: Long? = null,
    val devices: List<DeviceEntry>,
) {
    @Serializable
    data class DeviceEntry(
        val role: String,                      // "DUT" / "REFERENCE"
        val address: String,
        val name: String?,
        val csvFiles: List<String>,            // 主文件 + 滚动文件
        val profileId: String,                 // "Dut.v1" / "HeartRate.SIG.0x180D"
    )
}

class SessionManifestWriter @Inject constructor(private val json: Json) {
    fun write(dir: File, manifest: SessionManifest) {
        File(dir, "session_manifest.json").writeText(json.encodeToString(manifest))
    }
}
```

**对齐方法**（后期 Python / Pandas 分析）：

```python
# DUT 第 i 行 monotonicNanos：t_dut_i
# REFERENCE 第 j 行 monotonicNanos：t_ref_j
# manifest.startMonotonicNanos：t0
# 共同时间轴：(t - t0) / 1e9 秒
```

因为两台设备的 sample 都用同一台手机的 `System.nanoTime()` 打戳，**它们天然处于同一时间轴上**，对齐误差就是 BLE 通知到达 App 的时延差（典型 < 50 ms，对 PPG 评测足够）。如果要更精准的对齐，需要在设备侧做时间戳同步，超出本文档范围。

### 12.5 导出到 Downloads（minSdk 29 → 纯 MediaStore）

> **⚠️ V4 / D-7 口径（覆盖本节历史表述）**：minSdk = 29，导出**统一走 MediaStore**，无需任何存储运行时权限；下方"API 24–28 直接复制 + `WRITE_EXTERNAL_STORAGE`"分支已**作废**，仅作历史参考。导出对象为 **D-6 会话文件夹**（打包整文件夹到 `Download/BlueTrace/`）。

**API 29+ 走 MediaStore**（推荐路径，无需任何存储运行时权限）；
~~**API 24–28 直接复制到公共 `Environment.DIRECTORY_DOWNLOADS/BlueTrace/` 目录**，前提是已经申请到 `WRITE_EXTERNAL_STORAGE`~~（**作废**，minSdk 29 不再需要）。

这样能保持 Repository 层纯净，**不需要把 SAF 的 `ActivityResultLauncher` 入侵 ViewModel / Repository**。

```kotlin
suspend fun exportCsvToDownloads(
    context: Context,
    sourceFile: File,
    displayName: String = sourceFile.name,
): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        exportViaMediaStore(context, sourceFile, displayName)
    } else {
        exportViaLegacyPublicDir(context, sourceFile, displayName)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun exportViaMediaStore(
    context: Context, sourceFile: File, displayName: String,
): Uri {
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueTrace")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: error("MediaStore.insert returned null")
    resolver.openOutputStream(uri)!!.use { out ->
        sourceFile.inputStream().use { it.copyTo(out) }
    }
    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

/** API 24-28 降级：直接写公共 Downloads 目录。需要已申请 WRITE_EXTERNAL_STORAGE 运行时权限。 */
private fun exportViaLegacyPublicDir(
    context: Context, sourceFile: File, displayName: String,
): Uri {
    @Suppress("DEPRECATION")
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val destDir = File(downloads, "BlueTrace").apply { mkdirs() }
    val destFile = File(destDir, displayName)
    sourceFile.copyTo(destFile, overwrite = true)
    // 让文件管理器立刻能看到新文件
    MediaScannerConnection.scanFile(
        context, arrayOf(destFile.absolutePath), arrayOf("text/csv"), null,
    )
    return Uri.fromFile(destFile)
}
```

调用前置：在 ViewModel 触发导出时，先用 `BlePermissionFacade` 看一眼 `Build.VERSION.SDK_INT <= 28 && !storageGranted`，缺权限就交由 UI 发起 `ActivityResultContracts.RequestPermission(WRITE_EXTERNAL_STORAGE)`，授权完成后再回到 ViewModel 调 `exportCsvToDownloads`。这种"权限请求停留在 UI 层，导出逻辑停在仓储/ViewModel 层"的分割保持架构干净。

## 13. 后台采集（Foreground Service，Android 14+ 适配）

### 13.1 启动时机

Android 12+（API 31+）后台启动 FGS 会抛 `ForegroundServiceStartNotAllowedException`。本 App 的 FGS **必须由用户在前台的显式动作触发**：

1. 用户在 `DataCollectionScreen` 点击"开始采集"。
2. ViewModel 收到事件后，**先** `ContextCompat.startForegroundService(context, intent)` 拉起 Service，**再** 调用 `MultiDeviceController.start(assignment)`。
3. Service 在 `onCreate` 里 5 秒内必须 `startForeground(...)`，否则系统强杀。

> 不要在 `BroadcastReceiver`（包括蓝牙状态变化、设备解绑后唤醒）/ `JobScheduler` / `WorkManager` 后台任务里直接 `startForegroundService(...)`。如果一定要从后台恢复采集（例如蓝牙重开），先看 [允许从后台启动 FGS 的豁免列表](https://developer.android.com/develop/background-work/services/foreground-services#background-start-restrictions)，多数情况下走"发通知让用户点回 App"是最稳的。

### 13.2 Service 实现（含 WakeLock）

高频 BLE 采集（≥ 10 Hz）在国产 ROM Doze 或 CPU 空闲时容易掉包，必须持 `PARTIAL_WAKE_LOCK`：

```kotlin
@AndroidEntryPoint
class CollectionForegroundService : Service() {

    @Inject lateinit var controller: MultiDeviceController

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()

        // 1. WakeLock —— 保持 CPU 运行
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlueTrace::Collection",
        ).apply {
            setReferenceCounted(false)
            @SuppressLint("WakelockTimeout")
            acquire()
        }

        // 2. 通知（channel 在 Application.onCreate 中创建）
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueTrace 采集中")
            .setContentText("正在记录 BLE 数据")
            .setSmallIcon(R.drawable.ic_ble)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // 3. startForeground，Android 14+ 必须显式声明类型
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else 0,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException / MissingForegroundServiceTypeException
            Timber.e(e, "Failed to enter foreground; stopping self")
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.stop()                                // §9 的 sessionJob 被 cancel
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "bluetrace_collection"
        const val NOTIFICATION_ID = 1001
    }
}
```

### 13.3 启动前的运行时检查

启动 Service 前必须满足以下条件；其中 `POST_NOTIFICATIONS` 是用户可见性前置，不是 Android 14+ connected-device 类型的启动硬前置：

| 条件 | 验证方式 |
| --- | --- |
| `BLUETOOTH_CONNECT` 已授权（API 31+） | `env.isBluetoothConnectPermissionGranted` |
| `POST_NOTIFICATIONS` 已授权（API 33+，建议） | `ContextCompat.checkSelfPermission(...)` |
| 通知 channel 已存在 | `BlueTraceApplication.onCreate` 中 `NotificationManagerCompat.createNotificationChannel` |
| App 处于前台 | UI 层用户点击触发，不要后台调用 |

`BlePermissionFacade.canStartForegroundCollection(): Boolean` 把硬前置检查合在一处，UI 在点击 Start 前先调它；`POST_NOTIFICATIONS` 单独作为建议授权处理，用户拒绝时仍允许启动采集，但 UI 要明确提示通知抽屉不可见。

### 13.4 扫描 vs 采集：为什么不能用同一种后台机制

很容易把"BLE 后台"想成单一问题。其实**扫描**和**采集**的后台模型完全不同，必须分开处理：

| 维度 | 扫描（Scan） | 采集（Collection） |
| --- | --- | --- |
| 何时发生 | 用户主动找设备，**仅几秒到十几秒** | 连接成功后**持续数小时甚至数天** |
| 系统限制 | Android 7+：30 s 内最多 5 次；Android 8+ 后台扫描受 PendingIntent 模式约束（**库 v2.0 暂未封装**） | 连接成功后无主动扫描，系统只对 GATT 操作做正常 power management |
| 推荐机制 | **仅前台 ViewModel 内执行**，10 s 超时，发现目标即停 | **Foreground Service + Singleton CentralManager + WakeLock** |
| WorkManager 适用吗 | ❌ 不适用（扫描是 UI 即时操作） | ❌ **不适用**（WorkManager 单次执行上限 10 分钟，且不保证 CPU 不睡） |
| App 切后台行为 | 扫描已经结束 / 或用户停止 | FGS 保持进程优先级，连接和数据流不中断 |
| App 进程被杀行为 | N/A | 见 §13.5、§13.6 |

**结论**：
- **扫描永远停在 ViewModel + `viewModelScope`/`AppScope`，不进 FGS**。代码就是 §7 的 `ScanViewModel`。
- **采集走 FGS**，由 `CollectionForegroundService` 持有 `MultiDeviceController`，会话级 `while` 循环 + `AutoConnect` 兜底（§9.2 已写）。
- **WorkManager 不承担"保持连接"职责**，它是给"周期上传 CSV 到服务器"、"每日清理过期文件"、"FGS 被杀后的恢复通知"这类**可延迟、可重试**任务用的（§17.2）。

### 13.5 完整生命周期时序（含每个角色谁保活谁）

```text
T0  用户启动 App
    └─ Application.onCreate
         ├─ Timber.plant + NotificationChannel.create
         └─ Hilt 创建 Singletons:
              ├─ NativeAndroidEnvironment   ┐
              ├─ AppScope (SupervisorJob)   ├─ 与进程同生命周期，
              └─ CentralManager             ┘   Activity 销毁不会 close

T1  用户授予权限 → ScanScreen 显示
    └─ ScanViewModel.onStartScan()
         └─ centralManager.scan(10.s).launchIn(AppScope)
              （即使用户瞬间退到桌面，扫描也会跑完 10 s 才停）

T2  用户为 DUT / REFERENCE 各选择一个设备 → 跳到 DataCollectionScreen
    └─ DataCollectionViewModel.onStart(assignment)
         ├─ ContextCompat.startForegroundService(intent)   ← 必须先启 Service
         └─ controller.start(assignment)
              └─ AppScope.launch { supervisorScope { ... } } ← 会话协程组
                   ├─ launch { PerDeviceSession(DUT).run() }
                   └─ launch { PerDeviceSession(REFERENCE).run() }
                        └─ 各角色进入 Subscribing/Collecting 时写入 pending

T3  Service.onCreate
    ├─ wakeLock.acquire()
    ├─ ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    └─ （Controller 已经在 T2 启动，Service 主要起"前台优先级 + WakeLock"的支架作用）

T4  用户按 Home / 锁屏 / 关闭 Activity
    ├─ MainActivity.onDestroy
    ├─ ViewModel.onCleared
    │    └─ ⚠️ 但 MultiDeviceController 是 Singleton，sessionJob 跑在 AppScope，
    │       与 ViewModel 无关 → 继续采集
    └─ Service 仍在前台 → CPU 不睡 → BLE 连接活着 → 数据继续落盘

T5  用户重新打开 App
    ├─ Activity 重建 → ViewModel 重建
    └─ ViewModel.init { observe controller.state }
         └─ 直接 collectAsStateWithLifecycle 显示 Collecting(savedCount, ...)
            UI 无缝接回正在运行的会话

T6  用户点击 Stop
    ├─ controller.stop() → sessionJob.cancel()
    │    └─ finally { peripheral.disconnect() }
    ├─ stopService(intent)
    └─ Service.onDestroy { wakeLock.release() }
```

**关键不变量**：
- `CentralManager` / `Peripheral` 引用从未跨 Activity 边界传递（避免泄漏），所有持有者都在 Singleton 层（`MultiDeviceController` 内部）。
- ViewModel 只通过 `controller.state: StateFlow<SessionState>` 观察会话；ViewModel 销毁不影响会话。
- FGS 提供的不是"业务逻辑容器"而是"进程优先级 + WakeLock 支架"，业务在 `AppScope` 跑，FGS 起的是**保活作用**。

### 13.6 设备识别：绑定 vs 未绑定（本 App 的场景）

> **本 App 的目标 BLE 设备不使用绑定 (no bonding / Just Works without bonding)**。这一节先把两种路径的差异讲清，再说本 App 走的"未绑定路径"该怎么实现。

#### 13.6.1 两条路径对比

| 维度 | 绑定 (Bonded) | **未绑定 (本 App)** |
| --- | --- | --- |
| 设备识别 API | `centralManager.getBondedPeripherals(): List<Peripheral>` | `centralManager.getPeripheralById(address): Peripheral?` |
| 是否需要扫描 | **不需要**，开机即可 `AutoConnect` | 首次需要扫描用于用户选择和记录地址；之后可用持久化地址构造 `Peripheral` |
| 系统重启后 | `BluetoothAdapter` 持久化绑定列表，**不丢** | 地址仍可用于构造 `Peripheral`，但若设备使用可轮换随机地址或不在范围内，连接无法自动完成 |
| App 清数据 / 卸载重装 | 绑定信息保留在系统层，重装即可识别 | App 内 DataStore 被清，**首次启动等同新设备**，必须重新扫描 |
| `getPeripheralById` 的可用窗口 | 永久（直到用户在系统设置里取消配对） | 对稳定 MAC 可用；对可轮换随机地址不可靠 |
| 安全 | 链路层加密 (LE Secure / LE Legacy) | 明文 GATT 通信 |
| 用户体验 | 第一次连接弹"配对"对话框 | 无系统弹窗 |

#### 13.6.2 本 App 的选择：未绑定 + MAC 持久化 + 必要时重扫

既然你的 BLE 设备不开绑定，**无人值守的开机即重连能力弱于绑定设备**，因此架构必须接受这个现实：

> **持久化 MAC 是必要的，但不充分**。本库 Android 实现可以通过 `getPeripheralById(address)` 构造 `Peripheral`，但连接是否完成取决于设备是否仍使用同一地址、是否在范围内、是否继续广播/可连接。若 DUT 使用 Resolvable Private Address 且没有绑定关系，持久化 MAC 可能失效，必须 fallback 到按 Service UUID / 名称重新扫描，或改造设备协议支持绑定。

```kotlin
class CollectionResumer @Inject constructor(
    private val centralManager: CentralManager,
    private val controller: MultiDeviceController,
    private val store: CollectionSessionStore,
    private val env: AndroidEnvironment,
) {
    sealed interface ResumeResult {
        data object NothingToResume : ResumeResult
        data class RequiresUserAction(val reason: String) : ResumeResult

        /** 全部角色已有可尝试恢复的稳定地址，已重新进入连接流程 */
        data class ResumedAll(val resumed: Map<DeviceRole, String>) : ResumeResult

        /** 部分角色缺少可用地址，其余已进入连接流程 */
        data class PartiallyResumed(
            val resumed: Map<DeviceRole, String>,
            val needRescan: Map<DeviceRole, PendingDevice>,
        ) : ResumeResult

        /** 一个都没有可用地址 → UI 全部进入扫描流程 */
        data class RescanRequired(val needRescan: Map<DeviceRole, PendingDevice>) : ResumeResult
    }

    /** 在 MainActivity.onCreate 中调用；返回值决定 UI 该走哪条路径。 */
    suspend fun resumeIfNeeded(context: Context): ResumeResult {
        val pending = store.pending() ?: return ResumeResult.NothingToResume
        if (!env.isBluetoothConnectPermissionGranted)
            return ResumeResult.RequiresUserAction("BLUETOOTH_CONNECT 已被撤销")
        if (!env.isBluetoothEnabled)
            return ResumeResult.RequiresUserAction("蓝牙未开启")

        // 逐角色用持久化地址重建 Peripheral；稳定 MAC 的设备可直接进入 AutoConnect。
        // 注意：Android 实现通常能用合法地址构造 Peripheral，这不代表设备一定可达；
        // 地址轮换 / 不在范围 / 已关机要靠后续连接状态和定向重扫处理。
        val resumed = mutableMapOf<DeviceRole, Peripheral>()
        val needRescan = mutableMapOf<DeviceRole, PendingDevice>()
        for (dev in pending.devices) {
            val p = runCatching { centralManager.getPeripheralById(dev.address) }.getOrNull()
            if (p != null && dev.address.isNotBlank()) resumed[dev.role] = p else needRescan[dev.role] = dev
        }

        // 可恢复的角色立即进入连接流程；需要重扫的角色由 UI 后续调用 controller.replaceDevice(...)
        if (resumed.isNotEmpty()) {
            ContextCompat.startForegroundService(
                context, Intent(context, CollectionForegroundService::class.java)
            )
            controller.start(DeviceAssignment(resumed))
        }

        return when {
            resumed.size == pending.devices.size ->
                ResumeResult.ResumedAll(resumed.mapValues { it.value.address })
            resumed.isEmpty() ->
                ResumeResult.RescanRequired(needRescan)
            else ->
                ResumeResult.PartiallyResumed(
                    resumed = resumed.mapValues { it.value.address },
                    needRescan = needRescan,
                )
        }
    }
}
```

`ScanViewModel` 增加一个"按角色定向扫描"模式：

```kotlin
/**
 * 用 MAC / Service UUID 定向扫描：恢复失败或地址失效时，对目标角色单独扫一次。
 * 找到 → 立即停扫 → 通过 controller.replaceDevice(role, p) 接入运行中的会话。
 */
fun onScanForKnownDevice(
    role: DeviceRole,
    targetAddress: String?,
    timeout: Duration = 15.seconds,
) {
    job = centralManager.scan(timeout) {
            when (role) {
                DeviceRole.DUT -> ServiceUUID(DutUuids.SERVICE)
                DeviceRole.REFERENCE -> ServiceUUID(HeartRateUuids.SERVICE)
            }
        }
        .filter { result ->
            targetAddress == null ||
                result.peripheral.address.equals(targetAddress, ignoreCase = true)
        }
        .take(1)
        .onEach { result ->
            onStopScan()
            controller.replaceDevice(role, result.peripheral)
        }
        .onCompletion {
            // 超时未找到 → UI 显示"目标 $role 设备不在范围"
        }
        .launchIn(scope)
}

/** 配对模式：分两步扫描 DUT 和 REFERENCE，按 service UUID 过滤。 */
fun onScanForRole(role: DeviceRole, timeout: Duration = 10.seconds) {
    job = centralManager.scan(timeout) {
        when (role) {
            DeviceRole.DUT -> ServiceUUID(DutUuids.SERVICE)
            DeviceRole.REFERENCE -> ServiceUUID(HeartRateUuids.SERVICE)   // 0x180D
        }
    }
    .distinctByPeripheral()
    .filterNot { it.peripheral.name.isNullOrBlank() }
    // ... 同 §7 通用逻辑 ...
    .launchIn(scope)
}
```

按 `ServiceUUID` 过滤是关键：用户扫 REFERENCE 时，列表里**只会出现真正的心率带**，不会混入其它 BLE 设备，配对体验大幅改善。

#### 13.6.3 持久化什么 / 什么时候写（多设备版）

DataStore 里持久化的不是单个设备，而是整个**会话分配**：

```kotlin
@Serializable
data class PendingSession(
    val sessionId: String,
    val sessionDir: String,                            // 绝对路径
    val startedAtEpochMillis: Long,
    val devices: List<PendingDevice>,                  // 每角色一条
)

@Serializable
data class PendingDevice(
    val role: DeviceRole,
    val address: String,
    val name: String?,
)

@Singleton
class CollectionSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val dataStore = context.dataStore   // androidx.datastore.preferences

    suspend fun markStarted(assignment: DeviceAssignment) = dataStore.edit {
        it[KEY_SESSION_JSON] = json.encodeToString(assignment.toPendingSession())
    }
    suspend fun markDeviceCollecting(role: DeviceRole, fileName: String) {
        // 角色进入 Collecting 后补齐该角色的输出文件名，供进程恢复时续写同一 CSV。
    }
    suspend fun markDeviceFailed(role: DeviceRole, reason: DeviceState.Reason) {
        // 角色级失败：只从 pending.devices 移除该角色，其它角色继续
        // 全部角色都失败时调用 markStopped()
    }
    suspend fun markStopped() = dataStore.edit { it.clear() }
    suspend fun pending(): PendingSession? = dataStore.data
        .map { prefs -> prefs[KEY_SESSION_JSON]?.let { json.decodeFromString<PendingSession>(it) } }
        .first()
}
```

写时机（**逐角色**判断，不再是会话整体）：

- **用户点击 Start 且 `DeviceAssignment` 非空** → 写入本次会话意图和每个角色的稳定地址，保证进程在连接途中被杀也能引导恢复。
- **任意一个角色进入 `Collecting`** → 补齐该角色实际 CSV 文件名。允许"DUT 还没连上，REFERENCE 已经在采"的中间态。
- **用户主动 `stop()`** → `markStopped()`。
- **某角色 `Failed(reason ∈ {PermissionDenied, DeviceNotSupported})`** → `markDeviceFailed(role, reason)`，从 pending 中剔除该角色，避免下次反复无效恢复；其它角色不动。
- **全局 `Failed(BluetoothOff / PermissionDenied)`** → 保留 pending（蓝牙重开 / 重新授权后还能恢复整组）。

#### 13.6.4 进程被杀的恢复触发时机

| 触发源 | 处理 |
| --- | --- |
| 用户重新打开 App | `MainActivity.onCreate` 调 `resumer.resumeIfNeeded()`；根据 `ResumeResult` 走 UI |
| 系统重启（`BOOT_COMPLETED`） | `BootCompletedReceiver` **不直接** `startForegroundService` —— Android 12+ 会 `ForegroundServiceStartNotAllowedException`；改为发一条"上次的采集未完成，点此恢复"的通知，引导用户点回 App，由 `MainActivity` 继续走 `resumeIfNeeded()` |
| 蓝牙被关后又打开 | `env.bluetoothState` 变 `POWERED_ON` 时，由前台的 ViewModel 触发 `resumeIfNeeded`（前台条件天然满足） |
| App 升级换装 | 类似系统重启，走 BootCompletedReceiver 一套 |

#### 13.6.5 "如果将来改用绑定"怎么演进

如果哪天设备协议升级支持绑定（建议加上，能省掉本节大部分复杂度）：

- `SensorProfile<T>` 接口和具体实现（如 `DutProfileImpl`、`HeartRateProfileImpl`）都不需要改。
- `CollectionResumer.resumeIfNeeded()` 优先调 `centralManager.getBondedPeripherals().firstOrNull { it.address == pending.address }`，命中即走，**完全免扫描且不依赖设备地址是否轮换**。
- UI 在首次连接时加一个 `peripheral.createBond()` 调用（sample 的 `onBondRequested` 就是模板）。
- `地址失效 / 地址轮换 → 重扫`这条 fallback 分支可以大幅简化。

### 13.7 完整端到端流程图（三种场景 · 1..N 设备）

下面用文字时序把"扫描 → 配对 → 后台采集 → 恢复"的三条主路径走一遍，**默认举"未绑定 + 双角色（DUT + REFERENCE）"对照场景**——它是设备数量最多、流程最复杂的常见情况。**单设备会话只是它的退化版**（只填一个角色，其它步骤完全一致），下方在场景 A 末尾给出对照说明。

#### 场景 A：用户首次启动一次新的双设备会话

```text
[UI - PermissionGateScreen]
  授权 BLUETOOTH_SCAN / CONNECT / FINE_LOCATION（未声明 neverForLocation）
  建议授权 POST_NOTIFICATIONS@13+
       │
       ▼
[UI - DeviceAssignmentScreen]    ← 新增页：两个"卡片"，分别让用户绑定 DUT 与 REFERENCE
   ┌───────────────────────────┐    ┌───────────────────────────┐
   │ DUT (待测 PPG)            │    │ REFERENCE (参考心率带)    │
   │ [选择设备] ── ScanScreen  │    │ [选择设备] ── ScanScreen  │
   │   过滤 ServiceUUID(DUT)   │    │   过滤 ServiceUUID(0x180D)│
   │ 已选: AA:BB:CC:DD:EE:01   │    │ 已选: 11:22:33:44:55:66   │
   └───────────────────────────┘    └───────────────────────────┘
                    │                            │
                    └──────────┬─────────────────┘
                               ▼
                    DeviceAssignment(DUT=p1, REFERENCE=p2)
                               │
                               ▼  用户点击"开始采集"
[UI - DataCollectionScreen]
   ┌─ 同时启动 ────────────────────────────────────────────────────┐
   │                                                                │
   ├─ ContextCompat.startForegroundService(...)                     │
   └─ controller.start(assignment)                                  │
       └─ AppScope.launch { supervisorScope {                       │
            launch { perDeviceSession[DUT].run() }       ─────┐     │
            launch { perDeviceSession[REFERENCE].run() } ─┐   │     │
            sessionStore.markStarted(assignment)           │   │     │
          } }                                             │   │     │
                                                          ▼   ▼     │
[FGS.onCreate]                          [DUT session]    [REF session]
  wakeLock.acquire()                     Direct connect    Direct connect
  startForeground(CONNECTED_DEVICE)      installDut        installHRS
                                         pipeline<Dut>     pipeline<HR>
                                         CsvSink<Dut>      CsvSink<HR>
                                         ↓                 ↓
                                    DUT_AA-...csv    REFERENCE_11-...csv
                                         ↓                 ↓
                                    （并行持续采集，互不影响）
       │
       └─ 持续保活（一个 FGS 覆盖两个 BLE 连接）
```

> **DUT 失败 → REFERENCE 继续**：例如 DUT 抛 `ConnectionFailedException`，`PerDeviceSession[DUT]` 的 `while` 循环进入重连退避，UI 显示"DUT 重连中"，但 `PerDeviceSession[REFERENCE]` 完全不受影响，REFERENCE 的 CSV 持续追加。这是 `supervisorScope` 的语义保证。
>
> **退化到单设备会话**：用户在 `DeviceAssignmentScreen` 只选 DUT、不选 REFERENCE（或反过来），点"开始采集"后 `DeviceAssignment` 里只有一对键值，整个流程剩下：
>
> - FGS 仍然启动一次（FGS 不在乎几个连接，只关心是否需要前台优先级 + WakeLock）；
> - `MultiDeviceController.start(assignment)` 在 `supervisorScope` 下只 `launch { perDeviceSession[DUT].run() }` 一个子协程；
> - 只生成一份 CSV（`DUT_AA-...csv`）；`session_manifest.json` 的 `devices` 数组长度为 1；
> - `SessionState.Running.byRole` 只有一个条目；UI 在 `DataCollectionScreen` 只渲染一个设备卡片。
>
> **代码路径完全一致**，没有"if 单设备 else 多设备"的分支。

#### 场景 B：用户切后台 / 锁屏 / Activity 销毁后又回到 App

```text
[T+1h] 用户按 Home / 锁屏
  Activity onPause/onStop → ViewModel onCleared
       │
       │ ⚠️ 但是：
       │  - MultiDeviceController (@Singleton) 不受影响
       │  - sessionJob 跑在 AppScope，不挂 ViewModel
       │  - FGS 仍在前台优先级，wakeLock 仍持有
       │
       ▼ 采集和文件写入零中断 ────────────────────────────────► CSV 持续增长

[T+2h] 用户重新打开 App
       │
       ▼
[MainActivity.onCreate]
  正常走 NavHost → DataCollectionScreen
       │
       ▼
[DataCollectionViewModel.init]
  observe(controller.state) ── 拿到的就是当前 Collecting(savedCount=12345, ...)
       │
       ▼
UI 无缝接回，没有任何"重新连接"过程，savedCount 一直自增
```

#### 场景 C：进程被杀（OEM 强杀 / OOM / 强行停止 / 设备重启）

```text
[T+3h] 进程被系统杀死
  AppScope 一并消失 → 两个 PerDeviceSession 同时取消 → 两个 peripheral disconnect
       │
       │ CSV 文件:
       │  - DUT_AA-...csv 和 REFERENCE_11-...csv 各自最后一次 flush 已落盘
       │  - session_manifest.json 已写入 sessionDir
       │  - pending = { sessionId, devices: [DUT/AA-..., REFERENCE/11-...] }
       │
       ▼
[T+3h05m] 用户重新打开 App
       │
       ▼
[MainActivity.onCreate → CollectionResumer.resumeIfNeeded()]
  pending = store.pending()  // 命中
  for each device in pending.devices:
    centralManager.getPeripheralById(address)  // 稳定 MAC 可直接构造 Peripheral
       │
       ▼
  ┌────────────────────────────────────────────────────────────────────┐
  │ 情况 1：两个角色地址都可用于恢复 (ResumedAll)                       │
  │   ├─ startForegroundService                                        │
  │   ├─ controller.start(DeviceAssignment(DUT=p1, REFERENCE=p2))      │
  │   └─ UI 跳回 DataCollectionScreen，两个 CSV 续写（同一文件名）     │
  │                                                                    │
  │ 情况 2：仅一个角色可直接恢复 (PartiallyResumed)                    │
  │   ├─ 可恢复的角色先进入 AutoConnect（例如 REFERENCE）              │
  │   └─ UI 在 DataCollectionScreen 顶部显示"DUT 需要重新连接"卡片    │
  │       └─ 点击 → onScanForKnownDevice(DUT, 持久化地址或 ServiceUUID, 15s) │
  │           └─ 找到 → controller.replaceDevice(DUT, p)               │
  │           └─ 超时 → "设备不在范围"，保留 pending                  │
  │                                                                    │
  │ 情况 3：两个都不能直接恢复 (RescanRequired)                        │
  │   └─ UI 跳 DeviceAssignmentScreen（不是 ScanScreen），             │
  │      两个卡片自动进入"已知 MAC 定向扫描"模式                       │
  │      用户在范围内时几秒内全部就绪并自动开始采集                    │
  └────────────────────────────────────────────────────────────────────┘
```

> **关键观察**：
> 1. 多设备恢复**不是"全有或全无"**，部分恢复也是正常态：例如 REFERENCE 心率带还在身上，DUT 没电了 → 心率数据继续记录，DUT 这边等用户再开机。
> 2. **单设备会话的恢复就是上面流程的退化**：`pending.devices` 长度为 1，`ResumeResult` 只可能是 `ResumedAll`（直接续写）或 `RescanRequired`（重扫该角色），不会出现 `PartiallyResumed`。
> 3. 本 App **没有"完全无人值守的开机自动连接"能力**（未绑定 + v2.0 暂无 PendingIntent scan）。设备重启后必须有"用户回 App"动作。若设备使用可轮换随机地址，彻底解法是支持绑定或接入 Companion Device / PendingIntent scan 路线。
> 4. 续写同一 CSV：因为 sink 用了 `nextRotatedFile` 命名策略，文件存在时直接 append，不会破坏已有数据。manifest 的 `endEpochMillis` 在最终 `stop()` 时才写。

### 13.8 边界情况自检表

| 场景 | 系统会发生什么 | 本架构的响应 |
| --- | --- | --- |
| 用户按 Home，App 切后台 | Activity onStop，但 Application + FGS 不变 | 采集继续；UI 重回时直接 collect 现有 `StateFlow` |
| 用户旋转屏幕 | Activity 重建，ViewModel 重用 | 无感切换 |
| 用户从最近任务列表滑掉 App | 多数 OEM：FGS 被杀；部分（小米 / Vivo / OPPO）连进程一起杀 | 文件已 flush；下次启动 `CollectionResumer.resumeIfNeeded()` 处理 |
| 设备进入 Doze | CPU 想睡 | `WakeLock` 保 CPU 醒；BLE 心跳维持 |
| 蓝牙被用户关掉 | `env.bluetoothState` 变 `POWERED_OFF`，连接强断 | `runSession` 的 `while` 循环捕获 `ConnectionFailedException`，进入等待；蓝牙重开后 `AutoConnect` 自动复连 |
| 设备走出范围 | GATT 自动断开 | 同上，`AutoConnect` 等设备回来 |
| 设备重启 | 进程全死 | `BootCompletedReceiver` 发通知，不直接启 FGS |
| `BLUETOOTH_CONNECT` 被用户在设置里撤销 | 下一次 GATT 调用抛 `SecurityException` | `runSession` 外层 `catch` → `Failed(PermissionDenied)`，停止 FGS，UI 引导重新授权 |
| CSV 文件 IO 失败（盘满） | sink 内部协程抛 `IOException` | sink 自身 close；上层观察到 `Failed(StorageFailed)`，停止采集 |
| 进程被换装更新 | 全死 | 同"滑掉 App"，靠 `CollectionResumer` 恢复 |

## 14. ViewModel & UI

### 14.1 DataCollectionViewModel

```kotlin
@HiltViewModel
class DataCollectionViewModel @Inject constructor(
    private val controller: MultiDeviceController,
    private val env: AndroidEnvironment,
) : ViewModel() {

    data class UiState(
        val bluetoothOn: Boolean,
        val locationOn: Boolean,
        val session: SessionState,
        val latest: Map<DeviceRole, SensorSample> = emptyMap(),
    )

    val state: StateFlow<UiState> = combine(
        env.bluetoothState, env.locationState, controller.state,
    ) { bt, loc, session ->
        UiState(bluetoothOn = bt == Manager.State.POWERED_ON,
                locationOn = loc, session = session)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        UiState(env.isBluetoothEnabled, env.isLocationEnabled, SessionState.Idle))

    fun onStart(assignment: DeviceAssignment) = controller.start(assignment)
    fun onStop() = controller.stop()
}
```

### 14.2 Compose UI 要点

- 全屏 edge-to-edge：`enableEdgeToEdge()`，配合 `Scaffold(contentWindowInsets = WindowInsets.statusBars)`。
- 收集状态用 `collectAsStateWithLifecycle()`（API 24+ 兼容），避免后台时持续重组。
- 顶层 NavHost 用 type-safe routes：

```kotlin
// V4：顶层三 Tab + push 子页（子页 / 采集运行隐藏底部 Tab）
@Serializable data object PermissionGate
@Serializable data object MainTabs          // 采集 / 数据 / 设置
@Serializable data object DeviceConnect     // 扁平设备列表（DUT ≤3 + 参考心率带 ≤1）
@Serializable data object SubjectSelect
@Serializable data object SensorConfig      // 纯开关（透明传输，不控采样率）
@Serializable data object DataCollection    // 全屏，隐藏 Tab，长按 2 秒结束
```

- Predictive Back：`enableOnBackInvokedCallback="true"` + Compose `BackHandler` 自动支持。
- Splash Screen：`installSplashScreen()` 在 `super.onCreate` 之前调用。

### 14.3 页面流与各屏

> **页面流、各屏视觉与状态以 [v4_android.html 原型](../prototypes/v4_android.html)（当前 · 底部三 Tab）+ [V4 设计契约 §九/§十](../reviews/BlueTrace_V4_设计契约_2026-06-16.md) 为准**（UX_Flows 已归档 [legacy](../legacy/BlueTrace_UX_Flows.md)，原型现内置每屏 UX 交互规格），本架构文不再重复描述各屏长什么样。ViewModel 只暴露 `SessionState` + 最新样本，UI 照原型渲染。
>
> 要点（细节看原型）：设备连接为**扁平列表**（DUT ≤3 + 参考心率带 ≤1，单击连/断）；数据采集页为**竞品 Data Collection 式**（采集类型勾选 + 简单实时数据区 + Start/End 标签 + 暂停 + 长按 2 秒结束），**不含**角色分组 / Send Command 运行中控制面板（后期）。

## 15. 日志与诊断

- 库使用 SLF4J，通过 `slf4j-timber` 桥接，与业务日志统一到 Timber。
- `BlueTraceApplication.onCreate` 中：debug `Timber.plant(DebugTree())`，release `plant(ReleaseTree())`（过滤到 WARN+）。

必要日志点：

- 权限请求前后快照（`BlePermissionFacade.snapshot()` 整体打）。
- 蓝牙/定位每次状态变化。
- 扫描开始 / 结束 / 超时 / 失败原因；每个 scan result 的 name/address/rssi/scanRecord 长度；被过滤的无名设备数（合并打印计数，不要逐条 spam）。
- 连接事件：开始、成功、失败、断开（含 `GATT_STATUS_*`）。
- 服务发现：每个 service / characteristic 的 UUID + properties。
- 订阅：CCCD 写入成功/失败。
- 写指令：长度 + 前 N 字节 hex。
- 通知：长度 + 前 N 字节 hex；不要打全帧避免日志洪水。
- Pipeline：每 N 帧打一次速率，invalid 帧的 reason。
- 文件：当前路径、滚动、关闭。

release 包默认 WARN+，避免泄露设备数据。

## 16. 测试策略

得益于 v2.0 内置 mock，**核心 BLE 流程可全部 JVM unit test 覆盖**。

### 16.1 单元测试（纯 JVM）

- `DutDataParserTest` / 每个 `SensorDataParser<T>` 单测：正常帧 / 长度错 / 头错 / 尾错 / checksum 错。
- `CsvSensorDataSinkTest`：首次 header、追加、滚动、并发 send。
- `CollectionPipelineTest`：feed mock Flow → 断言 counters / sink 行数。

### 16.2 BLE 集成测试（用 MockAndroidEnvironment）

```kotlin
@OptIn(ExperimentalUuidApi::class)
private val bluetracePeripheral = PeripheralSpec.simulatePeripheral(
    identifier = "AA:BB:CC:DD:EE:01",
    proximity = Proximity.IMMEDIATE,
) {
    advertising(
        parameters = LegacyAdvertisingSetParameters(
            connectable = true, interval = 200.milliseconds,
        ),
    ) {
        ServiceUuid(BlueTraceUuids.SERVICE)
        CompleteLocalName("BlueTrace-Sim-01")
    }
    connectable {
        gatt {
            service(BlueTraceUuids.SERVICE) {
                characteristic(BlueTraceUuids.NOTIFY_CHAR /* notify */)
                characteristic(BlueTraceUuids.WRITE_CHAR  /* writeNoResponse */)
            }
        }
    }
}

@Test fun `happy path collects frames`() = runTest {
    val env = MockAndroidEnvironment.Api31(
        isBluetoothScanPermissionGranted = true,
        isBluetoothConnectPermissionGranted = true,
    )
    val central = CentralManager.mock(env, this).apply {
        simulatePeripherals(listOf(bluetracePeripheral))
    }
    // ... 执行 scan → connect → 等待 SessionState.Running 中两个角色 Collecting → 断言 sink 写入
}
```

测试矩阵：

- happy path：scan → connect → subscribe → 收 N 帧 → stop → 文件 N 行。
- 缺失 NOTIFY_CHAR → `Failed(DeviceNotSupported)`。
- 连接中断 → `Failed(Disconnected)`，自动重连（AutoConnect 选项）成功恢复。
- 蓝牙关闭（`env.simulatePowerOff()`）→ `Failed(BluetoothOff)`，sink 停止。
- 写指令失败（mock characteristic 配置成 `WriteNotPermitted`）→ `Failed(WriteFailed)`。

### 16.3 Compose UI 测试

- 用 `createComposeRule()` + Hilt test runner，注入 `MockAndroidEnvironment` 和 mock CentralManager。
- 验证：未授权时 ScanScreen 显示 PermissionGate；扫描中显示 progress；点击 device 跳转 DataCollect 并显示 Connecting。

## 17. 实施里程碑

### 17.1 一期（本期，本地数据采集闭环）

| Phase | 目标 | 验收 |
| --- | --- | --- |
| **P0 探针** | 用本仓库 `:sample` 在真机上跑通连接 nRF Blinky，确认 v2.0 API 在 Android 7 / Android 14 / Android 15 上的行为 | 真机日志看到 LBS notify |
| **P1 骨架** | 新建 `:bluetrace-app`，接入 Hilt（**Singleton** 作用域）、Timber、`NativeAndroidEnvironment`、`CentralManager`，完成 `PermissionGateScreen` 和 `ScanScreen`（mock flavor 调通） | mock 设备能在 ScanScreen 列出 |
| **P2 Profile + Pipeline** | `SensorProfile` + `DutProfileImpl` / `HeartRateProfileImpl` + `MultiDeviceController`（含 §9.2 重连循环）+ `CollectionPipeline` + 占位 parser；UI 显示 saved/invalid 计数 | mock **单设备**（仅 DUT 或仅 REFERENCE）+ **双设备**（DUT + REFERENCE）两组 happy path 都通过 |
| **P3 持久化 + 文件** | `CsvSensorDataSink`（Channel + 滚动 flush + 文件滚动）；导出到 Downloads（API 29+ MediaStore / API 24-28 公共目录） | 真机生成可读 CSV（单 / 双设备各验证一次），能导出到 Downloads |
| **P4 后台保活 + 进程恢复** | `CollectionForegroundService`（connectedDevice + WakeLock）；`CollectionSessionStore` + `CollectionResumer`（含稳定 MAC 恢复与地址失效时重扫，见 §13.6）；`BootCompletedReceiver` 发恢复通知；蓝牙/定位关闭恢复 | ① 灭屏/锁屏后持续采集（单 / 双设备） ② 进程被杀后回 App 自动续写同一 CSV |
| **P5 协议替换** | 拿到真实设备协议后，仅修改 `DutUuids` + `DutDataParser`（必要时调整 `DutSample` 字段），上层零改动 | Diff 限定在 `profile/dut/` |

### 17.2 二期（服务器远控 / 数据上传，本期不实现）

> 本期一切 BLE 采集结果保存在本地 CSV；二期才接入服务端。

| Phase | 目标 | 说明 |
| --- | --- | --- |
| **P6 服务器接入** | API 客户端（OkHttp / Ktor）、鉴权（OAuth2 / 设备 Token）、错误重试 | 与设备协议解耦，纯网络层 |
| **P7 周期上传** | `CsvUploadWorker`（`PeriodicWorkRequest` 15 min，`UNMETERED + battery-not-low` 约束）；上传成功后归档/删除；上传失败指数退避；`FileRotationWorker` 每日清理 7 天前 CSV | WorkManager **只承担"有界、可重试、可延迟"工作**，**不**用来保持 BLE 连接 |
| **P8 远程指令** | 服务器下发指令 → 本地 `SensorProfile.sendCommand` 转发；下发"开始 / 停止 / 修改采样率"等控制 | 复用 §8 的 Profile 接口，无需改 BLE 层 |
| **P9 看门狗 + 远程诊断** | `CollectionWatchdogWorker`：FGS 被杀后若仍有 pending session，发通知；日志按需上报 | 进一步降低数据丢失概率 |

**为什么 WorkManager 安排在二期**：本期目标是"本地采集闭环"，WorkManager 唯一的合理职责（周期上传 / 文件清理 / 看门狗）都是在有服务器或长期运行后才有价值，本期不引入可以避免过度设计。

### 17.3 不在本期范围内的事

- BLE 广播（advertiser）—— 本 App 仅作为 Central。
- 配对/绑定流程 —— 目标设备协议不开绑定，相关 UI 不开发；若设备协议升级，按 §13.6.5 演进路径加。
- 完全无人值守的开机自恢复 —— 本期不接入 Companion Device Manager / PendingIntent scan；设备重启后通过通知引导用户回 App 恢复。

## 18. 关键设计决策（与取舍）

1. **底层只用 Kotlin-BLE-Library v2.0**：不再依赖旧 `BleManager`/`BluetoothLeScannerCompat`，统一协程/Flow 风格，省掉大量回调状态机。本 App 仅作为 Central，**不引入 advertiser 模块**，不声明 `BLUETOOTH_ADVERTISE` 权限。
2. **Environment / CentralManager / AppScope = `@Singleton`**（§6）：因为有 FGS 长驻，BLE 组件必须与 Application 进程同生命周期，**不能挂在 `ActivityRetainedComponent`**，否则用户退出 UI 即触发 `close()`，后台采集被立刻打断。
3. **不重新发明 BLE 状态**：应用级 `SessionState` / `DeviceState` 完全由 `peripheral.state` + `env.bluetoothState` + `env.locationState` 派生，**单一真理源**。
4. **Profile 隔离 UUID/协议**：业务层只依赖 `SensorProfile<T>.samples: Flow<T>`（强类型，T : SensorSample），换协议只动对应的 `*ProfileImpl` + `*DataParser`，对 controller / pipeline / UI 零影响。
5. **设备数量 = 1..N，单设备是常见路径**（§1 / §9.3）：`DeviceAssignment` 是 `Map<DeviceRole, Peripheral>`，长度 ≥ 1 即可。`MultiDeviceController` 用 `supervisorScope` 下 `forEach { launch { perDeviceSession.run() } }` 自然展开 1..N，单设备和多设备走完全相同的代码路径，没有分支。
6. **会话级断线自动重连**（§9）：首连用 `ConnectionOptions.Direct(retry=3)`，断线后切 `AutoConnect`，由协议栈兜底恢复。`while` 循环承载，`CancellationException` 是唯一正常退出路径。
7. **数据流水线 = Channel + 调用方传入的 scope**（§11）：BLE → 解析 → IO 三阶段解耦，背压策略可配置（SUSPEND 默认），**严禁 `GlobalScope`**，保证结构化并发。
8. **存储分两条路径**（§12）：App 私有外部目录免权限做主存储；导出到公共 Downloads —— Android 10+ 走 MediaStore，**Android 7–9 走 `Environment.getExternalStoragePublicDirectory` + `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28）**，不污染 ViewModel/Repository。
9. **后台采集 = FGS + WakeLock**（§13）：声明 `connectedDevice` 类型；启动**必须由前台用户动作触发**（避免 `ForegroundServiceStartNotAllowedException`）；运行时持 `PARTIAL_WAKE_LOCK` 保证国产 ROM Doze 下不掉包。
10. **Mock 优先的测试策略**：核心 BLE 流程全部 JVM 单测覆盖（`MockAndroidEnvironment` + `PeripheralSpec`），CI 不需要真机。
11. **UUID 全部用 `kotlin.uuid.Uuid`**：与 v2.0 API 一致，去掉 `java.util.UUID` 转换样板；连接选项用 `automaticallyRequestHighestValueLength`（库实际属性名，README 示例里的 `automaticallyRequestHighestMtu` 是错的）。
