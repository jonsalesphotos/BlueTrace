package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.domain.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConsoleConnectUiState(
    val rows: List<DeviceRowUi> = emptyList(),
    val scanning: Boolean = false,
    /** 正在连接/断开的设备 id（该行按钮转圈、其余禁点）。 */
    val busyId: String? = null,
)

/**
 * 控制台内置连接页 VM：**非参考设备限连 1 台**——连接新设备前自动断开既有非参考设备；
 * 参考设备（HRS 心率带）不在此页展示、也不受此限（属采集流程）。
 */
class ConsoleConnectViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
) : ViewModel() {

    private val _results = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    private val _scanning = MutableStateFlow(false)
    private val _busy = MutableStateFlow<String?>(null)
    private val observed = mutableSetOf<String>()
    private var scanJob: Job? = null

    val uiState: StateFlow<ConsoleConnectUiState> =
        combine(_results, _links, registry.connected, _scanning, _busy) { results, links, connected, scanning, busy ->
            val connectedIds = connected.map { it.id }.toSet()
            ConsoleConnectUiState(
                rows = results
                    .filter { it.kind != DeviceKind.REFERENCE } // 参考设备不进控制台连接页
                    .map { d ->
                        val link = if (d.id in connectedIds) LinkState.CONNECTED
                        else links[d.id] ?: LinkState.DISCONNECTED
                        DeviceRowUi(d, link, disabled = busy != null && busy != d.id)
                    }
                    .sortedWith(
                        compareByDescending<DeviceRowUi> { it.link == LinkState.CONNECTED }
                            .thenByDescending { it.device.rssi },
                    ),
                scanning = scanning,
                busyId = busy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConsoleConnectUiState())

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _results.value = emptyList()
        scanJob = viewModelScope.launch {
            ble.scan().collect { devices ->
                _results.value = devices
                devices.forEach { observeLink(it.id) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        _scanning.value = false
    }

    /** 单击：已连→断开；未连→先断开全部既有非参考设备（单连语义）再连接，成功才入册。 */
    fun toggleConnect(device: ScannedDevice) {
        if (_busy.value != null) return
        _busy.value = device.id
        viewModelScope.launch {
            try {
                if (registry.isConnected(device.id)) {
                    ble.disconnect(device.id)
                    registry.remove(device.id)
                } else {
                    registry.connected.value
                        .filter { it.kind != DeviceKind.REFERENCE }
                        .forEach { old ->
                            ble.disconnect(old.id)
                            registry.remove(old.id)
                        }
                    observeLink(device.id)
                    ble.connect(device)
                    if (ble.linkState(device.id).value == LinkState.CONNECTED) registry.add(device)
                }
            } finally {
                _busy.value = null
            }
        }
    }

    private fun observeLink(id: String) {
        if (id in observed) return
        observed.add(id)
        viewModelScope.launch {
            ble.linkState(id).collect { state -> _links.update { it + (id to state) } }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
