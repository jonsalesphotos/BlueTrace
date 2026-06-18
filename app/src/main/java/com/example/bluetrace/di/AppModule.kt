package com.example.bluetrace.di

import com.example.bluetrace.data.android.AndroidEnvironmentRepository
import com.example.bluetrace.data.android.AndroidEpochClock
import com.example.bluetrace.data.android.AndroidTimeZoneProvider
import com.example.bluetrace.data.android.DataStoreAppPreferences
import com.example.bluetrace.data.android.DataStoreSubjectRepository
import com.example.bluetrace.data.android.MediaStoreExporter
import com.example.bluetrace.data.android.sessionsRoot
import com.example.bluetrace.domain.ConnectionRegistry
import com.example.bluetrace.shared.ble.BleClient
import com.example.bluetrace.shared.ble.MockBleClient
import com.example.bluetrace.shared.data.SessionStore
import com.example.bluetrace.shared.domain.AppPreferences
import com.example.bluetrace.shared.domain.EnvironmentRepository
import com.example.bluetrace.shared.domain.SubjectRepository
import com.example.bluetrace.shared.protocol.MockSampleDecoder
import com.example.bluetrace.shared.protocol.SampleDecoder
import com.example.bluetrace.shared.session.DefaultSessionController
import com.example.bluetrace.shared.session.DiagnosticsLog
import com.example.bluetrace.shared.session.InMemoryDiagnosticsLog
import com.example.bluetrace.shared.session.SessionController
import com.example.bluetrace.shared.util.EpochClock
import com.example.bluetrace.shared.util.TimeZoneProvider
import com.example.bluetrace.viewmodel.CollectHomeViewModel
import com.example.bluetrace.viewmodel.DataViewModel
import com.example.bluetrace.viewmodel.DeviceScanViewModel
import com.example.bluetrace.viewmodel.EnvironmentViewModel
import com.example.bluetrace.viewmodel.ExportViewModel
import com.example.bluetrace.viewmodel.RunViewModel
import com.example.bluetrace.viewmodel.SessionDetailViewModel
import com.example.bluetrace.viewmodel.SettingsViewModel
import com.example.bluetrace.viewmodel.SubjectViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import org.koin.android.ext.koin.androidContext
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

    // ---- 共享核心（KMP commonMain）----
    single { SessionStore(get(), get()).also { it.ensureRoot() } }
    single<DiagnosticsLog> { InMemoryDiagnosticsLog(get()) }
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
        )
    }

    // ---- app 级状态 / 仓库 ----
    single { ConnectionRegistry() }
    single<AppPreferences> { DataStoreAppPreferences(androidContext()) }
    single<SubjectRepository> { DataStoreSubjectRepository(androidContext()) }
    single<EnvironmentRepository> { AndroidEnvironmentRepository(androidContext()) }
    single { MediaStoreExporter(androidContext()) }

    // ---- ViewModels ----
    viewModelOf(::EnvironmentViewModel)
    viewModelOf(::CollectHomeViewModel)
    viewModelOf(::DeviceScanViewModel)
    viewModelOf(::SubjectViewModel)
    viewModelOf(::RunViewModel)
    viewModelOf(::DataViewModel)
    viewModelOf(::ExportViewModel)
    viewModel { (folder: String) -> SessionDetailViewModel(folder, get()) }
    viewModel { SettingsViewModel(androidContext(), get(), get(), get()) }
}
