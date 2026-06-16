# BlueTrace Android BLE 数据采集 App 架构设计

> 本文档基于 **Kotlin-BLE-Library v2.0**（本仓库 `version/2.0` 分支：`CentralManager` + `Peripheral` + 协程/Flow 风格 API），目标 Android 平台：
>
> - `minSdk = 24`（Android 7.0）
> - `compileSdk = 35` / `targetSdk = 35`（Android 15）
> - Kotlin 2.x、Jetpack Compose（Compose BOM）、Material 3
>
> 参考实现：本仓库 `sample/src/main/.../scanner/ScannerViewModel.kt` 与 `scanner/profile/LedButtonProfile.kt`，以及社区 [Android-nRF-Blinky](https://github.com/NordicSemiconductor/Android-nRF-Blinky) 的 Profile 抽象方式。

---

## 1. 目标与范围

BlueTrace 是一个面向 BLE 传感器的数据采集 App。核心能力：

- 请求并管理 Android BLE 扫描、连接、（可选）定位和文件保存所需权限。
- 扫描周围 BLE 设备，展示名称、MAC 地址、RSSI，并支持点击连接。
- 连接目标设备后自动发现服务、开启通知或指示通道。
- 向写特征发送字节流指令（自动按 MTU 分片）。
- 接收通知数据，通过 Kotlin `Flow` 分发给上层。
- 将原始字节流解析为业务数据，并追加保存为 CSV/TXT；可选导出到系统 Downloads。
- 对权限拒绝、蓝牙关闭、定位关闭、设备断开、连接超时、订阅失败、写入失败和文件写入失败做可恢复处理。
- 支持后台持续采集（前台服务 + `connectedDevice` 类型，Android 14+ 强制要求）。

Service UUID、Write Characteristic UUID、Notify Characteristic UUID 以常量形式预留，对接真实设备时只需替换 `BlueTraceUuids` 与 `DefaultSensorDataParser`，上层零改动。

## 2. 技术选型

| 维度 | 选择 |
| --- | --- |
| Language | Kotlin 2.x，目标 JVM 17 |
| UI | Jetpack Compose（Compose BOM）+ Material 3，edge-to-edge |
| Navigation | `androidx.navigation:navigation-compose`（Type-safe Routes） |
| Architecture | MVVM + 单向数据流（UDF），ViewModel 暴露 `StateFlow<UiState>` |
| Async | Kotlin Coroutines + Flow |
| **BLE 核心** | **本仓库 `Kotlin-BLE-Library v2.0`**：`:client-android`（native）+ `:environment-android` + `:environment-android-compose`；测试用 `:client-android-mock` + `:environment-android-mock` |
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
        minSdk = 24
        targetSdk = 35
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // 让 java.time.* 在 API 24 可用
    }
    flavorDimensions += listOf("mode")
    productFlavors {
        create("native") { isDefault = true; dimension = "mode" }
        create("mock")   { dimension = "mode" }
    }
}

dependencies {
    // BLE 核心（v2.0，仓库内 project 依赖）
    "nativeImplementation"(project(":client-android"))
    "nativeImplementation"(project(":advertiser-android"))   // 可选：自我广播 / 调试
    "mockImplementation"(project(":client-android-mock"))
    "mockImplementation"(project(":advertiser-android-mock"))
    // Environment（系统状态 + 权限快照），全 flavor 都用
    implementation(project(":environment-android"))
    implementation(project(":environment-android-compose")) // 提供 LocalEnvironmentOwner
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

    // Desugar
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.x")
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
├── profile
│   ├── BlueTraceUuids.kt
│   ├── BlueTraceProfile.kt             // interface + UUID + 安装入口
│   └── BlueTraceProfileImpl.kt
├── session
│   ├── CollectionController.kt         // 连接→安装 profile→起 pipeline→停止
│   ├── CollectionState.kt              // sealed: Idle/Connecting/Subscribing/Collecting/Failed
│   └── CollectionPipeline.kt           // notify → parse → sink
├── data
│   ├── SensorData.kt
│   ├── ParseResult.kt
│   ├── SensorDataParser.kt
│   └── DefaultSensorDataParser.kt
├── storage
│   ├── SensorDataSink.kt
│   ├── CsvSensorDataSink.kt            // Channel + 单消费者协程 + 滚动 flush
│   └── ExportToDownloads.kt            // MediaStore，API 24+ 兼容路径
├── service
│   └── CollectionForegroundService.kt  // connectedDevice 类型，Android 14+
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
Profile Layer            ←─ BlueTraceProfile：业务化封装 RemoteCharacteristic
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

    <!-- 定位：BlueTrace 场景"扫描结果用于数据采集"，不声明 neverForLocation -->
    <!-- API 28 及以下扫描必须 ACCESS_FINE_LOCATION；API 29-30 仍建议 ACCESS_FINE_LOCATION -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                     android:maxSdkVersion="30"/>
    <!-- 如果产品要求 Android 12+ 仍可基于扫描结果做位置推断，再加这条 -->
    <!-- <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/> -->

    <!-- 前台服务（Android 14+ 必须声明对应类型权限） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>

    <!-- 通知（Android 13+，前台服务通知需要） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

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

- 因为 `minSdk = 24`，必须同时保留旧的 `BLUETOOTH` / `BLUETOOTH_ADMIN`（带 `maxSdkVersion="30"`）和新的 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`。
- BlueTrace 是"采集"场景，**不**使用 `usesPermissionFlags="neverForLocation"`，因此 `AndroidEnvironment.isLocationRequiredForScanning` 在 Android 12+ 也会是 `true`，需要 `ACCESS_FINE_LOCATION`。如果你产品上确认"扫描结果不会用于位置推断"，再把 manifest 加上 `neverForLocation`，并在构造 `NativeAndroidEnvironment` 时传 `isNeverForLocationFlagSet = true`。
- Android 13+ 前台服务通知需要 `POST_NOTIFICATIONS`，必须运行时请求。
- Android 14+ 启动 connectedDevice 类型前台服务前**必须**已经有 `BLUETOOTH_CONNECT` 运行时授权，否则 `startForeground(...)` 抛 `SecurityException`。
- Splash Screen API 走 `androidx.core:core-splashscreen`，对 API 24 起兼容。
- `enableOnBackInvokedCallback="true"` 启用预测式返回（API 33+），Compose `BackHandler` 自动适配。

### 5.2 运行时权限策略（按 SDK 版本）

| SDK | 必须运行时请求 |
| --- | --- |
| 24–28 | `ACCESS_FINE_LOCATION`（扫描必需） |
| 29–30 | `ACCESS_FINE_LOCATION`（扫描必需） |
| 31–32 | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`，**如未设 `neverForLocation`** 还要 `ACCESS_FINE_LOCATION` |
| 33+ | 同上，外加 `POST_NOTIFICATIONS`（仅当使用前台服务通知时） |

**真理之源是 `AndroidEnvironment`**，不要自己写 `Build.VERSION.SDK_INT` 分支。见 §6.2。

## 6. Environment、Hilt 与协程作用域

### 6.1 EnvironmentModule

```kotlin
@Module
@InstallIn(ActivityRetainedComponent::class)
object EnvironmentModule {

    @ActivityRetainedScoped
    @Provides
    fun provideNativeEnvironment(
        @ApplicationContext context: Context,
        lifecycle: ActivityRetainedLifecycle,
    ): NativeAndroidEnvironment =
        NativeAndroidEnvironment
            // 与 AndroidManifest 中是否使用 neverForLocation 必须保持一致
            .getInstance(context, isNeverForLocationFlagSet = false)
            .also { env -> lifecycle.addOnClearedListener { env.close() } }
}

@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class AndroidEnvironmentBindModule {
    @Binds abstract fun bindEnvironment(env: NativeAndroidEnvironment): AndroidEnvironment
}
```

`NativeAndroidEnvironment` 内部注册了 `BroadcastReceiver`，**必须**在 `onCleared` 关闭，否则泄漏。

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
@Module
@InstallIn(ActivityRetainedComponent::class)
object CentralManagerModule {

    @ActivityRetainedScoped
    @Provides
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("BleScope"))

    @ActivityRetainedScoped
    @Provides
    fun provideCentralManager(
        env: AndroidEnvironment,
        scope: CoroutineScope,
        lifecycle: ActivityRetainedLifecycle,
    ): CentralManager =
        CentralManager.Factory.native(env, scope)
            .also { cm -> lifecycle.addOnClearedListener { cm.close() } }
}
```

> mock flavor 里把 `CentralManager.mock(env, scope).apply { simulatePeripherals(...) }` 替换上去即可。

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

## 8. Profile 抽象（替代旧 BleManager 子类）

仿 nRF Blinky 与本仓库 `LedButtonProfile` 的风格，定义业务化 Profile 接口。

### 8.1 UUID 常量（`kotlin.uuid.Uuid`）

```kotlin
@OptIn(ExperimentalUuidApi::class)
object BlueTraceUuids {
    val SERVICE     : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR  : Uuid = Uuid.parse("0000xxxx-0000-1000-8000-00805f9b34fb")
}
```

> v2.0 已迁移到 `kotlin.uuid.Uuid`，必须 `@OptIn(ExperimentalUuidApi::class)`。不要再用 `java.util.UUID`。

### 8.2 Profile 接口

```kotlin
interface BlueTraceProfile {
    /** 设备主动上报的原始字节流。冷流，被订阅时才开 CCCD。 */
    val notifications: Flow<ByteArray>

    /** 发送指令。超过 MTU 时自动分片。 */
    suspend fun sendCommand(payload: ByteArray)

    /** Write 后立即收 Notify 的同步语义（避免 race）。 */
    suspend fun request(payload: ByteArray): ByteArray
}
```

### 8.3 实现

```kotlin
@OptIn(ExperimentalUuidApi::class)
class BlueTraceProfileImpl(
    private val peripheral: Peripheral,
    private val notifyChar: RemoteCharacteristic,
    private val writeChar:  RemoteCharacteristic,
) : BlueTraceProfile {

    override val notifications: Flow<ByteArray> =
        notifyChar.subscribe(onSubscription = {
            Timber.i("Subscribed to $uuid, isNotifying=$isNotifying")
        })

    override suspend fun sendCommand(payload: ByteArray) {
        val type = if (writeChar.properties.contains(CharacteristicProperty.WRITE_WITHOUT_RESPONSE))
            WriteType.WITHOUT_RESPONSE else WriteType.WITH_RESPONSE
        val maxLen = peripheral.maximumWriteValueLength(type)
        payload.chunked(maxLen).forEach { writeChar.write(it, type) }
    }

    override suspend fun request(payload: ByteArray): ByteArray =
        notifyChar.waitForValueChange { sendCommand(payload) }
            ?: error("No response from device")
}
```

### 8.4 在连接 scope 内安装 Profile

```kotlin
@OptIn(ExperimentalUuidApi::class)
suspend fun Peripheral.installBlueTrace(
    block: suspend CoroutineScope.(BlueTraceProfile) -> Unit,
) = profile(serviceUuid = BlueTraceUuids.SERVICE, required = true) { remoteService ->
    val notify = remoteService.characteristics.first { it.uuid == BlueTraceUuids.NOTIFY_CHAR }
    val write  = remoteService.characteristics.first { it.uuid == BlueTraceUuids.WRITE_CHAR }
    require(notify.isSubscribable()) { "Notify characteristic not subscribable" }
    block(BlueTraceProfileImpl(this@installBlueTrace, notify, write))
}
```

错误映射：

| 库异常 | 应用层 `CollectionState.Reason` |
| --- | --- |
| `OperationFailedException(SERVICE_NOT_FOUND)`（来自 `profile(required = true)`） | `DeviceNotSupported` |
| `NoSuchElementException`（特征缺失） | `DeviceNotSupported` |
| `OperationFailedException(SUBSCRIPTION_NOT_SUPPORTED)` | `SubscriptionFailed` |
| `OperationFailedException(WRITE_NOT_PERMITTED / GATT_ERROR)` | `WriteFailed` |
| `DeviceDisconnectedException`（v2.0 内部表达） | `Disconnected`（非主动断开走重连，见 §9） |

## 9. 连接编排（CollectionController）

### 9.1 连接选项

```kotlin
private val defaultOptions = CentralManager.ConnectionOptions.Direct(
    timeout = 10.seconds,
    retry = 3,
    retryDelay = 1.seconds,
    preferredPhy = Phy.PHY_LE_2M,
    automaticallyRequestHighestMtu = true,
)

private val autoConnectOptions = CentralManager.ConnectionOptions.AutoConnect()
```

- **直连**：UI 主动连接首次使用 `Direct`，库已内置 `retry + retryDelay`，比手写指数退避更稳。
- **重连**：长期在线场景切到 `AutoConnect`，由系统协议栈完成断连恢复，不耗 App 电。

### 9.2 Controller

```kotlin
class CollectionController @Inject constructor(
    private val centralManager: CentralManager,
    private val pipelineFactory: CollectionPipeline.Factory,
    @AppScope private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CollectionState>(CollectionState.Idle)
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    private var sessionJob: Job? = null

    fun start(peripheral: Peripheral) {
        if (sessionJob?.isActive == true) return
        sessionJob = appScope.launch { runSession(peripheral) }
    }

    fun stop() = sessionJob?.cancel()

    private suspend fun runSession(peripheral: Peripheral) {
        try {
            _state.value = CollectionState.Connecting
            centralManager.connect(peripheral, defaultOptions)

            // 在连接 scope 内同时跑：profile 安装 + 状态派生 + 数据流水线
            coroutineScope {
                observePeripheralState(peripheral)            // 派生 CollectionState
                peripheral.installBlueTrace { profile ->
                    _state.value = CollectionState.Subscribing
                    val pipeline = pipelineFactory.create(this)
                    val pipeJob = pipeline.start(profile.notifications)
                    _state.value = CollectionState.Collecting(0, 0)
                    pipeline.counters
                        .onEach { (saved, invalid) ->
                            _state.update { (it as? CollectionState.Collecting)
                                ?.copy(saved, invalid) ?: it }
                        }
                        .launchIn(this)
                    pipeJob.join()  // 持续直到 scope 被取消
                }
            }
        } catch (e: CancellationException) {
            // 用户主动停止，正常路径
            throw e
        } catch (e: Throwable) {
            _state.value = CollectionState.Failed(e.toReason(), e)
            Timber.e(e, "Collection session failed")
        } finally {
            runCatching { peripheral.disconnect() }
            _state.update { (it as? CollectionState.Collecting)?.let { CollectionState.Idle } ?: it }
        }
    }

    private fun CoroutineScope.observePeripheralState(p: Peripheral) {
        p.state
            .onEach { Timber.i("Peripheral ${p.address} -> $it") }
            .launchIn(this)
    }
}
```

注意要点：

- 连接 scope 是 `coroutineScope { ... }`，它的取消会自动 cancel：
  - `peripheral.profile { ... }` 内部协程（包括 notification 订阅）
  - `pipeline` 协程
  - `peripheral.state` 等观察
- 正常 `stop()` → `CancellationException` 不显示为错误；只显示真正异常。

### 9.3 应用级会话状态

```kotlin
sealed interface CollectionState {
    data object Idle : CollectionState
    data object Connecting : CollectionState
    data object Subscribing : CollectionState
    data class Collecting(val savedCount: Long, val invalidCount: Long) : CollectionState
    data class Failed(val reason: Reason, val cause: Throwable? = null) : CollectionState

    enum class Reason {
        BluetoothOff, LocationOff, PermissionDenied,
        ConnectionTimeout, DeviceNotSupported,
        SubscriptionFailed, WriteFailed, StorageFailed, Disconnected, Unknown,
    }
}

fun Throwable.toReason(): CollectionState.Reason = when (this) {
    is TimeoutCancellationException -> CollectionState.Reason.ConnectionTimeout
    is SecurityException             -> CollectionState.Reason.PermissionDenied
    is IOException                   -> CollectionState.Reason.StorageFailed
    // is OperationFailedException -> 按 reason 细分
    else -> CollectionState.Reason.Unknown
}
```

UI 层应用级状态来源：`combine(controller.state, env.bluetoothState, env.locationState) → UiState`，**蓝牙/定位状态来自 `AndroidEnvironment`，不自己监听 BroadcastReceiver**。

## 10. 数据解析层（纯 Kotlin）

```kotlin
data class SensorData(
    val receivedAtEpochMillis: Long,   // 系统墙钟，落盘用
    val monotonicNanos: Long,          // System.nanoTime()，跨重连算采样间隔
    val sequence: Int,
    val value1: Float,
    val value2: Float,
)

sealed interface ParseResult {
    data class Success(val data: SensorData) : ParseResult
    data class InvalidFrame(val reason: String, val raw: ByteArray) : ParseResult
}

fun interface SensorDataParser {
    fun parse(raw: ByteArray, receivedAt: Long = System.currentTimeMillis(),
              monotonicNanos: Long = System.nanoTime()): ParseResult
}

class DefaultSensorDataParser : SensorDataParser {
    override fun parse(raw: ByteArray, receivedAt: Long, monotonicNanos: Long): ParseResult {
        if (raw.size < MIN_LEN) return ParseResult.InvalidFrame("too short ${raw.size}", raw)
        if (raw[0] != 0xAA.toByte() || raw[1] != 0x55.toByte())
            return ParseResult.InvalidFrame("bad header", raw)
        // ... LEN / SEQ / VALUE / CHECKSUM / 0x0D 0x0A 校验 ...
        return ParseResult.Success(SensorData(receivedAt, monotonicNanos, /* ... */ ))
    }
    companion object { private const val MIN_LEN = 10 }
}
```

帧格式占位（拿到真实协议替换）：

```text
AA 55 | LEN | SEQ | VALUE_1 | VALUE_2 | CHECKSUM | 0D 0A
```

设计：

- 不抛异常到上层，解析失败返回 `InvalidFrame`。
- `monotonicNanos` 用 `System.nanoTime()` 保证墙钟跳变（NITZ、用户改时间）也能算真实采样间隔。

## 11. 数据流水线

把"BLE 收 → 解析 → 落盘"解耦为三个阶段，避免 IO 拖慢 BLE 接收。

```kotlin
class CollectionPipeline(
    private val parser: SensorDataParser,
    private val sink: SensorDataSink,
) {
    private val _saved = MutableStateFlow(0L)
    private val _invalid = MutableStateFlow(0L)
    val counters: Flow<Pair<Long, Long>> = combine(_saved, _invalid, ::Pair)

    fun start(source: Flow<ByteArray>): Job = source
        .buffer(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
        .map { parser.parse(it) }
        .onEach { res ->
            when (res) {
                is ParseResult.Success -> {
                    sink.write(res.data)        // Channel.send 内部异步落盘
                    _saved.update { it + 1 }
                }
                is ParseResult.InvalidFrame -> {
                    _invalid.update { it + 1 }
                    Timber.w("Invalid frame: ${res.reason}")
                }
            }
        }
        .catch { Timber.e(it, "Pipeline crashed") }
        .launchIn(GlobalScope) // 由调用方传入 scope；示例略

    interface Factory { fun create(scope: CoroutineScope): CollectionPipeline }
}
```

背压策略：

- 默认 `BufferOverflow.SUSPEND`：BLE 流被反压时降速发送（采集场景宁可慢一点，也不丢数据）。
- 若产品要求"宁可丢数据也不阻塞"，切 `BufferOverflow.DROP_OLDEST`，并把丢弃计数也暴露到 UI。

## 12. 本地文件保存

### 12.1 文件位置

```kotlin
val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
    .let { File(it, "BlueTrace") }.apply { mkdirs() }
```

路径：`/Android/data/com.example.bluetrace/files/Documents/BlueTrace/`。**无需任何存储运行时权限**（API 24+ 起 Scoped 路径）。

### 12.2 CsvSensorDataSink（Channel + 单消费者）

```kotlin
class CsvSensorDataSink(
    private val outputFile: File,
    scope: CoroutineScope,
    private val rotateBytes: Long = 16L * 1024 * 1024,  // 16 MB 自动滚动
) : SensorDataSink {

    private val channel = Channel<SensorData>(capacity = 1024, BufferOverflow.SUSPEND)
    private val writerJob: Job = scope.launch(Dispatchers.IO) {
        var writer = openWriter(outputFile, header = !outputFile.exists())
        var bytes = outputFile.length()
        var pending = 0
        var lastFlush = System.currentTimeMillis()
        try {
            for (d in channel) {
                val line = "${d.receivedAtEpochMillis},${d.sequence},${d.value1},${d.value2}\n"
                writer.write(line)
                bytes += line.length
                pending++
                val now = System.currentTimeMillis()
                if (pending >= 50 || now - lastFlush > 1_000) {
                    writer.flush(); pending = 0; lastFlush = now
                }
                if (bytes >= rotateBytes) {
                    writer.close()
                    val rotated = rotateFile(outputFile)
                    writer = openWriter(rotated, header = true)
                    bytes = 0
                }
            }
        } finally { writer.flush(); writer.close() }
    }

    override suspend fun write(data: SensorData) = channel.send(data)
    override suspend fun close() { channel.close(); writerJob.join() }

    private fun openWriter(file: File, header: Boolean): BufferedWriter {
        val w = file.bufferedWriter(Charsets.UTF_8)
        if (header) w.write("receivedAtEpochMillis,sequence,value1,value2\n")
        return w
    }
    private fun rotateFile(original: File): File {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        return File(original.parentFile, "bluetrace_${ts}.csv")
    }
}
```

`SensorDataSink` 接口：

```kotlin
interface SensorDataSink {
    suspend fun write(data: SensorData)
    suspend fun close()
}
```

### 12.3 导出到 Downloads（MediaStore，API 24+ 全兼容）

```kotlin
suspend fun exportCsvToDownloads(
    context: Context,
    sourceFile: File,
    displayName: String = sourceFile.name,
): Uri = withContext(Dispatchers.IO) {
    val collection: Uri = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        // API 24-28 没有 MediaStore.Downloads，落到 ACTION_CREATE_DOCUMENT 或公共 Download 目录
        TODO("API 28- 走 SAF ACTION_CREATE_DOCUMENT")
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlueTrace")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(collection, values)
        ?: error("Failed to create MediaStore entry")
    context.contentResolver.openOutputStream(uri)!!.use { out ->
        sourceFile.inputStream().use { it.copyTo(out) }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    }
    uri
}
```

> 24–28 的设备没有 `MediaStore.Downloads`，推荐用 `Intent.ACTION_CREATE_DOCUMENT`（SAF）让用户选位置，避免申请已被废弃的 `WRITE_EXTERNAL_STORAGE`。

## 13. 后台采集（Foreground Service，Android 14+ 适配）

```kotlin
@AndroidEntryPoint
class CollectionForegroundService : Service() {

    @Inject lateinit var controller: CollectionController

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueTrace 采集中")
            .setContentText("正在记录 BLE 数据")
            .setSmallIcon(R.drawable.ic_ble)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ 必须显式声明类型
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { controller.stop(); super.onDestroy() }

    companion object {
        const val CHANNEL_ID = "bluetrace_collection"
        const val NOTIFICATION_ID = 1001
    }
}
```

启动前必须满足：

1. `POST_NOTIFICATIONS` 已运行时授权（API 33+）。
2. `BLUETOOTH_CONNECT` 已运行时授权（API 31+）。
3. 通知 channel 已创建（`BlueTraceApplication.onCreate` 中 `createNotificationChannel`）。

否则 Android 14+ 抛 `ForegroundServiceTypeException`。

## 14. ViewModel & UI

### 14.1 DataCollectionViewModel

```kotlin
@HiltViewModel
class DataCollectionViewModel @Inject constructor(
    private val controller: CollectionController,
    private val env: AndroidEnvironment,
) : ViewModel() {

    data class UiState(
        val bluetoothOn: Boolean,
        val locationOn: Boolean,
        val collection: CollectionState,
        val latest: SensorData? = null,
    )

    val state: StateFlow<UiState> = combine(
        env.bluetoothState, env.locationState, controller.state,
    ) { bt, loc, coll ->
        UiState(bluetoothOn = bt == Manager.State.POWERED_ON,
                locationOn = loc, collection = coll)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        UiState(env.isBluetoothEnabled, env.isLocationEnabled, CollectionState.Idle))

    fun onConnect(peripheral: Peripheral) = controller.start(peripheral)
    fun onStop() = controller.stop()
}
```

### 14.2 Compose UI 要点

- 全屏 edge-to-edge：`enableEdgeToEdge()`，配合 `Scaffold(contentWindowInsets = WindowInsets.statusBars)`。
- 收集状态用 `collectAsStateWithLifecycle()`（API 24+ 兼容），避免后台时持续重组。
- 顶层 NavHost 用 type-safe routes：

```kotlin
@Serializable data object PermissionGate
@Serializable data object Scan
@Serializable data class Collect(val deviceAddress: String)
```

- Predictive Back：`enableOnBackInvokedCallback="true"` + Compose `BackHandler` 自动支持。
- Splash Screen：`installSplashScreen()` 在 `super.onCreate` 之前调用。

### 14.3 页面流

```text
PermissionGateScreen
  └─→ ScanScreen
        └─→ DataCollectionScreen
              └─ (后台) CollectionForegroundService
```

`PermissionGateScreen` 展示：蓝牙开关 / 定位开关 / 必要权限三组 chip，每组都有"授权 / 打开设置 / 打开定位 / 开启蓝牙"快捷按钮，按钮调用 `env.enableBluetooth()` 或跳 `ACTION_LOCATION_SOURCE_SETTINGS`。

`DataCollectionScreen` 展示：连接状态、当前设备、最新解析数据、saved/invalid 计数、当前文件路径、Start/Stop、Send Command、Export to Downloads。

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

- `DefaultSensorDataParserTest`：正常帧 / 长度错 / 头错 / 尾错 / checksum 错。
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
    // ... 执行 scan → connect → 等待 CollectionState.Collecting → 断言 sink 写入
}
```

测试矩阵：

- happy path：scan → connect → subscribe → 收 N 帧 → stop → 文件 N 行。
- 缺失 NOTIFY_CHAR → `Failed(DeviceNotSupported)`。
- 连接中断 → `Failed(Disconnected)`，自动重连（AutoConnect 选项）成功恢复。
- 蓝牙关闭（`env.simulateBluetoothOff()`）→ `Failed(BluetoothOff)`，sink 停止。
- 写指令失败（mock characteristic 配置成 `WRITE_NOT_PERMITTED`）→ `Failed(WriteFailed)`。

### 16.3 Compose UI 测试

- 用 `createComposeRule()` + Hilt test runner，注入 `MockAndroidEnvironment` 和 mock CentralManager。
- 验证：未授权时 ScanScreen 显示 PermissionGate；扫描中显示 progress；点击 device 跳转 DataCollect 并显示 Connecting。

## 17. 实施里程碑

| Phase | 目标 | 验收 |
| --- | --- | --- |
| **P0 探针** | 用本仓库 `:sample` 在真机上跑通连接 nRF Blinky，确认 v2.0 API 在 Android 7 / Android 14 / Android 15 上的行为 | 真机日志看到 LBS notify |
| **P1 骨架** | 新建 `:bluetrace-app`，接入 Hilt、Timber、`NativeAndroidEnvironment`、`CentralManager`，完成 `PermissionGateScreen` 和 `ScanScreen`（mock flavor 调通） | mock 设备能在 ScanScreen 列出 |
| **P2 Profile + Pipeline** | `BlueTraceProfile` + `BlueTraceProfileImpl` + `CollectionController` + `CollectionPipeline` + 占位 parser；UI 显示 saved/invalid 计数 | mock 测试 happy path 通过 |
| **P3 持久化** | `CsvSensorDataSink`（Channel + 滚动 flush）+ Downloads 导出 | 真机生成可读 CSV，能导出到 Downloads |
| **P4 后台 + 稳定性** | `CollectionForegroundService`（connectedDevice 类型）+ `AutoConnect` 切换；蓝牙/定位关闭恢复；UI 错误态完整 | 灭屏/锁屏后仍持续采集 |
| **P5 协议替换** | 拿到真实设备协议后，仅修改 `BlueTraceUuids` + `DefaultSensorDataParser`，上层零改动 | Diff 限定在 `profile/` + `data/` |

## 18. 关键设计决策（与取舍）

1. **底层只用 Kotlin-BLE-Library v2.0**：不再依赖旧 `BleManager`/`BluetoothLeScannerCompat`，统一协程/Flow 风格，省掉大量回调状态机。
2. **不重新发明 BLE 状态**：应用级 `CollectionState` 完全由 `peripheral.state` + `env.bluetoothState` + `env.locationState` 派生，**单一真理源**。
3. **Profile 隔离 UUID/协议**：业务层只依赖 `BlueTraceProfile.notifications: Flow<ByteArray>`，换协议只动 `BlueTraceProfileImpl` + `DefaultSensorDataParser`。
4. **重连交给库**：直连用 `ConnectionOptions.Direct(retry=3)`，长期在线用 `AutoConnect`，避免手写指数退避。
5. **数据流水线 Channel-based**：BLE → 解析 → IO 三阶段解耦，背压策略可配置（SUSPEND 默认）。
6. **存储默认 Scoped**：App 私有外部目录免权限；导出走 MediaStore (Android 10+) 或 SAF (Android 7-9)。
7. **后台采集走 Foreground Service**：声明 `connectedDevice` 类型，运行时确保 `BLUETOOTH_CONNECT` + `POST_NOTIFICATIONS` 已授权。
8. **Mock 优先的测试策略**：核心 BLE 流程全部 JVM 单测覆盖（`MockAndroidEnvironment` + `PeripheralSpec`），CI 不需要真机。
9. **环境/CentralManager 生命周期绑定 `ActivityRetainedComponent`**：通过 `ActivityRetainedLifecycle.addOnClearedListener` 在 `onCleared` 调用 `close()`，避免泄漏 `BroadcastReceiver`。
10. **UUID 全部用 `kotlin.uuid.Uuid`**：与 v2.0 API 一致，去掉 `java.util.UUID` 转换样板。
