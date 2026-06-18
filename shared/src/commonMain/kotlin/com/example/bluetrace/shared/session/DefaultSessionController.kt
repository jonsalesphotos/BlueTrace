package com.example.bluetrace.shared.session

import com.example.bluetrace.shared.ble.BleClient
import com.example.bluetrace.shared.ble.BleNotification
import com.example.bluetrace.shared.ble.MockBleClient
import com.example.bluetrace.shared.data.ManifestDevice
import com.example.bluetrace.shared.data.ManifestQuality
import com.example.bluetrace.shared.data.ManifestSampling
import com.example.bluetrace.shared.data.ManifestSubject
import com.example.bluetrace.shared.data.SessionLayout
import com.example.bluetrace.shared.data.SessionManifest
import com.example.bluetrace.shared.data.SessionRecorder
import com.example.bluetrace.shared.data.SessionStore
import com.example.bluetrace.shared.data.sessionFolderName
import com.example.bluetrace.shared.domain.DecodedStream
import com.example.bluetrace.shared.domain.DeviceKind
import com.example.bluetrace.shared.domain.LinkState
import com.example.bluetrace.shared.domain.QualitySummary
import com.example.bluetrace.shared.domain.SessionConfig
import com.example.bluetrace.shared.domain.SessionFile
import com.example.bluetrace.shared.domain.SessionSummary
import com.example.bluetrace.shared.domain.StopReason
import com.example.bluetrace.shared.protocol.SampleDecoder
import com.example.bluetrace.shared.util.EpochClock
import com.example.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path

/**
 * 真实采集编排（v1 喂 [MockBleClient]）。所有 Notify / 连接事件 / 计时 tick / 标签 经单一 [events] channel
 * 由一个消费协程串行处理 → 落盘与状态变更天然无竞态、文件无并发写。
 */
class DefaultSessionController(
    private val bleClient: BleClient,
    private val decoder: SampleDecoder,
    private val fileSystem: FileSystem,
    private val sessionsRoot: Path,
    private val sessionStore: SessionStore,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val appVersion: String = "1.0",
) : SessionController {

    private val _state = MutableStateFlow(RunState())
    override val state: StateFlow<RunState> = _state

    private val _logLines = MutableSharedFlow<RunLogLine>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val logLines: SharedFlow<RunLogLine> = _logLines

    private val _finished = MutableStateFlow<SessionSummary?>(null)
    override val finished: StateFlow<SessionSummary?> = _finished

    override var activeConfig: SessionConfig? = null
        private set

    // ---- 运行期可变状态（只在消费协程里改）----
    private var status = RunStatus.READY
    private var startEpochMs = 0L
    private var endEpochMs = 0L
    private var datasCount = 0L
    private var reconnectCount = 0
    private var disconnectTotalMs = 0L
    private var labelIntervalOpen = false
    private var displayPaused = false
    private var stopReason: StopReason? = null
    private val deviceStates = LinkedHashMap<String, RunDeviceState>()
    private val disconnectStartMs = HashMap<String, Long>()
    private val activeSensors = HashMap<String, LinkedHashSet<String>>()
    private var enabledStreamNames: Set<String> = emptySet()

    private var events: Channel<RunEvent>? = null
    private var runScope: CoroutineScope? = null
    private var recorder: SessionRecorder? = null
    private var layout: SessionLayout? = null

    override fun start(config: SessionConfig) {
        if (status == RunStatus.COLLECTING) return
        resetVars()
        activeConfig = config
        status = RunStatus.COLLECTING
        startEpochMs = config.startEpochMs
        _finished.value = null
        enabledStreamNames = config.enabledTypes.map { DecodedStream.ofCollectType(it).csvName }.toSet()

        val lay = SessionLayout(sessionsRoot / sessionFolderName(config))
        layout = lay
        recorder = SessionRecorder(fileSystem, lay, config.enabledTypes, config.gnssEnabled)
        // 开始即写 manifest 关键信息（会话自描述、被杀也能解，§6.2）
        sessionStore.writeManifest(lay, buildManifest(config, end = null, reason = null, files = emptyList(), quality = QualitySummary()))

        config.devices.forEach { d ->
            deviceStates[d.deviceId] = RunDeviceState(d.deviceId, d.name, d.kind, LinkState.CONNECTED)
            activeSensors[d.deviceId] = LinkedHashSet()
        }
        diagnostics.add(LogLevel.INFO, "session", "start ${lay.sessionDir.name}")
        pushState()

        val ch = Channel<RunEvent>(Channel.BUFFERED)
        events = ch
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        runScope = rs

        rs.launch { consume(ch) }
        config.devices.forEach { d ->
            rs.launch { bleClient.notifications(d.deviceId).collect { ch.send(RunEvent.Notif(d.kind, it)) } }
            rs.launch { bleClient.linkState(d.deviceId).collect { ch.send(RunEvent.Link(d.deviceId, d.kind, it)) } }
        }
        rs.launch { while (isActive) { delay(TICK_MS); ch.trySend(RunEvent.Tick) } }
        if (config.gnssEnabled) rs.launch { gpsLoop(ch) }
    }

    private suspend fun consume(ch: Channel<RunEvent>) {
        for (e in ch) {
            when (e) {
                is RunEvent.Notif -> handleNotif(e)
                is RunEvent.Link -> handleLink(e)
                RunEvent.Tick -> pushState()
                is RunEvent.Pin -> handlePin(e.text)
                is RunEvent.Interval -> handleInterval(e.text)
                is RunEvent.Pause -> { displayPaused = e.paused; pushState() }
                is RunEvent.Gps -> recorder?.recordGps(e.epochMs, e.lat, e.lon, e.alt, e.speed, e.accuracy)
                RunEvent.StorageFull -> { finishInternal(StopReason.STORAGE_FULL); runScope?.cancel(); return }
                is RunEvent.Stop -> { val s = finishInternal(e.reason); e.ack.complete(s); runScope?.cancel(); return }
            }
        }
    }

    private fun handleNotif(e: RunEvent.Notif) {
        val rec = recorder ?: return
        val n: BleNotification = e.notification
        val hex = rec.recordRaw(e.kind, n.receivedAtMs, n.rawBytes)
        datasCount++
        val samples = decoder.decode(e.kind, n)
        if (samples.isEmpty()) {
            diagnostics.add(LogLevel.WARN, "decode", "unparseable packet from ${n.deviceId} (dropped)")
        }
        rec.recordSamples(samples)
        val sensors = activeSensors.getOrPut(n.deviceId) { LinkedHashSet() }
        for (s in samples) {
            if (s.stream == DecodedStream.HR) {
                deviceStates[n.deviceId]?.let { deviceStates[n.deviceId] = it.copy(hr = s.channels.firstOrNull()) }
            } else if (s.stream.csvName in enabledStreamNames) {
                sensors.add(s.stream.csvName)
            }
        }
        val rel = formatRelativeCenti(n.receivedAtMs - startEpochMs)
        _logLines.tryEmit(RunLogLine(RunLogLine.Kind.HEX, "[$rel]", hex))
    }

    private fun handleLink(e: RunEvent.Link) {
        val prev = deviceStates[e.deviceId]?.link
        val now = e.state
        if (prev == LinkState.CONNECTED && now == LinkState.RECONNECTING) {
            reconnectCount++
            disconnectStartMs[e.deviceId] = clock.nowMs()
            diagnostics.add(LogLevel.WARN, "ble", "${e.deviceId} disconnected → reconnecting")
        }
        if (prev == LinkState.RECONNECTING && now == LinkState.CONNECTED) {
            disconnectStartMs.remove(e.deviceId)?.let { disconnectTotalMs += (clock.nowMs() - it).coerceAtLeast(0) }
            diagnostics.add(LogLevel.INFO, "ble", "${e.deviceId} reconnected → resume")
        }
        deviceStates[e.deviceId] = deviceStates[e.deviceId]?.copy(link = now)
            ?: RunDeviceState(e.deviceId, e.deviceId, e.kind, now)
        pushState()
    }

    private fun handlePin(text: String) {
        val now = clock.nowMs()
        recorder?.recordLabel(now, "PIN", text)
        val rel = formatRelativeCenti(now - startEpochMs)
        _logLines.tryEmit(RunLogLine(RunLogLine.Kind.LABEL, "[$rel]", "⏺ LABEL PIN · \"$text\""))
    }

    private fun handleInterval(text: String) {
        val now = clock.nowMs()
        val rel = formatRelativeCenti(now - startEpochMs)
        if (!labelIntervalOpen) {
            labelIntervalOpen = true
            recorder?.recordLabel(now, "START", text)
            _logLines.tryEmit(RunLogLine(RunLogLine.Kind.LABEL, "[$rel]", "▶ LABEL START · \"$text\""))
        } else {
            labelIntervalOpen = false
            recorder?.recordLabel(now, "STOP", text)
            _logLines.tryEmit(RunLogLine(RunLogLine.Kind.LABEL, "[$rel]", "⏹ LABEL STOP · \"$text\""))
        }
        pushState()
    }

    private suspend fun gpsLoop(ch: Channel<RunEvent>) {
        var lat = 31.2304; var lon = 121.4737 // 上海，演示漂移
        while (true) {
            delay(1000) // cancellable：runScope 取消即退出
            lat += 0.00001; lon += 0.00001
            ch.trySend(RunEvent.Gps(clock.nowMs(), lat, lon, 12.0, 1.2, 5.0))
        }
    }

    private fun finishInternal(reason: StopReason): SessionSummary {
        status = RunStatus.STOPPED
        stopReason = reason
        endEpochMs = clock.nowMs()
        // 收尾未闭合的断联区间
        disconnectStartMs.values.forEach { disconnectTotalMs += (endEpochMs - it).coerceAtLeast(0) }
        disconnectStartMs.clear()

        val cfg = activeConfig!!
        val lay = layout!!
        val files = recorder?.finalizeFiles() ?: emptyList()
        val quality = QualitySummary(reconnectCount, disconnectTotalMs, 0)
        sessionStore.writeManifest(lay, buildManifest(cfg, endEpochMs, reason, files, quality))
        val summary = buildSummary(cfg, lay, files, quality, reason)
        _finished.value = summary
        diagnostics.add(LogLevel.INFO, "session", "stopped reason=${reason.id} lines=$datasCount")
        pushState()
        return summary
    }

    override fun setDisplayPaused(paused: Boolean) { events?.trySend(RunEvent.Pause(paused)) }
    override fun pin(text: String) { events?.trySend(RunEvent.Pin(text)) }
    override fun toggleIntervalLabel(text: String) { events?.trySend(RunEvent.Interval(text)) }
    override fun simulateStorageFull() { events?.trySend(RunEvent.StorageFull) }

    override fun injectDisconnect() {
        val target = activeConfig?.devices?.firstOrNull { it.kind == DeviceKind.DUT }
            ?: activeConfig?.devices?.firstOrNull() ?: return
        (bleClient as? MockBleClient)?.injectDisconnect(target.deviceId)
    }

    override suspend fun stop(reason: StopReason): SessionSummary {
        if (status != RunStatus.COLLECTING) {
            return _finished.value ?: error("stop() called with no active session")
        }
        val ack = CompletableDeferred<SessionSummary>()
        val ch = events ?: return _finished.value ?: error("no event channel")
        ch.send(RunEvent.Stop(reason, ack))
        return ack.await()
    }

    override fun reset() {
        if (status == RunStatus.COLLECTING) return
        resetVars()
        _finished.value = null
        _state.value = RunState()
        activeConfig = null
    }

    private fun resetVars() {
        status = RunStatus.READY
        startEpochMs = 0; endEpochMs = 0; datasCount = 0
        reconnectCount = 0; disconnectTotalMs = 0
        labelIntervalOpen = false; displayPaused = false; stopReason = null
        deviceStates.clear(); disconnectStartMs.clear(); activeSensors.clear()
    }

    private fun pushState() {
        val cfg = activeConfig
        val elapsed = when (status) {
            RunStatus.COLLECTING -> (clock.nowMs() - startEpochMs).coerceAtLeast(0)
            RunStatus.STOPPED -> (endEpochMs - startEpochMs).coerceAtLeast(0)
            RunStatus.READY -> 0
        }
        val devices = cfg?.devices?.map { d ->
            val base = deviceStates[d.deviceId] ?: RunDeviceState(d.deviceId, d.name, d.kind, LinkState.CONNECTED)
            base.copy(activeSensors = activeSensors[d.deviceId]?.toList().orEmpty())
        } ?: emptyList()
        _state.value = RunState(
            status = status,
            startEpochMs = startEpochMs,
            elapsedMs = elapsed,
            datasCount = datasCount,
            devices = devices,
            labelIntervalOpen = labelIntervalOpen,
            displayPaused = displayPaused,
            stopReason = stopReason,
            reconnectCount = reconnectCount,
        )
    }

    private fun buildManifest(
        config: SessionConfig,
        end: Long?,
        reason: StopReason?,
        files: List<SessionFile>,
        quality: QualitySummary,
    ): SessionManifest {
        val folder = sessionFolderName(config)
        return SessionManifest(
            sessionId = folder,
            folderName = folder,
            startEpochMs = config.startEpochMs,
            endEpochMs = end,
            timezone = config.timezoneId,
            utcOffsetSeconds = config.utcOffsetSeconds,
            subject = ManifestSubject(
                config.subject.alias, config.subject.sex, config.subject.birth,
                config.subject.heightCm, config.subject.weightKg,
            ),
            mode = config.mode,
            sampling = ManifestSampling(config.enabledTypes.map { it.id }),
            devices = config.devices.map { d ->
                ManifestDevice(
                    role = d.kind.name,
                    address = d.address,
                    name = d.name,
                    profileId = d.profileId,
                    csvFiles = files.filter { it.category.name.contains("CSV") }.map { it.relativePath },
                )
            },
            gnssEnabled = config.gnssEnabled,
            appVersion = appVersion,
            stopReason = reason?.id,
            quality = ManifestQuality(quality.reconnectCount, quality.disconnectTotalMs, quality.droppedPackets),
            files = files.map { com.example.bluetrace.shared.data.ManifestFile(it.relativePath, it.category.name, it.lineCount, it.sizeBytes) },
        )
    }

    private fun buildSummary(
        config: SessionConfig,
        lay: SessionLayout,
        files: List<SessionFile>,
        quality: QualitySummary,
        reason: StopReason,
    ): SessionSummary = SessionSummary(
        sessionId = lay.sessionDir.name,
        folderName = lay.sessionDir.name,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        subjectAlias = config.subject.alias,
        mode = config.mode,
        deviceCount = config.devices.size,
        sensorCount = config.enabledTypes.size,
        totalLines = files.sumOf { it.lineCount },
        totalBytes = files.sumOf { it.sizeBytes },
        files = files,
        quality = quality,
        stopReason = reason,
        enabledTypes = config.enabledTypes,
        gnssEnabled = config.gnssEnabled,
    )

    companion object {
        const val TICK_MS = 200L
    }
}

/** 消费协程的串行事件。 */
private sealed interface RunEvent {
    data class Notif(val kind: DeviceKind, val notification: BleNotification) : RunEvent
    data class Link(val deviceId: String, val kind: DeviceKind, val state: LinkState) : RunEvent
    data object Tick : RunEvent
    data class Pin(val text: String) : RunEvent
    data class Interval(val text: String) : RunEvent
    data class Pause(val paused: Boolean) : RunEvent
    data class Gps(
        val epochMs: Long, val lat: Double, val lon: Double,
        val alt: Double, val speed: Double, val accuracy: Double,
    ) : RunEvent
    data object StorageFull : RunEvent
    data class Stop(val reason: StopReason, val ack: CompletableDeferred<SessionSummary>) : RunEvent
}
