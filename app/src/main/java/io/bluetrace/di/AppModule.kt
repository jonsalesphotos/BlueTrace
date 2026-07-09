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
import io.bluetrace.shared.protocol.SampleDecoder
import io.bluetrace.shared.protocol.registry.HrsProfile
import io.bluetrace.shared.protocol.registry.MockBleProfile
import io.bluetrace.shared.protocol.registry.ProtocolRegistry
import io.bluetrace.shared.protocol.registry.RegistrySampleDecoder
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
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    // ---- 平台 / 基础设施 ----
    single<EpochClock> { AndroidEpochClock() }
    single<TimeZoneProvider> { AndroidTimeZoneProvider() }
    // 应用级 scope（架构评估 A2）：挂全局异常兜底——否则任何跑在其上的未捕获协程异常直接崩进程
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
    // 采集场景词表（v6）：从 assets/scenes.json 加载（与 Docs/architecture/scenes.json 同源），解析失败兜底 EMPTY。
    single<io.bluetrace.shared.domain.SceneCatalog> {
        io.bluetrace.shared.domain.parseSceneCatalog(
            androidContext().assets.open("scenes.json").bufferedReader().use { it.readText() },
        )
    }

    // ---- 共享核心（KMP commonMain）----
    single { SessionStore(get(), get(), Dispatchers.IO).also { it.ensureRoot() } } // A3：Store 自守 IO
    // 应用日志（v7）：滚动 .log 文件。writerScope 必须单线程（保序、无并发追加竞态）。
    single(named("logWriter")) {
        // A2：日志写线程兜底走 logcat（不能写 DiagnosticsLog 自己，会递归）
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineExceptionHandler { _, e ->
                android.util.Log.e("BlueTrace", "log writer crashed", e)
            },
        )
    }
    single<DiagnosticsLog> {
        val ctx = androidContext()
        // logsDir 与 sessionsRoot 同根（getExternalFilesDir），adb pull 直接可取；不放内部 filesDir。
        val logsDir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve("logs").path.toPath()
        FileDiagnosticsLog(get(), logsDir, get(), get(), get(named("logWriter")))
    }
    // ⚠️ Mock/真实 BLE 的唯一切换点：全 App 只经 BleClient 接口消费（service/controller 均不感知具体类型）。
    // 运行时可切换（设置 · 仅 DEBUG 行「使用 Mock BLE」，重启生效）：默认真实 GATT（2026-07-02 用户定"纯真实"），
    // Mock 供无设备演示/UI 回归。解码走下方注册表(02 R2): 真实模式已可解 HRS 心率带,
    // 自研 DUT 协议待 M7 冻结——冻结前真实 DUT 只落 raw HEX(source of truth) + malformed 告警, 属预期。
    single { MockBleClient(get(), get()) }
    single<BleClient> {
        if (io.bluetrace.data.android.BleBackendSwitch.useMock(androidContext())) get<MockBleClient>()
        else io.bluetrace.data.android.AndroidBleClient(androidContext(), get())
    }
    // 02 R2: 注册表按后端拼装——Mock 后端全员 Mock 线协议; 真实后端注册 HRS(R4 心率带先行),
    // 自研 DUT 冻结前无 profileId → RegistrySampleDecoder 回退 Mock profile, 解不出真实字节
    // 只产 Malformed 诊断(等价旧 unparseable 告警), raw HEX 照常落盘。
    single {
        val profiles =
            if (io.bluetrace.data.android.BleBackendSwitch.useMock(androidContext())) listOf(MockBleProfile())
            else listOf(HrsProfile())
        ProtocolRegistry(profiles)
    }
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
            // A1：会话事件循环+落盘走 IO 弹性池；limitedParallelism(1) 保持单线程串行语义
            runContext = Dispatchers.IO.limitedParallelism(1),
            storageMonitor = get(),
            gnssSource = get(),
        )
    }

    // ---- app 级状态 / 仓库 ----
    // B2：事件驱动登记表（订阅 linkState 自动清退被动断连），下沉 shared 供 iOS 复用
    single { ConnectionRegistry(get(), get()) }
    single { io.bluetrace.data.android.DeviceLogStore(androidContext()) }
    single { io.bluetrace.shared.domain.CollectDraft(get(), get<io.bluetrace.shared.domain.SceneCatalog>(), get()) }
    single<AppPreferences> { DataStoreAppPreferences(androidContext()) }
    // 用户存储（v7）：SQLDelight。driver 由 app 注入（commonMain 不碰平台）；io = Dispatchers.IO（Android）。
    single<SqlDriver> { AndroidSqliteDriver(BlueTraceDb.Schema, androidContext(), "bluetrace.db") }
    single { BlueTraceDb(get()) }
    single<SubjectRepository> { SqlDelightSubjectRepository(get(), Dispatchers.IO) }
    single<EnvironmentRepository> { AndroidEnvironmentRepository(androidContext(), get()) }
    single { MediaStoreExporter(androidContext(), get()) }
    single { io.bluetrace.data.android.OtaZipLoader(androidContext()) }

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
    viewModel { io.bluetrace.viewmodel.ConsoleConnectViewModel(get(), get()) }
    // 设备维护（DUT）控制台：设备日志/操作日志经 MediaStore 导出到 Download/BlueTrace/logs/
    viewModel {
        io.bluetrace.viewmodel.DeviceConsoleViewModel(
            ble = get(),
            registry = get(),
            clock = get(),
            zone = get(),
            subjects = get(),
            exporter = get(),
            logStore = get(),
        )
    }
    // DEBUG：OTA 测试（选烧录包→校验→循环刷入）
    viewModel {
        io.bluetrace.viewmodel.OtaTestViewModel(
            ble = get(),
            registry = get(),
            clock = get(),
            zone = get(),
            zipLoader = get(),
        )
    }
}
