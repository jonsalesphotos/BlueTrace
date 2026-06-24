package io.bluetrace.shared.session

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.MockBleClient
import io.bluetrace.shared.data.ManifestDevice
import io.bluetrace.shared.data.ManifestQuality
import io.bluetrace.shared.data.ManifestSampling
import io.bluetrace.shared.data.ManifestSubject
import io.bluetrace.shared.data.SessionLayout
import io.bluetrace.shared.data.SessionManifest
import io.bluetrace.shared.data.SessionRecorder
import io.bluetrace.shared.data.SessionStore
import io.bluetrace.shared.data.sessionFolderName
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.QualitySummary
import io.bluetrace.shared.domain.SessionConfig
import io.bluetrace.shared.domain.SessionFile
import io.bluetrace.shared.domain.SessionSummary
import io.bluetrace.shared.domain.StopReason
import io.bluetrace.shared.protocol.SampleDecoder
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
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
    private val storageMonitor: io.bluetrace.shared.data.StorageMonitor = io.bluetrace.shared.data.StorageMonitor { Long.MAX_VALUE },
    private val gnssSource: io.bluetrace.shared.data.GnssSource = io.bluetrace.shared.data.GnssSource.None,
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
    private var gnssJob: Job? = null

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
        // 本机 GNSS 一路（F-GPS-1）：勾选 GNSS 时订阅真实 GnssSource（运行中可经 setGnss 起/停），样本 → gps.csv
        if (config.gnssEnabled) gnssJob = launchGnss()
    }

    /** 起本机 GNSS 订阅（while-in-use，D-3；缺权限/定位关时 GnssSource 自给空流）。每个定位样本 → gps.csv。 */
    private fun launchGnss(): Job? {
        val rs = runScope ?: return null
        val ch = events ?: return null
        return rs.launch {
            gnssSource.samples().collect { s ->
                ch.send(RunEvent.Gps(clock.nowMs(), s.lat, s.lon, s.altM, s.speedMps, s.accuracyM))
            }
        }
    }

    private suspend fun consume(ch: Channel<RunEvent>) {
        for (e in ch) {
            when (e) {
                is RunEvent.Notif -> handleNotif(e)
                is RunEvent.Link -> handleLink(e)
                RunEvent.Tick -> {
                    // 采集中存储写满 → 自动结束并安全落盘（§5.4 / v2-C③）
                    if (storageMonitor.usableBytes() < io.bluetrace.shared.data.StoragePolicy.MIN_FREE_DURING) {
                        diagnostics.add(LogLevel.WARN, "storage", "usable space low → auto-stop")
                        finishInternal(StopReason.STORAGE_FULL); runScope?.cancel(); return
                    }
                    pushState()
                }
                is RunEvent.Pin -> handlePin(e.text)
                is RunEvent.Interval -> handleInterval(e.text)
                is RunEvent.Pause -> { displayPaused = e.paused; pushState() }
                is RunEvent.SetEnabled -> {
                    recorder?.setEnabledTypes(e.types)
                    enabledStreamNames = e.types.map { io.bluetrace.shared.domain.DecodedStream.ofCollectType(it).csvName }.toSet()
                    activeConfig = activeConfig?.copy(enabledTypes = e.types)
                    pushState()
                }
                is RunEvent.SetGnss -> {
                    recorder?.setGnssEnabled(e.enabled)
                    if (e.enabled) { if (gnssJob == null) gnssJob = launchGnss() } else { gnssJob?.cancel(); gnssJob = null }
                    activeConfig = activeConfig?.copy(gnssEnabled = e.enabled)
                    pushState()
                }
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
    override fun setEnabledTypes(types: Set<io.bluetrace.shared.domain.CollectType>) {
        events?.trySend(RunEvent.SetEnabled(types))
    }
    override fun setGnss(enabled: Boolean) { events?.trySend(RunEvent.SetGnss(enabled)) }
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
        gnssJob = null
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
            mainScene = config.scene.mainToken,
            subScene = config.scene.subToken,
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
            files = files.map { io.bluetrace.shared.data.ManifestFile(it.relativePath, it.category.name, it.lineCount, it.sizeBytes) },
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
        scene = config.scene,
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
    data class SetEnabled(val types: Set<io.bluetrace.shared.domain.CollectType>) : RunEvent
    data class SetGnss(val enabled: Boolean) : RunEvent
    data class Gps(
        val epochMs: Long, val lat: Double, val lon: Double,
        val alt: Double, val speed: Double, val accuracy: Double,
    ) : RunEvent
    data object StorageFull : RunEvent
    data class Stop(val reason: StopReason, val ack: CompletableDeferred<SessionSummary>) : RunEvent
}
