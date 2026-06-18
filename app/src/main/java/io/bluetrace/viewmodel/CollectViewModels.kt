package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.shared.domain.AppPreferences
import io.bluetrace.shared.domain.AssignedDevice
import io.bluetrace.shared.domain.CollectMode
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.domain.SessionConfig
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 环境与权限（启动门控 / 蓝牙开关 / 设置A）。 */
class EnvironmentViewModel(private val env: EnvironmentRepository) : ViewModel() {
    val state = env.state
    fun refresh() = env.refresh()
}

data class CollectHomeUiState(
    val currentSubject: Subject? = null,
    val connectedDevices: List<ScannedDevice> = emptyList(),
    val mode: CollectMode = CollectMode.WEAR,
    val gnssEnabled: Boolean = false,
    val hardSatisfied: Boolean = true,
    val bluetoothOn: Boolean = true,
) {
    val connectedCount: Int get() = connectedDevices.size
    val canStart: Boolean get() = currentSubject != null && connectedDevices.isNotEmpty()
}

/** 采集主界面（采集A）：用户 / 已连设备 / 模式 / GNSS + 开始采集。 */
class CollectHomeViewModel(
    subjectRepo: SubjectRepository,
    private val registry: ConnectionRegistry,
    private val prefs: AppPreferences,
    private val controller: SessionController,
    private val env: EnvironmentRepository,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
) : ViewModel() {

    private val _mode = MutableStateFlow(CollectMode.WEAR)

    private data class Base(
        val subject: Subject?,
        val connected: List<ScannedDevice>,
        val mode: CollectMode,
        val gnss: Boolean,
    )

    val uiState: StateFlow<CollectHomeUiState> =
        combine(
            combine(subjectRepo.subjects, subjectRepo.currentId) { subjects, currentId ->
                subjects.firstOrNull { it.id == currentId } ?: subjects.firstOrNull()
            },
            registry.connected,
            _mode,
            prefs.gnssEnabled,
        ) { subject, connected, mode, gnss -> Base(subject, connected, mode, gnss) }
            .let { base ->
                combine(base, env.state) { b, envState ->
                    CollectHomeUiState(
                        currentSubject = b.subject,
                        connectedDevices = b.connected,
                        mode = b.mode,
                        gnssEnabled = b.gnss,
                        hardSatisfied = envState.hardSatisfied,
                        bluetoothOn = envState.bluetoothOn,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectHomeUiState())

    fun setMode(mode: CollectMode) { _mode.value = mode }

    fun refreshEnv() = env.refresh()

    /** 构建 SessionConfig 并开始采集（§7.3）。返回 false 表示前置条件不足。 */
    fun startSession(): Boolean {
        val s = uiState.value
        val subject = s.currentSubject ?: return false
        if (s.connectedDevices.isEmpty()) return false
        val now = clock.nowMs()
        val config = SessionConfig(
            subject = subject,
            mode = s.mode,
            devices = s.connectedDevices.map { AssignedDevice(it.id, it.name, it.address, it.kind, it.profileId) },
            enabledTypes = CollectType.defaults,
            gnssEnabled = s.gnssEnabled,
            startEpochMs = now,
            timezoneId = zone.zoneId(),
            utcOffsetSeconds = zone.offsetSeconds(),
        )
        controller.start(config)
        return true
    }
}
