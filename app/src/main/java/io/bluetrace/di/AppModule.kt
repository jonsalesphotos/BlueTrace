package io.bluetrace.di

import io.bluetrace.data.android.AndroidEnvironmentRepository
import io.bluetrace.data.android.AndroidEpochClock
import io.bluetrace.data.android.AndroidTimeZoneProvider
import io.bluetrace.data.android.DataStoreAppPreferences
import io.bluetrace.data.android.DataStoreSubjectRepository
import io.bluetrace.data.android.MediaStoreExporter
import io.bluetrace.data.android.sessionsRoot
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.MockBleClient
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.protocol.MockSampleDecoder
import io.bluetrace.shared.protocol.SampleDecoder
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
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
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
    single { SessionStore(get(), get()).also { it.ensureRoot() } }
    // 应用日志（v7）：滚动 .log 文件。writerScope 必须单线程（保序、无并发追加竞态）。
    single(named("logWriter")) { CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) }
    single<DiagnosticsLog> {
        val ctx = androidContext()
        // logsDir 与 sessionsRoot 同根（getExternalFilesDir），adb pull 直接可取；不放内部 filesDir。
        val logsDir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve("logs").path.toPath()
        FileDiagnosticsLog(get(), logsDir, get(), get(), get(named("logWriter")))
    }
    single { MockBleClient(get(), get()) }
    single<BleClient> { get<MockBleClient>() }
    single<SampleDecoder> { MockSampleDecoder() }
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
            storageMonitor = get(),
            gnssSource = get(),
        )
    }

    // ---- app 级状态 / 仓库 ----
    single { ConnectionRegistry() }
    single { io.bluetrace.domain.CollectDraft(get(), get<io.bluetrace.shared.domain.SceneCatalog>(), get()) }
    single<AppPreferences> { DataStoreAppPreferences(androidContext()) }
    single<SubjectRepository> { DataStoreSubjectRepository(androidContext()) }
    single<EnvironmentRepository> { AndroidEnvironmentRepository(androidContext()) }
    single { MediaStoreExporter(androidContext(), get()) }

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
}
