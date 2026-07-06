package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.shared.domain.AssignedDevice
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DEFAULT_SUBJECT
import io.bluetrace.shared.domain.DEFAULT_SUBJECT_ID
import io.bluetrace.shared.domain.EnvironmentRepository
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.domain.SceneCatalog
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.SessionConfig
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.domain.SubjectRepository
import io.bluetrace.shared.session.SessionController
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 环境与权限（启动门控 / 蓝牙开关 / 设置A）。 */
class EnvironmentViewModel(private val env: EnvironmentRepository) : ViewModel() {
    val state = env.state
    fun refresh() = env.refresh()
    fun markBlocked(id: io.bluetrace.shared.domain.RequirementId) = env.markPermanentlyDenied(id)
}

data class CollectHomeUiState(
    val currentSubject: Subject? = null,
    val connectedDevices: List<ScannedDevice> = emptyList(),
    val scene: SceneSelection = SceneSelection("Wear", "Wearing"),
    val hardSatisfied: Boolean = true,
    val bluetoothOn: Boolean = true,
    val scanConnectMissing: Boolean = false,
) {
    val connectedCount: Int get() = connectedDevices.size
    val canStart: Boolean get() = currentSubject != null && connectedDevices.isNotEmpty()
}

/** 开始采集结果（含存储预检，§5.2）。 */
enum class StartOutcome { STARTED, NOT_READY, STORAGE_FULL }

/** 采集主界面（采集A）：用户 / 已连设备 / 采集场景 / GNSS + 开始采集。 */
class CollectHomeViewModel(
    subjectRepo: SubjectRepository,
    private val registry: ConnectionRegistry,
    private val controller: SessionController,
    private val env: EnvironmentRepository,
    private val storageMonitor: io.bluetrace.shared.data.StorageMonitor,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val catalog: SceneCatalog,
    private val draft: io.bluetrace.domain.CollectDraft,
) : ViewModel() {

    private data class Base(
        val subject: Subject?,
        val connected: List<ScannedDevice>,
        val scene: SceneSelection,
    )

    val uiState: StateFlow<CollectHomeUiState> =
        combine(
            combine(subjectRepo.subjects, subjectRepo.currentId) { subjects, currentId ->
                // 手动选 Default 伪用户（id 不在 subjects 列表里）→ 直接给 DEFAULT_SUBJECT，勿回退到第一个真人；
                // current 无效（如刚删掉当前用户）→ 显示"未选择"并拦截开始，不静默换成列表第一个真人（防 manifest 写错采集人）
                if (currentId == DEFAULT_SUBJECT_ID) DEFAULT_SUBJECT
                else subjects.firstOrNull { it.id == currentId }
            },
            registry.connected,
            draft.scene,
        ) { subject, connected, scene -> Base(subject, connected, scene) }
            .let { base ->
                combine(base, env.state) { b, envState ->
                    // autoDefaultUserSubs（未佩戴/桌面/口袋…）命中 → 采集对象自动切 Default 用户（无人/纯数据采集，§0.4）
                    val effectiveSubject = if (catalog.isAutoDefaultUser(b.scene.subToken)) DEFAULT_SUBJECT else b.subject
                    CollectHomeUiState(
                        currentSubject = effectiveSubject,
                        // B2A 维护手表（设备控制台专属）不进采集会话：它发的是 B2A 命令/心跳帧，
                        // MockPacketCodec 解不了只会刷 unparseable 告警 + 产空 CSV 脏会话。
                        connectedDevices = b.connected.filterNot {
                            io.bluetrace.shared.s7.B2aDetect.matchesAdvertisement(it)
                        },
                        scene = b.scene,
                        hardSatisfied = envState.hardSatisfied,
                        bluetoothOn = envState.bluetoothOn,
                        scanConnectMissing = envState.status(io.bluetrace.shared.domain.RequirementId.BLE_SCAN_CONNECT) !=
                            io.bluetrace.shared.domain.RequirementStatus.GRANTED,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CollectHomeUiState())

    /** 场景选择页回写本次采集场景（autoDefaultUserSubs 命中 → uiState 自动切 Default 用户）。 */
    fun setScene(scene: SceneSelection) { draft.setScene(scene) }

    fun refreshEnv() = env.refresh()

    /** 授权被永久拒绝时标记 BLOCKED（缺权限弹层的回调用，交互#9）。 */
    fun markBlocked(id: io.bluetrace.shared.domain.RequirementId) = env.markPermanentlyDenied(id)

    /** 启动/进入时是否低空间（< 1GB，§5.2 一次性提示）。 */
    fun isLowSpace(): Boolean = storageMonitor.usableBytes() < io.bluetrace.shared.data.StoragePolicy.LOW_SPACE_HINT

    /** 构建 SessionConfig 并开始采集（§7.3 + 存储预检 §5.2）。 */
    fun startSession(): StartOutcome {
        // 已在采集（双击/重入）→ 不重复 start，调用方直接导航（配合 launchSingleTop 去重）
        if (controller.state.value.status == io.bluetrace.shared.session.RunStatus.COLLECTING) return StartOutcome.STARTED
        val s = uiState.value
        val subject = s.currentSubject ?: return StartOutcome.NOT_READY
        if (s.connectedDevices.isEmpty()) return StartOutcome.NOT_READY
        // 开始前真实存储预检：不足则拦截不允许开始
        if (storageMonitor.usableBytes() < io.bluetrace.shared.data.StoragePolicy.MIN_FREE_TO_START) {
            return StartOutcome.STORAGE_FULL
        }
        val now = clock.nowMs()
        val config = SessionConfig(
            subject = subject,
            scene = s.scene,
            devices = s.connectedDevices.map { AssignedDevice(it.id, it.name, it.address, it.kind, it.profileId) },
            enabledTypes = CollectType.defaults,
            gnssEnabled = false, // GNSS 改为运行C 采集类型勾选（Q1）；开始默认关，进运行页 sheet 勾选 + 按需授权
            startEpochMs = now,
            timezoneId = zone.zoneId(),
            utcOffsetSeconds = zone.offsetSeconds(),
        )
        controller.start(config)
        return StartOutcome.STARTED
    }
}
