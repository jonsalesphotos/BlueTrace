package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.DeviceLimits
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanStatus { IDLE, SCANNING, STOPPED }

data class DeviceRowUi(
    val device: ScannedDevice,
    val link: LinkState,
    val disabled: Boolean, // 达上限且未连 → 不可点
    /** 已识别目标: 有控制面的手表 或 参考心率带; 用于排序置顶与打标.  */
    val recognized: Boolean = false,
    /** 有控制面 → 可作维护控制台目标(区别于参考带); 沿用字段名 b2a(UI 徽章).  */
    val b2a: Boolean = false,
)

data class DeviceScanUiState(
    val rows: List<DeviceRowUi> = emptyList(),
    val connectedCount: Int = 0,
    val dutCount: Int = 0,
    val referenceCount: Int = 0,
    val scanning: Boolean = false,
    val rssiThreshold: Int = ScanDefaults.RSSI_THRESHOLD,
    val query: String = "",
    val showEmpty: Boolean = false,
    val atDutLimit: Boolean = false,
)

/** 设备连接(设备A/B/C): 扫描 + 扁平列表 + 限额 + RSSI 过滤 + 60s 自停(§5.3).  */
class DeviceScanViewModel(
    private val bleClient: BleClient,
    private val registry: ConnectionRegistry,
    private val catalog: DeviceProfileCatalog,
) : ViewModel() {

    private val _results = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    private val _status = MutableStateFlow(ScanStatus.IDLE)
    private val _rssi = MutableStateFlow(ScanDefaults.RSSI_THRESHOLD)
    private val _query = MutableStateFlow("")

    private var scanJob: Job? = null
    private var timerJob: Job? = null
    private val observed = mutableSetOf<String>()

    private data class Raw(
        val results: List<ScannedDevice>,
        val links: Map<String, LinkState>,
        val rssi: Int,
        val query: String,
    )

    val uiState: StateFlow<DeviceScanUiState> =
        combine(
            combine(_results, _links, _rssi, _query) { r, l, rssi, q -> Raw(r, l, rssi, q) },
            registry.connected,
            _status,
        ) { raw, connected, status ->
            val connectedIds = connected.map { it.id }.toSet()
            val dutCount = connected.count { it.kind == DeviceKind.DUT }
            val refCount = connected.count { it.kind == DeviceKind.REFERENCE }
            val resultIds = raw.results.mapTo(HashSet()) { it.id }
            // 已连接设备常在连接后**停止广播** → 从扫描结果消失; 并回来, 保证始终可见并置顶
            val merged = raw.results + connected.filter { it.id !in resultIds }
            val rows = merged
                // 隐藏无名设备(命名设备才展示)—— 已连接设备**豁免**(始终显示)
                .filter { it.id in connectedIds || (it.name.isNotBlank() && it.name != "(unnamed)") }
                // RSSI 过滤 —— 已连接设备豁免
                .filter { it.id in connectedIds || it.rssi >= raw.rssi }
                .filter { raw.query.isBlank() || it.name.contains(raw.query, true) || it.address.contains(raw.query, true) }
                .map { dev ->
                    val link = when {
                        dev.id in connectedIds -> LinkState.CONNECTED
                        else -> raw.links[dev.id] ?: LinkState.DISCONNECTED
                    }
                    val atLimit = when (dev.kind) {
                        DeviceKind.DUT -> dutCount >= DeviceLimits.MAX_DUT
                        DeviceKind.REFERENCE -> refCount >= DeviceLimits.MAX_REFERENCE
                    }
                    // 识别: 有控制面的手表 或 参考心率带 → 已识别目标; 其余(随机 BLE)沉底.
                    // 控制面判定归 Catalog(去 S7 硬编码): S7 有控制面, HRS/随机设备无.
                    val b2a = catalog.identify(dev)?.controlPlane != null
                    val recognized = b2a || dev.kind == DeviceKind.REFERENCE
                    DeviceRowUi(dev, link, disabled = atLimit && dev.id !in connectedIds, recognized = recognized, b2a = b2a)
                }
                // 排序: 已连接置顶 → 已识别(B2A 手表 + 参考带)在前, 未识别下沉 → 信号强度(RSSI 降序)
                .sortedWith(
                    compareByDescending<DeviceRowUi> { it.link == LinkState.CONNECTED }
                        .thenByDescending { it.recognized }
                        .thenByDescending { it.device.rssi },
                )
            DeviceScanUiState(
                rows = rows,
                connectedCount = connected.size,
                dutCount = dutCount,
                referenceCount = refCount,
                scanning = status == ScanStatus.SCANNING,
                rssiThreshold = raw.rssi,
                query = raw.query,
                showEmpty = status == ScanStatus.STOPPED && rows.isEmpty(),
                atDutLimit = dutCount >= DeviceLimits.MAX_DUT,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceScanUiState())

    fun startScan() {
        if (_status.value == ScanStatus.SCANNING) return
        _status.value = ScanStatus.SCANNING
        _results.value = emptyList()
        timerJob = viewModelScope.launch {
            delay(60_000) // 扫描 60s 自停（§5.3）
            stopScan()
        }
        scanJob = viewModelScope.launch {
            // sample(1s): 扫描回调很密(RSSI 每帧变), 节流到最多 1 次/秒,
            // 让列表 ~1 秒才按信号重排一次——防跳动, 可稳定点选(与控制台页一致).
            bleClient.scan().sample(1000).collect { devices ->
                // 扫描去识别化: client 只上报原始广播(profileId=null/kind=DUT), 识别在此投影层
                // 经 Catalog 统一打标(Mock roster 预置身份由 annotate 守卫保留).
                val annotated = devices.map { catalog.annotate(it) }
                _results.value = annotated
                annotated.forEach { observeLink(it.id) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        timerJob?.cancel(); timerJob = null
        if (_status.value == ScanStatus.SCANNING) _status.value = ScanStatus.STOPPED
    }

    fun setRssiThreshold(value: Int) { _rssi.value = value }
    fun setQuery(value: String) { _query.value = value }

    /** 单击: 未连→连接(限额内); 已连→断开.  */
    fun toggleConnect(device: ScannedDevice) {
        if (registry.isConnected(device.id)) {
            viewModelScope.launch {
                bleClient.disconnect(device.id)
                registry.remove(device.id)
            }
        } else {
            if (!registry.canConnect(device.kind)) return
            observeLink(device.id)
            viewModelScope.launch {
                // 先连后入册: 真实 BLE 连接可失败/超时, 失败不得留下幽灵"已连接"条目.
                // 识别到档案则走其 gattSpec 声明式通道(新协议只认 spec, 探测只认 B2A/HRS);
                // 未识别设备保留探测兜底(spec=null).
                bleClient.connect(device, catalog.identify(device)?.gattSpec)
                if (bleClient.linkState(device.id).value == LinkState.CONNECTED) registry.add(device)
            }
        }
    }

    private fun observeLink(id: String) {
        if (id in observed) return
        observed.add(id)
        viewModelScope.launch {
            bleClient.linkState(id).collect { state -> _links.update { it + (id to state) } }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel(); timerJob?.cancel()
    }
}
