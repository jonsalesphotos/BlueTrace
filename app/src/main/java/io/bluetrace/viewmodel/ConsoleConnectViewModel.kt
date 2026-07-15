package io.bluetrace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 连接页设备行：是否为控制台可维护的设备（有控制面才可连接；不支持则不可连接）。 */
data class ConsoleDeviceRow(
    val device: ScannedDevice,
    val link: LinkState,
    val supported: Boolean,
    val busy: Boolean,
)

data class ConsoleConnectUiState(
    val rows: List<ConsoleDeviceRow> = emptyList(),
    val scanning: Boolean = false,
    val query: String = "",
    val rssiThreshold: Int = ScanDefaults.RSSI_THRESHOLD,
)

/**
 * 控制台内置连接页 VM。
 * - 只对 **有控制面的设备**运行连接（识别归 [DeviceProfileCatalog]，不支持设备展示为不可连）；
 * - **无名设备不显示**；已连接设备置顶；顺序稳定（不按 RSSI 实时重排，避免跳动难点选）；
 * - 单点即连/断该设备，**不自动断开其它设备**（多设备由控制台选择控制哪台）；
 * - 名称 / 信号强度过滤。
 */
class ConsoleConnectViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val catalog: DeviceProfileCatalog,
) : ViewModel() {

    private val _results = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _links = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    private val _scanning = MutableStateFlow(false)
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    private val _query = MutableStateFlow("")
    private val _rssi = MutableStateFlow(ScanDefaults.RSSI_THRESHOLD)
    private val observed = mutableSetOf<String>()
    private var scanJob: Job? = null

    private data class Ctl(val scanning: Boolean, val busy: Set<String>, val query: String, val rssi: Int)

    val uiState: StateFlow<ConsoleConnectUiState> =
        combine(
            _results,
            _links,
            registry.connected,
            combine(_scanning, _busy, _query, _rssi) { scanning, busy, q, rssi -> Ctl(scanning, busy, q, rssi) },
        ) { results, links, connected, ctl ->
            val scanning = ctl.scanning
            val busy = ctl.busy
            val query = ctl.query
            val rssi = ctl.rssi
            val connectedIds = connected.map { it.id }.toSet()
            val resultIds = results.mapTo(HashSet()) { it.id }
            // 已连接手表常在连接后**停止广播** → 从扫描结果消失；把已连设备并回来，保证始终可见并置顶
            val merged = results + connected.filter { it.id !in resultIds && it.kind != DeviceKind.REFERENCE }

            val rows = merged.asSequence()
                // 无名/参考过滤 —— 已连接设备**豁免**（始终显示）
                .filter { it.id in connectedIds || (it.name.isNotBlank() && it.name != "(unnamed)" && it.kind != DeviceKind.REFERENCE) }
                // RSSI 过滤 —— 已连接设备豁免（信号弱也不隐藏）
                .filter { it.id in connectedIds || it.rssi >= rssi }
                .filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
                .map { d ->
                    val link = if (d.id in connectedIds) LinkState.CONNECTED else links[d.id] ?: LinkState.DISCONNECTED
                    ConsoleDeviceRow(
                        device = d,
                        link = link,
                        // 控制台可维护 = 识别到的档案有控制面(S7 有; 参考带/纯数据设备无)——去 S7 硬编码.
                        supported = catalog.identify(d)?.controlPlane != null,
                        busy = d.id in busy,
                    )
                }
                // 排序：已连接置顶 → 支持的在上、不支持下沉 → 信号强度（RSSI 降序）。
                // 列表更新经 sample 节流（见 startScan），避免每帧重排跳动、可稳定点选。
                .sortedWith(
                    compareByDescending<ConsoleDeviceRow> { it.link == LinkState.CONNECTED }
                        .thenByDescending { it.supported }
                        .thenByDescending { it.device.rssi },
                )
                .toList()

            ConsoleConnectUiState(rows = rows, scanning = scanning, query = query, rssiThreshold = rssi)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConsoleConnectUiState())

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _results.value = emptyList()
        scanJob = viewModelScope.launch {
            // sample(1s)：扫描回调很密（RSSI 每帧变），节流到最多 1 次/秒，
            // 让列表 ~1 秒才按信号重排一次——既按信号强度排序，又不至跳动到无法点选。
            ble.scan().sample(1000).collect { devices ->
                // 扫描去识别化: 识别在此投影层经 Catalog 统一打标(supported 判定/参考带过滤据此不变).
                val annotated = devices.map { catalog.annotate(it) }
                _results.value = annotated
                annotated.forEach { observeLink(it.id) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        _scanning.value = false
    }

    fun setQuery(v: String) { _query.value = v }
    fun setRssiThreshold(v: Int) { _rssi.value = v }

    /**
     * 单点即连/断该设备。**不自动断开其它设备**（除非明确点已连设备断开）；
     * 不支持的设备直接忽略（不运行连接）。
     */
    fun toggleConnect(device: ScannedDevice) {
        if (device.id in _busy.value) return
        val connectedNow = registry.isConnected(device.id)
        // 未连接且无控制面(控制台不可维护) → 不运行连接
        if (!connectedNow && catalog.identify(device)?.controlPlane == null) return
        _busy.update { it + device.id }
        viewModelScope.launch {
            try {
                if (connectedNow) {
                    ble.disconnect(device.id)
                    registry.remove(device.id)
                } else {
                    observeLink(device.id)
                    // 识别到档案则走其 gattSpec 声明式通道（新协议只认 spec，探测只认 B2A/HRS）；
                    // 未识别设备保留探测兜底（spec=null）。
                    ble.connect(device, catalog.identify(device)?.gattSpec)
                    if (ble.linkState(device.id).value == LinkState.CONNECTED) registry.add(device)
                }
            } finally {
                _busy.update { it - device.id }
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
