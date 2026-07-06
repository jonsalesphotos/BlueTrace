package io.bluetrace.shared.session

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 真实采集编排（v1 喂 Mock BLE，仅依赖 [BleClient] 接口）。所有 Notify / 连接事件 / 计时 tick / 标签
 * 经单一 [events] channel 由一个消费协程串行处理 → 落盘与状态变更天然无竞态、文件无并发写。
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
    /**
     * 会话事件循环（含落盘）的派发上下文（架构评估 A1）：okio 写盘是阻塞 IO，不能占 CPU 计算池。
     * Android 注入 `Dispatchers.IO.limitedParallelism(1)`——进 IO 弹性池且仍单线程执行，
     * 不破坏"单消费者串行无竞态"语义；测试用默认空上下文（跟随 TestDispatcher）。
     */
    private val runContext: CoroutineContext = EmptyCoroutineContext,
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
    // 高频数据路溢出计数（→ quality.droppedPackets）。生产者协程与消费协程同派发器（注入的 scope），无并发写。
    private var droppedNotifs = 0L
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
        decoder.onSessionStart() // 会话边界：清跨会话解码状态（分片重组缓冲等；decoder 是全局单例）

        val lay = SessionLayout(sessionsRoot / sessionFolderName(config))
        layout = lay

        config.devices.forEach { d ->
            deviceStates[d.deviceId] = RunDeviceState(d.deviceId, d.name, d.kind, LinkState.CONNECTED)
            activeSensors[d.deviceId] = LinkedHashSet()
        }
        diagnostics.add(LogLevel.INFO, "session", "start ${lay.sessionDir.name}")
        pushState()

        val ch = Channel<RunEvent>(Channel.BUFFERED)
        events = ch
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + runContext)
        runScope = rs

        rs.launch {
            // 建目录/首写 manifest 挪进会话 IO 上下文（原在调用线程=主线程，架构#4/A3）；
            // producers 先发的事件在 buffered channel 里排队，init 完成后 consume 才开始取。
            val init = runCatching {
                recorder = SessionRecorder(fileSystem, lay, config.enabledTypes, config.gnssEnabled)
                // 开始即写 manifest 关键信息（会话自描述、被杀也能解，§6.2）
                sessionStore.writeManifest(lay, buildManifest(config, end = null, reason = null, files = emptyList(), quality = QualitySummary()))
            }
            if (init.isFailure) {
                diagnostics.add(LogLevel.ERROR, "session", "init storage failed: ${init.exceptionOrNull()?.message} → auto-stop")
                exitConsume(ch, runCatching { finishInternal(StopReason.ERROR) }.getOrNull())
                return@launch
            }
            consume(ch)
        }
        config.devices.forEach { d ->
            rs.launch {
                bleClient.notifications(d.deviceId).collect {
                    // 高频数据路不反压来源：满则丢弃并计数（→ quality.droppedPackets）。
                    // 真实 BLE 的 GATT 回调线程不可挂起，挂起式 send 在真机上不可行；Mock 同语义对齐。
                    if (ch.trySend(RunEvent.Notif(d.kind, it)).isFailure) droppedNotifs++
                }
            }
            // 链路/控制事件必须可靠投递（丢一次状态转换会错计断联时长），仍走挂起式 send
            rs.launch { bleClient.linkState(d.deviceId).collect { sendOrDrop(ch, RunEvent.Link(d.deviceId, d.kind, it)) } }
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
                sendOrDrop(ch, RunEvent.Gps(clock.nowMs(), s.lat, s.lon, s.altM, s.speedMps, s.accuracyM))
            }
        }
    }

    private suspend fun consume(ch: Channel<RunEvent>) {
        try {
            for (e in ch) {
                when (e) {
                    is RunEvent.Notif -> handleNotif(e)
                    is RunEvent.Link -> handleLink(e)
                    RunEvent.Tick -> {
                        // 采集中存储写满 → 自动结束并安全落盘（§5.4 / v2-C③）
                        if (storageMonitor.usableBytes() < io.bluetrace.shared.data.StoragePolicy.MIN_FREE_DURING) {
                            diagnostics.add(LogLevel.WARN, "storage", "usable space low → auto-stop")
                            exitConsume(ch, finishInternal(StopReason.STORAGE_FULL)); return
                        }
                        recorder?.flushWriters() // 批量刷盘：崩溃损失窗口 = TICK_MS（换掉逐行 flush 的 syscall 开销）
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
                    RunEvent.StorageFull -> { exitConsume(ch, finishInternal(StopReason.STORAGE_FULL)); return }
                    is RunEvent.Stop -> { val s = finishInternal(e.reason); e.ack.complete(s); exitConsume(ch, s); return }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 落盘/解码等运行期异常（磁盘满最典型）：安全收尾而不是让消费协程死亡——
            // 否则未捕获异常直接崩进程，且 producers 填满 channel 后 stop() 的 ack 永远等不到。
            diagnostics.add(LogLevel.ERROR, "session", "consume failed: ${t.message ?: t::class.simpleName} → auto-stop")
            val s = runCatching { finishInternal(StopReason.ERROR) }.getOrNull()
            exitConsume(ch, s)
        }
    }

    /**
     * 消费循环退出前收尾：关渠道并清空残留事件，给排队中的 Stop 补 ack —— 否则
     * "存储满自动结束 / IO 异常收尾"与"长按结束"竞态时 stop() 会永久挂起。最后取消 runScope。
     */
    private fun exitConsume(ch: Channel<RunEvent>, summary: SessionSummary?) {
        ch.close()
        while (true) {
            val e = ch.tryReceive().getOrNull() ?: break
            if (e is RunEvent.Stop) {
                val s = summary ?: _finished.value
                if (s != null) e.ack.complete(s) else e.ack.completeExceptionally(IllegalStateException("session ended without summary"))
            }
        }
        runScope?.cancel()
    }

    /** 生产者侧安全投递：消费循环收尾后渠道已关闭，晚到的事件静默丢弃（会话已结束）。 */
    private suspend fun sendOrDrop(ch: Channel<RunEvent>, e: RunEvent) {
        try { ch.send(e) } catch (_: ClosedSendChannelException) { }
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
            decoder.onDeviceReset(e.deviceId) // 断连即弃该设备重组缓冲：防与重连后的新流错拼
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

    private suspend fun finishInternal(reason: StopReason): SessionSummary {
        status = RunStatus.STOPPED
        stopReason = reason
        endEpochMs = clock.nowMs()
        // 收尾未闭合的断联区间
        disconnectStartMs.values.forEach { disconnectTotalMs += (endEpochMs - it).coerceAtLeast(0) }
        disconnectStartMs.clear()

        val cfg = activeConfig!!
        val lay = layout!!
        // 收尾路径尽量不抛：finalize / manifest 写失败（磁盘满）也要产出摘要让 UI 能退出；
        // manifest 没写上 → 会话保持"开口"，下次启动 autoFinalizeOpenSession 兜底修复。
        val files = runCatching { recorder?.finalizeFiles() ?: emptyList() }
            .getOrElse { diagnostics.add(LogLevel.ERROR, "session", "finalize files failed: ${it.message}"); emptyList() }
        val quality = QualitySummary(reconnectCount, disconnectTotalMs, droppedNotifs)
        runCatching { sessionStore.writeManifest(lay, buildManifest(cfg, endEpochMs, reason, files, quality)) }
            .onFailure { diagnostics.add(LogLevel.ERROR, "session", "manifest write failed (auto-finalize next launch): ${it.message}") }
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
        bleClient.debugInjectDisconnect(target.deviceId) // 经接口演示钩子，真实实现为 no-op
    }

    override suspend fun stop(reason: StopReason): SessionSummary? {
        // 无活动会话（进程恢复后的幽灵调用等）→ 返回最近摘要或 null，绝不抛错（否则 UI 侧 launch 直接崩）。
        if (status != RunStatus.COLLECTING) return _finished.value
        val ch = events ?: return _finished.value
        val ack = CompletableDeferred<SessionSummary>()
        try {
            ch.send(RunEvent.Stop(reason, ack))
        } catch (_: ClosedSendChannelException) {
            // 消费侧已收尾（存储满/IO 异常与手动结束竞态）→ 直接取当前结果
            return _finished.value
        }
        return try {
            ack.await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            _finished.value
        }
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
        labelIntervalOpen = false; displayPaused = false; stopReason = null; droppedNotifs = 0
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
