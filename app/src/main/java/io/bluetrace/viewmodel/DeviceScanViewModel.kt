package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanStatus { IDLE, SCANNING, STOPPED }

data class DeviceRowUi(
    val device: ScannedDevice,
    val link: LinkState,
    val disabled: Boolean, // 达上限且未连 → 不可点
)

data class DeviceScanUiState(
    val rows: List<DeviceRowUi> = emptyList(),
    val connectedCount: Int = 0,
    val dutCount: Int = 0,
    val referenceCount: Int = 0,
    val scanning: Boolean = false,
    val rssiThreshold: Int = -80,
    val query: String = "",
    val showEmpty: Boolean = false,
    val atDutLimit: Boolean = false,
)

/** 设备连接（设备A/B/C）：扫描 + 扁平列表 + 限额 + RSSI 过滤 + 60s 自停（§5.3）。 */
class DeviceScanViewModel(
    private val bleClient: BleClient,
    private val registry: ConnectionRegistry,
) : ViewModel() {

    private val _results = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    private val _status = MutableStateFlow(ScanStatus.IDLE)
    private val _rssi = MutableStateFlow(-80)
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
            val rows = raw.results
                .filter { it.rssi >= raw.rssi }
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
                    DeviceRowUi(dev, link, disabled = atLimit && dev.id !in connectedIds)
                }
                .sortedWith(compareByDescending<DeviceRowUi> { it.link == LinkState.CONNECTED }.thenByDescending { it.device.rssi })
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
            bleClient.scan().collect { devices ->
                _results.value = devices
                devices.forEach { observeLink(it.id) }
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

    /** 单击：未连→连接（限额内）；已连→断开。 */
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
                // 先连后入册：真实 BLE 连接可失败/超时，失败不得留下幽灵「已连接」条目
                bleClient.connect(device)
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
