package io.bluetrace.di

import io.bluetrace.data.android.AndroidEnvironmentRepository
import io.bluetrace.data.android.AndroidEpochClock
import io.bluetrace.data.android.AndroidTimeZoneProvider
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.bluetrace.data.android.DataStoreAppPreferences
import io.bluetrace.data.android.MediaStoreExporter
import io.bluetrace.shared.data.SqlDelightSubjectRepository
import io.bluetrace.shared.db.BlueTraceDb
import io.bluetrace.data.android.sessionsRoot
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.mock.MockBleClient
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.device.DeviceSessionManager
import io.bluetrace.shared.device.HrsDeviceProfile
import io.bluetrace.shared.device.MockDeviceProfile
import io.bluetrace.shared.protocol.SampleDecoder
import io.bluetrace.shared.protocol.registry.MockBleProfile
import io.bluetrace.shared.protocol.registry.ProtocolRegistry
import io.bluetrace.shared.protocol.registry.RegistrySampleDecoder
import io.bluetrace.shared.s7.S7DeviceProfile
import io.bluetrace.shared.zx.ZxDeviceProfile
import io.bluetrace.shared.session.DefaultSessionController
import io.bluetrace.shared.session.DiagnosticsLog
import io.bluetrace.shared.session.FileDiagnosticsLog
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.viewmodel.CollectHomeViewModel
import io.bluetrace.viewmodel.DataViewModel
import io.bluetrace.viewmodel.DeviceScanViewModel
import io.bluetrace.viewmodel.EnvironmentViewModel
import io.bluetrace.viewmodel.ExportViewModel
import io.bluetrace.viewmodel.RunViewModel
import io.bluetrace.viewmodel.SessionDetailViewModel
import io.bluetrace.viewmodel.SettingsViewModel
import io.bluetrace.viewmodel.SubjectViewModel
import io.bluetrace.shared.session.LogLevel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    // ---- 平台 / 基础设施 ----
    single<EpochClock> { AndroidEpochClock() }
    single<TimeZoneProvider> { AndroidTimeZoneProvider() }
    // 应用级 scope(架构评估 A2): 挂全局异常兜底——否则任何跑在其上的未捕获协程异常直接崩进程
    single<CoroutineScope> {
        val koin = getKoin()
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
                runCatching { koin.get<DiagnosticsLog>().add(LogLevel.ERROR, "scope", "uncaught: ${e.message ?: e::class.simpleName}") }
            },
        )
    }
    single<FileSystem> { FileSystem.SYSTEM }
    single<Path> { sessionsRoot(androidContext()) }
    single<io.bluetrace.shared.data.StorageMonitor> { io.bluetrace.data.android.AndroidStorageMonitor(androidContext()) }
    single<io.bluetrace.shared.data.GnssSource> { io.bluetrace.data.android.AndroidGnssSource(androidContext()) }
    // 采集场景词表(v6): 从 assets/scenes.json 加载(与 Docs/architecture/scenes.json 同源), 解析失败兜底 EMPTY.
    single<io.bluetrace.shared.domain.SceneCatalog> {
        io.bluetrace.shared.domain.parseSceneCatalog(
            androidContext().assets.open("scenes.json").bufferedReader().use { it.readText() },
        )
    }

    // ---- 共享核心(KMP commonMain)----
    single { SessionStore(get(), get(), Dispatchers.IO).also { it.ensureRoot() } } // A3：Store 自守 IO
    // 应用日志(v7): 滚动 .log 文件. writerScope 必须单线程(保序, 无并发追加竞态).
    single(named("logWriter")) {
        // A2: 日志写线程兜底走 logcat(不能写 DiagnosticsLog 自己, 会递归)
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineExceptionHandler { _, e ->
                android.util.Log.e("BlueTrace", "log writer crashed", e)
            },
        )
    }
    // 工程配置(v8): files/config/bluetrace_config.json 同步加载(小文件); 公共镜像在 Application 后台跑
    single { io.bluetrace.data.android.ConfigStore(androidContext()).apply { load() } }
    single<DiagnosticsLog> {
        val ctx = androidContext()
        // logsDir 与 sessionsRoot 同根(getExternalFilesDir), adb pull 直接可取; 不放内部 filesDir.
        // v8 目录树: files/log/app(appLogsDir 内含 v7 files/logs 迁移); 保留天数走工程配置.
        FileDiagnosticsLog(
            get(), io.bluetrace.data.android.appLogsDir(ctx), get(), get(), get(named("logWriter")),
            retainDays = get<io.bluetrace.data.android.ConfigStore>().current.log.appRetainDays,
        )
    }
    // ⚠️ BLE 后端的唯一切换点: 全 App 只经 BleClient 接口消费(service/controller 均不感知具体类型).
    // 三向切换(设置 · 仅 DEBUG, 重启生效), 优先级 Mock > Nordic > 自写:
    //   - "使用 Mock BLE"   -> MockBleClient(无设备演示/UI 回归);
    //   - "使用 Nordic BLE" -> NordicBleClient(W1.5 引入的第二实现, W1.6 真机 A/B 闸门候选默认);
    //   - 默认               -> AndroidBleClient(自写真实 GATT, 2026-07-02 用户定"纯真实").
    // 解码走下方注册表(02 R2): 真实模式已可解 HRS 心率带, 自研 DUT 协议待 M7 冻结——冻结前真实 DUT
    // 只落 raw HEX(source of truth) + malformed 告警, 属预期.
    single { MockBleClient(get(), get()) }
    single<BleClient> {
        when {
            io.bluetrace.data.android.BleBackendSwitch.useMock(androidContext()) -> get<MockBleClient>()
            io.bluetrace.data.android.BleBackendSwitch.useNordic(androidContext()) ->
                io.bluetrace.data.android.NordicBleClient(androidContext(), get(), get())
            else -> io.bluetrace.data.android.AndroidBleClient(androidContext(), get())
        }
    }
    // W2 统一识别 + W3 会话宿主: 设备档案目录按后端拼装(识别真源唯一).
    //   - Mock 后端 = [S7DeviceProfile, ZxDeviceProfile, MockDeviceProfile](W3 裁定 a + W6 异构验收):
    //     Mock roster 的 S7 设备广播含 FFE0, 由 S7DeviceProfile 命中(与真机同一条识别路径)-> 经会话
    //     宿主拿到 S7 控制面; ZX 设备广播含 AA00, 由 ZxDeviceProfile 命中(W6 第二协议, shared/.../zx
    //     包, 与 S7 全维度异构); 其余 Mock 合成设备落 catch-all MockDeviceProfile. 顺序即优先级
    //     (S7/ZX 各按广播精确命中, catch-all 兜底最后).
    //   - 真实后端 = [S7DeviceProfile, HrsDeviceProfile].
    // annotate 只对 profileId=null 的原始上报打标; Mock roster 设备已带 profileId, 加 S7Profile/
    // ZxProfile 对扫描/解码零影响(两者 dataPlane 均为 null, toProtocolRegistry 仍只收 MockBleProfile).
    single {
        val profiles =
            if (io.bluetrace.data.android.BleBackendSwitch.useMock(androidContext())) listOf(S7DeviceProfile(), ZxDeviceProfile(), MockDeviceProfile())
            else listOf(S7DeviceProfile(), HrsDeviceProfile())
        DeviceProfileCatalog(profiles)
    }
    // 02 R2: 解码侧 ProtocolRegistry 由 Catalog 派生(非空 dataPlane 组装, 识别真源唯一)——
    // Mock 后端 = [MockBleProfile]; 真实后端 = [HrsProfile](S7.dataPlane=null 跳过, DUT 协议
    // 冻结前无 profileId → RegistrySampleDecoder 回退 Mock profile, 解不出真实字节只产 Malformed
    // 诊断(等价旧 unparseable 告警), raw HEX 照常落盘). 解码侧消费接口零改动.
    single<ProtocolRegistry> { get<DeviceProfileCatalog>().toProtocolRegistry() }
    single<SampleDecoder> { RegistrySampleDecoder(get(), MockBleProfile()) }
    single<SessionController> {
        DefaultSessionController(
            bleClient = get(),
            decoder = get(),
            fileSystem = get(),
            sessionsRoot = get(),
            sessionStore = get(),
            clock = get(),
            zone = get(),
            diagnostics = get(),
            scope = get(),
            // A1: 会话事件循环+落盘走 IO 弹性池; limitedParallelism(1) 保持单线程串行语义
            runContext = Dispatchers.IO.limitedParallelism(1),
            storageMonitor = get(),
            gnssSource = get(),
        )
    }

    // ---- app 级状态 / 仓库 ----
    // B2: 事件驱动登记表(订阅 linkState 自动清退被动断连), 下沉 shared 供 iOS 复用
    single { ConnectionRegistry(get(), get()) }
    // W3 设备会话宿主(app 级): 每设备一份会话生命周期(identify -> connect(gattSpec) -> confirm -> 控制面).
    // 长事务不落 viewModelScope(挂 app 级 CoroutineScope). 运行时消费方(VM/UI)W5 接; 现无消费方.
    single { DeviceSessionManager(get(), get(), get(), get(), get()) }
    single { io.bluetrace.data.android.DeviceLogStore(androidContext()) }
    single { io.bluetrace.shared.domain.CollectDraft(get(), get<io.bluetrace.shared.domain.SceneCatalog>(), get()) }
    single<AppPreferences> { DataStoreAppPreferences(androidContext()) }
    // 用户存储(v7): SQLDelight. driver 由 app 注入(commonMain 不碰平台); io = Dispatchers.IO(Android).
    single<SqlDriver> { AndroidSqliteDriver(BlueTraceDb.Schema, androidContext(), "bluetrace.db") }
    single { BlueTraceDb(get()) }
    single<SubjectRepository> { SqlDelightSubjectRepository(get(), Dispatchers.IO) }
    single<EnvironmentRepository> { AndroidEnvironmentRepository(androidContext(), get()) }
    single { MediaStoreExporter(androidContext(), get(), get()) }
    single { io.bluetrace.data.android.OtaZipLoader(androidContext()) }
    // OTA 执行日志落盘(Download/BlueTrace/log/ota/, 每次运行一个文件)
    single { io.bluetrace.data.android.OtaRunLogStore(androidContext()) }

    // ---- ViewModels ----
    viewModelOf(::EnvironmentViewModel)
    viewModelOf(::CollectHomeViewModel)
    viewModelOf(::DeviceScanViewModel)
    viewModelOf(::SubjectViewModel)
    viewModelOf(::RunViewModel)
    viewModelOf(::DataViewModel)
    viewModelOf(::ExportViewModel)
    viewModel { (folder: String) -> SessionDetailViewModel(folder, get()) }
    viewModel { SettingsViewModel(androidContext(), get(), get()) }
    viewModel { io.bluetrace.viewmodel.ConsoleConnectViewModel(get(), get(), get()) }
    // 设备维护(DUT)控制台: DeviceSessionManager 首个运行时消费方(W5); 控制走 DeviceControl 六分面.
    // 固件日志 → Download/BlueTrace/log/firmware/, 操作日志 → log/app/
    viewModel {
        io.bluetrace.viewmodel.DeviceConsoleViewModel(
            ble = get(),
            sessionManager = get(),
            registry = get(),
            clock = get(),
            zone = get(),
            subjects = get(),
            exporter = get(),
            logStore = get(),
        )
    }
    // DEBUG: OTA 测试(选烧录包→校验→循环刷入); 执行日志落 log/ota/, 手动停止发重启指令(appScope 善后)
    viewModel {
        io.bluetrace.viewmodel.OtaTestViewModel(
            ble = get(),
            registry = get(),
            catalog = get(),
            clock = get(),
            zone = get(),
            zipLoader = get(),
            otaLogStore = get(),
            configStore = get(),
            appScope = get(),
        )
    }
    // DEBUG: 多设备 OTA(顶栏开关打开; 工作队列串行逐台刷入, 一个包全队列共用)
    viewModel {
        io.bluetrace.viewmodel.MultiOtaViewModel(
            ble = get(),
            registry = get(),
            catalog = get(),
            clock = get(),
            zone = get(),
            zipLoader = get(),
            otaLogStore = get(),
            configStore = get(),
            appScope = get(),
        )
    }
}
