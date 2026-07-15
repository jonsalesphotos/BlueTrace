package io.bluetrace.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bluetrace.data.android.RandomAccessPullSink
import io.bluetrace.data.android.UwtpPullStore
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import io.bluetrace.shared.util.epochMsToLocalParts
import io.bluetrace.shared.uwtp.CtrlInfoRsp
import io.bluetrace.shared.uwtp.CtrlRuntimeState
import io.bluetrace.shared.uwtp.ObjectEntry
import io.bluetrace.shared.uwtp.PullOutcome
import io.bluetrace.shared.uwtp.PullProgress
import io.bluetrace.shared.uwtp.Uwtp
import io.bluetrace.shared.uwtp.UwtpClient
import io.bluetrace.shared.uwtp.UwtpCommandException
import io.bluetrace.shared.uwtp.UwtpFilePull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 文件行 = 设备侧条目 + 本地断点(有断点则"续传")。 */
data class UwtpFileRow(
    val entry: ObjectEntry,
    val resumeOffset: Long? = null,
    val savedPath: String? = null,
)

data class UwtpTestUiState(
    val device: ScannedDevice? = null,
    val link: LinkState = LinkState.DISCONNECTED,
    val mtu: Int = 0,
    val info: CtrlInfoRsp? = null,
    val runtime: CtrlRuntimeState? = null,
    val files: List<UwtpFileRow> = emptyList(),
    val busy: String? = null,
    /** 活动拉取进度(null=无活动传输)。 */
    val pull: PullProgress? = null,
    val pulling: Boolean = false,
    val error: String? = null,
) {
    val connected: Boolean get() = device != null && link == LinkState.CONNECTED
    val canReconnect: Boolean get() = device != null && !connected && link != LinkState.CONNECTING && !pulling
    val canDisconnect: Boolean get() = connected && !pulling
}

/**
 * DEBUG「UWTP 传输」VM: S7 离线文件上传(手表->App)首个联调工具。
 *
 * 流程(工作稿 P2 首交付链路): 连接(BLE 层已自动请求 MTU 247) -> CTRL INFO -> CTRL STATE_QUERY
 * -> OBJECT_LIST -> 选文件 TRANSFER_BEGIN -> DATA 组装/累计 ACK -> FINISH 落盘;
 * 断连/中止后凭 App 持久化的 {contiguous + object_token} 续传, OBJECT_CHANGED 自动清断点从 0 重来。
 *
 * 设备黏性/重连交互仿 [OtaTestViewModel]; UWTP 与 B2A 共管道分流在 [UwtpClient] 内完成,
 * 本页不触碰任何 B2A 路径。
 */
class UwtpTestViewModel(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val store: UwtpPullStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UwtpTestUiState())
    val state: StateFlow<UwtpTestUiState> = _state

    /** 执行日志(终端面板; 上限 300 行)。 */
    val logLines = mutableStateListOf<String>()

    private var client: UwtpClient? = null
    private var linkJob: Job? = null
    private var clientLogJob: Job? = null
    private var pullJob: Job? = null
    private var activePull: UwtpFilePull? = null

    init {
        log("UWTP 传输工具已启动(v0.2-draft 客户端)")
        viewModelScope.launch {
            registry.connected.collect { list ->
                if (_state.value.pulling) return@collect
                val target = list.firstOrNull { it.kind != DeviceKind.REFERENCE }
                if (target != null && target.id != _state.value.device?.id) trackDevice(target)
            }
        }
    }

    private fun trackDevice(target: ScannedDevice) {
        linkJob?.cancel()
        clientLogJob?.cancel()
        client?.stop()
        _state.value = UwtpTestUiState(device = target, link = ble.linkState(target.id).value)
        val c = UwtpClient(ble, target.id, viewModelScope, clock)
        client = c
        c.start()
        clientLogJob = viewModelScope.launch {
            c.opLog.collect { line -> log(line.text) }
        }
        linkJob = viewModelScope.launch {
            var prev: LinkState? = null
            ble.linkState(target.id).collect { l ->
                _state.update { it.copy(link = l, mtu = ble.negotiatedMtu(target.id)) }
                if (l == LinkState.CONNECTED && prev != LinkState.CONNECTED) {
                    log("已连接 ${target.name} MTU=${ble.negotiatedMtu(target.id)} (uwtp_payload_max=${ble.negotiatedMtu(target.id) - 8})")
                }
                prev = l
            }
        }
    }

    fun reconnect() {
        val device = _state.value.device ?: return
        if (!_state.value.canReconnect) return
        viewModelScope.launch {
            _state.update { it.copy(link = LinkState.CONNECTING) }
            log("重连中: ${device.name}")
            ble.connect(device)
            if (ble.linkState(device.id).value == LinkState.CONNECTED) registry.add(device)
            else log("重连失败")
        }
    }

    fun disconnect() {
        val device = _state.value.device ?: return
        if (!_state.value.canDisconnect) return
        viewModelScope.launch {
            ble.disconnect(device.id)
            registry.remove(device.id)
            log("已断开: ${device.name}")
        }
    }

    /** 串行动作壳(busy 互斥 + 统一错误展示)。 */
    private fun op(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null || _state.value.pulling) return
        if (!_state.value.connected) {
            log("跳过 $label: 未连接")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = label, error = null) }
            try {
                block()
            } catch (e: UwtpCommandException) {
                log("✗ $label 失败: ${e.message}")
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** CTRL INFO: 固件版本 / registry_rev / RAW Schema 支持集合。 */
    fun queryInfo() = op("INFO") {
        val info = client?.ctrlInfo() ?: return@op
        _state.update { it.copy(info = info) }
        log("INFO: fw=0x${info.fwVersion.toString(16)} registry_rev=${info.registryRev} schemas=${info.rawSchemas.size}")
        info.rawSchemas.forEach { s ->
            log("  schema 0x${s.schemaId.toString(16)} rev${s.schemaRev} profile=0x${s.sourceProfile.toString(16)} ch=0x${s.channelMask.toString(16)}")
        }
    }

    /** CTRL STATE_QUERY(query_flags=1): 三个正交状态。 */
    fun queryState() = op("STATE") {
        val rt = client?.stateQuery() ?: return@op
        _state.update { it.copy(runtime = rt) }
        log("STATE: offline=${rt.offlineState} live=${rt.liveState} transfer=${rt.transferState}")
    }

    /** OBJECT_LIST 全量翻页 + 本地断点标注。 */
    fun listFiles() = op("LIST") {
        val c = client ?: return@op
        val mac = _state.value.device?.address ?: return@op
        val entries = c.listAllObjects()
        val rows = entries.map { e ->
            val resume = store.loadResume(mac, e.fileId)
            val saved = store.finalFile(mac, e.fileId).takeIf { it.exists() }
            UwtpFileRow(e, resumeOffset = resume?.contiguous, savedPath = saved?.absolutePath)
        }
        _state.update { it.copy(files = rows) }
        log("OBJECT_LIST: ${rows.size} 个文件")
        rows.forEach { r ->
            val e = r.entry
            log(
                "  file_id=${e.fileId} size=${e.size} utc=${e.startUtc} flags=0x${e.flags.toString(16)}" +
                    " token=0x${e.objectToken.toULong().toString(16)}" +
                    (r.resumeOffset?.let { " [断点 $it]" } ?: "") + (if (r.savedPath != null) " [已保存]" else ""),
            )
        }
    }

    /**
     * 拉取(或续传)一个文件。断点存在且 token 有效 -> 从 contiguous 续传;
     * BEGIN 返回 OBJECT_CHANGED -> 清断点自动从 0 重来一次(工作稿 D-10)。
     */
    fun pull(fileId: Long) {
        if (_state.value.pulling || _state.value.busy != null) return
        if (!_state.value.connected) {
            log("跳过拉取: 未连接")
            return
        }
        val c = client ?: return
        val mac = _state.value.device?.address ?: return
        val entry = _state.value.files.firstOrNull { it.entry.fileId == fileId }?.entry
        if (entry == null) {
            log("file_id=$fileId 不在列表, 先刷新列表")
            return
        }
        _state.update { it.copy(pulling = true, error = null, pull = null) }
        pullJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var outcome = runPullOnce(c, mac, fileId)
                if (outcome is PullOutcome.Failed && outcome.deviceError == Uwtp.ERR_OBJECT_CHANGED) {
                    log("OBJECT_CHANGED: 对象已变更, 清断点从 0 重拉")
                    store.clearResume(mac, fileId)
                    outcome = runPullOnce(c, mac, fileId)
                }
                when (val o = outcome) {
                    is PullOutcome.Done -> {
                        val f = store.finalize(mac, fileId)
                        val kib = o.avgKibps
                        log("✓ 拉取完成 file_id=$fileId ${o.totalSize}B, 本次 ${o.sessionBytes}B / ${o.elapsedMs}ms, 平均 ${fmt1(kib)} KiB/s")
                        log("已保存: ${f?.absolutePath ?: "(改名失败, 保留 .part)"}")
                        refreshRow(mac, fileId)
                    }
                    is PullOutcome.Failed -> {
                        log("✗ 拉取失败: ${o.reason}${if (o.resumable) " (断点 ${o.contiguous}, 可续传)" else ""}")
                        _state.update { it.copy(error = o.reason) }
                        if (!o.resumable) store.clearResume(mac, fileId)
                        refreshRow(mac, fileId)
                    }
                    is PullOutcome.Aborted -> {
                        log("已中止, 断点 ${o.contiguous}(可续传)")
                        refreshRow(mac, fileId)
                    }
                }
            } finally {
                _state.update { it.copy(pulling = false) }
                activePull = null
            }
        }
    }

    /** 单次 BEGIN->DATA->FINISH; 断点读写与 sink 生命周期都在这一层。 */
    private suspend fun runPullOnce(c: UwtpClient, mac: String, fileId: Long): PullOutcome {
        val resume = store.loadResume(mac, fileId)
        val startOffset = resume?.contiguous ?: 0L
        val token = resume?.objectToken ?: 0L
        val part = store.partFile(mac, fileId)
        if (resume == null) part.delete() // 无断点: 从头
        val sink = RandomAccessPullSink(part)
        if (resume != null) sink.truncate(startOffset) // 断点权威值在 json, part 多余部分裁掉
        var beginToken = token
        var beginTotal = resume?.totalSize ?: 0L
        val pull = UwtpFilePull(
            port = c,
            sink = sink,
            fileId = fileId,
            startOffset = startOffset,
            objectToken = token,
            objectSize = resume?.totalSize ?: 0L,
            requestCrcMode = 0, // 阶段一固件只回 accepted_crc_mode=0
            requestAckEveryN = 32, // Binding 暂定
            onBegin = { rsp ->
                beginToken = rsp.objectToken
                beginTotal = rsp.totalSize
                log("BEGIN ok: transfer_id=${rsp.transferId} total=${rsp.totalSize} ack_n=${rsp.acceptedAckEveryN} max_data=${rsp.maxDataLen} crc=${rsp.acceptedCrcMode}")
            },
            onAckSent = { contiguous ->
                store.saveResume(mac, UwtpPullStore.Resume(fileId, beginToken, beginTotal, contiguous))
            },
        )
        activePull = pull
        val progressJob = viewModelScope.launch {
            pull.progress.collect { p -> _state.update { it.copy(pull = p) } }
        }
        c.attachTransfer(pull.inbound)
        return try {
            if (startOffset > 0) log("续传 file_id=$fileId from $startOffset (token=0x${token.toULong().toString(16)})")
            else log("开始拉取 file_id=$fileId")
            pull.run()
        } finally {
            c.detachTransfer(pull.inbound)
            progressJob.cancel()
            sink.close()
        }
    }

    /** 中止当前拉取(发 TRANSFER_ABORT, 保留断点)。 */
    fun abortPull() {
        activePull?.requestAbort() ?: log("无活动传输")
    }

    /** 清某文件断点(手动重来)。 */
    fun clearResume(fileId: Long) {
        val mac = _state.value.device?.address ?: return
        if (_state.value.pulling) return
        store.clearResume(mac, fileId)
        log("已清断点 file_id=$fileId")
        refreshRow(mac, fileId)
    }

    private fun refreshRow(mac: String, fileId: Long) {
        _state.update { st ->
            st.copy(
                files = st.files.map { row ->
                    if (row.entry.fileId != fileId) row
                    else row.copy(
                        resumeOffset = store.loadResume(mac, fileId)?.contiguous,
                        savedPath = store.finalFile(mac, fileId).takeIf { it.exists() }?.absolutePath,
                    )
                },
            )
        }
    }

    private fun fmt1(v: Double): String = ((v * 10).toLong() / 10.0).toString()

    fun log(text: String) {
        val ts = epochMsToLocalParts(clock.nowMs(), zone.offsetSeconds())
        val stamp = "${pad2(ts.hour)}:${pad2(ts.minute)}:${pad2(ts.second)}"
        viewModelScope.launch(Dispatchers.Main.immediate) {
            logLines.add("$stamp $text")
            if (logLines.size > 300) logLines.removeRange(0, logLines.size - 300)
        }
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else "$v"

    override fun onCleared() {
        activePull?.requestAbort()
        client?.stop()
    }
}
