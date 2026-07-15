package io.bluetrace.shared.b2a

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.ConnectionRegistry
import io.bluetrace.shared.device.FirmwareUpdateStrategy
import io.bluetrace.shared.device.FwUpdateResult
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 队列内一台设备的处理状态(设计见 Docs/OTA/S7多设备OTA_设计.md §4/§5.3).  */
enum class DeviceOtaStatus {
    QUEUED, // 待升级(入队, 未连接)
    CONNECTING, // 连接中
    READING, // 读版本 + 电量
    FLASHING, // 刷写中(phase 细分 下载/等复位/重连/读版本)
    DONE, // 完成
    FAILED, // 失败
    SKIPPED_LOW_BATTERY, // 电量 < 门槛, 跳过
}

/** 队列项: 设备 + 当前状态 + 版本/电量前后值 + 进度. 纯数据(KMP, UI 无关).  */
data class DeviceOtaItem(
    val device: ScannedDevice,
    val status: DeviceOtaStatus = DeviceOtaStatus.QUEUED,
    val phase: OtaPhase? = null,
    val progress: OtaProgress? = null,
    val versionBefore: String? = null,
    val versionAfter: String? = null,
    val batteryBefore: Int? = null,
    val batteryAfter: Int? = null,
    val failReason: String? = null,
) {
    /** 失败/低电可手动重试.  */
    val retriable: Boolean get() = status == DeviceOtaStatus.FAILED || status == DeviceOtaStatus.SKIPPED_LOW_BATTERY

    /** 通信中的当前台锁定不可删; 其余(待升级/完成/失败/跳过)可删.  */
    val removable: Boolean get() = status != DeviceOtaStatus.CONNECTING &&
        status != DeviceOtaStatus.READING && status != DeviceOtaStatus.FLASHING
}

/**
 * 多设备 OTA 串行编排核(KMP, commonMain, 无 Android 依赖, iOS 可复用).
 *
 * 一个包全队列共用, **串行**逐台跑: 连接→读版本+电量→(电量门槛)→刷写→复读电量→断开→下一台.
 * 失败/低电即跳过, 继续下一台, 失败/跳过项可手动重试. 串行理由(设备端授权 + 切片看门狗 + 重连风暴)
 * 与流程见 Docs/OTA/S7多设备OTA_设计.md.
 *
 * **W4 瘦身为队列壳**: 排队/电量门槛/重试/取消/日志留本层, 逐台把刷写下沉 [B2aFirmwareUpdateStrategy.run]
 * (吸收原 OtaProvisioner/B2aOtaSession 编排链), 手动停止善后转发 [B2aFirmwareUpdateStrategy.abort]
 * (传输态门控/永不 STOP 红线在策略内). 电量门槛读数仍走短命 [B2aConsole](`swVer`/`battery.percent`,
 * 改动最小; W3 的 DeviceSessionManager 途径引宿主依赖, 暂不引入).
 *
 * 状态经 [queue]/[running] StateFlow + [opLog] SharedFlow 上抛; Android VM / iOS 各做薄壳订阅.
 * 包加载(Uri→zip 解析), 扫描 UI 属平台壳职责, 不在本层——[startBatch]/[retry] 直接收 [OtaPackage].
 */
class MultiOtaController(
    private val ble: BleClient,
    private val registry: ConnectionRegistry,
    private val clock: EpochClock,
    private val zone: TimeZoneProvider,
    private val scope: CoroutineScope,
    /** 刷前电量门槛: 低于此值跳过(固件无低电门控, 掉电变砖由 App 兜底). 工程配置 ota.lowBatteryPct.  */
    private val lowBatteryPct: Int = 30,
    /** OTA 后回连扫描预算(产品下限 60s; 工程配置 ota.reconnectScanSeconds, 只可调大).  */
    private val reconnectScanMs: Long = 60_000,
    /**
     * 手动停止善后(发重启指令)用的 scope——须比 UI 壳长寿(Android 传 app 级 scope),
     * 否则"中止并离开"销毁 VM 时善后协程被连带取消, 重启指令发不出去. 测试可与 [scope] 同一个.
     */
    private val abortScope: CoroutineScope = scope,
    /**
     * app 级 OTA 操作租约(可空=不启用, 供旧测试): 跨实例互斥——本实例的 stopping 门只管自己,
     * 单/多两屏或退屏重进的新实例靠共享租约挡住"旧善后在飞时开新一轮". 所有权见
     * [io.bluetrace.shared.device.OtaOperationGate] KDoc(Lease token, 过期释放 no-op).
     */
    private val gate: io.bluetrace.shared.device.OtaOperationGate? = null,
) {
    private val _queue = MutableStateFlow<List<DeviceOtaItem>>(emptyList())
    val queue: StateFlow<List<DeviceOtaItem>> = _queue

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    // 手动停止的善后进行中(取消/标记/策略 abort/断开): 期间禁止 startBatch/retry——
    // 旧善后跑在 abortScope 上, 无门槛时快速重启会让它误伤新一轮(abort 新策略/标错新队列项).
    private val _stopping = MutableStateFlow(false)

    /** 停止善后进行中(公开给 UI: 期间开始/重试按钮应禁用, 与 [running] 一并驱动按钮态). */
    val stopping: StateFlow<Boolean> = _stopping

    private val _opLog = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opLog: SharedFlow<String> = _opLog

    private var batchJob: Job? = null

    // 当前处理台的固件升级策略(processDevice 开始即建): stopBatch 转发 abort() 用.
    // 串行编排下无并发访问; cancelAndJoin 屏障后读有 happens-before.
    private var currentStrategy: FirmwareUpdateStrategy? = null

    /**
     * 一轮运行的**善后所有权凭据**: 恒非空对象(即便 gate=null 也有 token), [lease] 才可空——
     * 不用可空 Lease 同时表达"无 gate"与"已失去所有权"两种语义.
     */
    private class RunToken(val lease: io.bluetrace.shared.device.OtaOperationGate.Lease?)

    // 本轮所有权槽(原子): 仲裁的是**整个善后的执行权**, 不只是租约释放——运行协程 finally /
    // stopBatch / close 三方谁 CAS 取走 token, 谁才允许执行收尾(标记/abort/disconnect)与释放
    // 租约; CAS 败者**直接退出, 一个动作都不做**. 只仲裁释放权是不够的: 败者若照跑善后, 会与
    // 赢家并发重复 abort/disconnect(非传输态重复发 CTRL_RESET), 或在新实例开始后打断其连接.
    private val runToken = MutableStateFlow<RunToken?>(null)

    // ---- 队列增删(入队不连接)----

    /** 追加设备到队列(按 id 去重, 已在册的忽略).  */
    fun addDevices(devices: List<ScannedDevice>) {
        if (devices.isEmpty()) return
        _queue.update { cur ->
            val existing = cur.mapTo(HashSet()) { it.device.id }
            cur + devices.filterNot { it.id in existing }.map { DeviceOtaItem(it) }
        }
    }

    /** 移除一台(仅可移除项: 通信中的当前台锁定).  */
    fun removeDevice(id: String) {
        _queue.update { cur ->
            val item = cur.firstOrNull { it.device.id == id } ?: return@update cur
            if (!item.removable) return@update cur
            cur.filterNot { it.device.id == id }
        }
    }

    fun clearQueue() {
        if (_running.value) return
        _queue.value = emptyList()
    }

    // ---- 批量执行 ----

    /** 启动串行批量; 返回批量 Job(可 join 观测完成, 测试/调用方用); 不满足条件返回 null.  */
    fun startBatch(pkg: OtaPackage): Job? {
        if (_running.value || _stopping.value) return null
        if (_queue.value.none { it.status == DeviceOtaStatus.QUEUED }) return null
        // 跨实例租约最后取(本地条件全过才占): 占不到=另一实例在运行/善后
        val l = if (gate != null) gate.tryAcquire() ?: return null else null
        return launchBatch(pkg, l)
    }

    /** 批量协程本体(调用方已持租约 [l]): startBatch/retry 的公共尾段. */
    private fun launchBatch(pkg: OtaPackage, l: io.bluetrace.shared.device.OtaOperationGate.Lease?): Job {
        val token = RunToken(l)
        runToken.value = token
        val job = scope.launch {
            _running.value = true
            log("===== 开始批量升级 =====")
            try {
                while (isActive) {
                    // 每轮取队列里第一个"待升级"(删除/重试导致的变动天然生效)
                    val next = _queue.value.firstOrNull { it.status == DeviceOtaStatus.QUEUED } ?: break
                    try {
                        processDevice(next.device, pkg)
                    } catch (c: CancellationException) {
                        throw c // 停止批量/清理: 照常上抛
                    } catch (e: Exception) {
                        // 单台意外异常不拖垮整批: 标失败, 尽力断开, 继续下一台
                        fail(next.device.id, e.message ?: "异常")
                        try {
                            disconnect(next.device.id)
                        } catch (c: CancellationException) {
                            throw c // disconnect 是 suspend: 取消不得被当普通失败吞掉
                        } catch (_: Exception) {
                        }
                    }
                }
                log("===== 结束：${summaryLine()} =====")
            } finally {
                _running.value = false
                // 善后所有权 = isActive 且 CAS 取走 token, 两者缺一不可:
                // - CAS 挡"stopBatch/close 已接管"(即便 isActive=true 的移交间隙窗口, CAS 必败);
                // - isActive 挡"被外部取消"——Lifecycle 2.8+ 先取消 viewModelScope **再**调
                //   onCleared: 取消路径的 finally 若抢走 token 并释放, 随后 onCleared 的 CAS
                //   必败而跳过 abort/disconnect, 设备无人善后 gate 却已空闲. 取消时不抢,
                //   token 留给接管方(onCleared/close)做完整善后.
                if (isActive && runToken.compareAndSet(token, null)) {
                    token.lease?.let { gate?.release(it) }
                }
            }
        }
        batchJob = job
        return job
    }

    /**
     * 手动停止批量: 取消 → 当前通信中的台子标"手动停止"(可重试)→ 转发 [B2aFirmwareUpdateStrategy.abort]
     * (传输态门控/永不 STOP 红线在策略内; 链路仍在且非传输态则发 CTRL_RESET 复位回干净状态)→ 断开清退.
     * 善后跑 [abortScope](退屏也能发完).
     *
     * **运行代际防护**: 策略在发起停止那一刻**快照**为局部值, 善后绝不回读可变成员;
     * [_stopping] 关死 startBatch/retry 入口, 善后完成前新一轮起不来——二者合力保证
     * 旧善后不可能 abort 新策略/标错新队列项/断开新一轮设备.
     */
    fun stopBatch() {
        val job = batchJob ?: return
        if (_stopping.value) return
        // 仲裁善后执行权: CAS 败 = 运行已自然收尾(finally 取走)或 close 已接管——**直接退出,
        // 一个动作都不做**(照跑善后会与赢家并发重复 abort/disconnect, 或打断新实例).
        val token = runToken.value?.takeIf { runToken.compareAndSet(it, null) } ?: return
        val strategy = currentStrategy // 快照本轮身份
        _stopping.value = true
        abortScope.launch {
            try {
                job.cancelAndJoin()
                log("已停止批量")
                // 取消发生在流程中段时, 当前台停在 CONNECTING/READING/FLASHING(= 不可删项);
                // stopping 门下队列不会被新一轮改写, 此读仍属本轮.
                val active = _queue.value.firstOrNull { !it.removable }
                if (active != null) {
                    val id = active.device.id
                    updateItem(id) { it.copy(status = DeviceOtaStatus.FAILED, failReason = "手动停止", phase = null, progress = null) }
                    // 传输态门控(是否吞重启指令)由策略内部 otaTransferActive 自持; 善后结果经 onLog=log 回吐终端.
                    strategy?.abort()
                    try {
                        disconnect(id)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                    }
                }
            } finally {
                _stopping.value = false
                token.lease?.let { gate?.release(it) } // 善后完全结束才释放(所有权已随 token 归本路径)
            }
        }
    }

    /**
     * 单台重试(失败/低电): 重置为待升级; 未在跑则起一轮把它跑掉. 返回新起的批量 Job(在跑中返回 null).
     * 拒绝路径**不碰队列**(原子入口): 停止善后期间/跨实例租约被占, 先拒绝再说——否则项被改成
     * QUEUED 却没有任务跑, 留下"已排队无任务"的假状态.
     */
    fun retry(id: String, pkg: OtaPackage): Job? = retryInternal(pkg) { cur ->
        cur.map { if (it.device.id == id && it.retriable) it.resetToQueued() else it }
    }

    /** 重试全部失败/低电项. 返回新起的批量 Job(在跑中返回 null). 拒绝路径不碰队列(同 [retry]).  */
    fun retryAllFailed(pkg: OtaPackage): Job? = retryInternal(pkg) { cur ->
        cur.map { if (it.retriable) it.resetToQueued() else it }
    }

    /** 重试原子入口: 先过全部门槛(stopping/租约)再改队列再启动——任一步拒绝都不留队列副作用. */
    private fun retryInternal(pkg: OtaPackage, requeue: (List<DeviceOtaItem>) -> List<DeviceOtaItem>): Job? {
        if (_stopping.value) return null
        if (_running.value) {
            // 在跑中: 改队列即可, 当前批的串行循环会自然消费新 QUEUED 项(沿用既有语义)
            _queue.update(requeue)
            return null
        }
        // 未在跑: 需要起新一轮 -> **先抢租约再改队列**(抢不到不碰队列)
        val l = if (gate != null) gate.tryAcquire() ?: return null else null
        _queue.update(requeue)
        if (_queue.value.none { it.status == DeviceOtaStatus.QUEUED }) {
            // 没有可重试项(id 不存在/不可重试): 归还租约, 不启动(尚未入槽, 直接还 gate)
            l?.let { gate?.release(it) }
            return null
        }
        return launchBatch(pkg, l)
    }

    /** 汇总一行.  */
    fun summaryLine(): String {
        val q = _queue.value
        val done = q.count { it.status == DeviceOtaStatus.DONE }
        val failed = q.count { it.status == DeviceOtaStatus.FAILED }
        val skipped = q.count { it.status == DeviceOtaStatus.SKIPPED_LOW_BATTERY }
        val queued = q.count { it.status == DeviceOtaStatus.QUEUED }
        return "完成 $done · 失败 $failed · 跳过 $skipped · 待升级 $queued"
    }

    private fun DeviceOtaItem.resetToQueued() =
        copy(status = DeviceOtaStatus.QUEUED, failReason = null, phase = null, progress = null)

    /** 一台设备的完整流程(连接→读→门槛→刷→复读→断开).  */
    private suspend fun processDevice(device: ScannedDevice, pkg: OtaPackage) {
        val id = device.id
        log("── ${device.name} ──")

        // 策略在设备处理开始即建(轻量, 无副作用): abort() 在连接/读取阶段(未进 FILE_TRANS)也需可达,
        // 细进度经 onOtaPhase/onOtaProgress 塞回队列项(供现有 UI 直接消费 OtaPhase/OtaProgress).
        val strategy = B2aFirmwareUpdateStrategy(
            ble, device, scope, clock, zone, abortScope,
            reconnectScanMs = reconnectScanMs,
            onLog = { log(it) },
            onOtaPhase = { p -> updateItem(id) { it.copy(phase = p) } },
            onOtaProgress = { pr -> updateItem(id) { it.copy(progress = pr) } },
        )
        currentStrategy = strategy

        // 1) 连接
        updateItem(id) { it.copy(status = DeviceOtaStatus.CONNECTING, phase = null, progress = null, failReason = null) }
        ble.connect(device) // 不抛业务异常, 以 linkState 收敛为准
        if (ble.linkState(id).value != LinkState.CONNECTED) {
            fail(id, "连接失败")
            return
        }
        registry.add(device)

        // 2) 读版本 + 电量(刷前)
        updateItem(id) { it.copy(status = DeviceOtaStatus.READING) }
        val (v0, b0) = readVersionBattery(device)
        updateItem(id) { it.copy(versionBefore = v0, batteryBefore = b0) }
        log("读取：版本=${v0 ?: "未知"} 电量=${b0?.let { "$it%" } ?: "未知"}")

        // 3) 电量门槛
        if (b0 != null && b0 < lowBatteryPct) {
            updateItem(id) { it.copy(status = DeviceOtaStatus.SKIPPED_LOW_BATTERY, failReason = "电量 $b0% < $lowBatteryPct%") }
            log("电量不足（$b0%）→ 跳过")
            disconnect(id)
            return
        }

        // 4) 刷写 + 等复位 + 扫描回连 + 读版本(下沉 B2aFirmwareUpdateStrategy; 细日志经 onLog 回吐, 一条不丢)
        updateItem(id) { it.copy(status = DeviceOtaStatus.FLASHING, phase = OtaPhase.Downloading, progress = null) }
        val result = strategy.run(pkg)

        // 5) 结果 + 复读电量
        when (result) {
            is FwUpdateResult.Success -> {
                registry.add(device)
                val b1 = readBattery(device)
                updateItem(id) {
                    it.copy(
                        status = DeviceOtaStatus.DONE,
                        versionAfter = result.versionAfter,
                        batteryAfter = b1,
                        phase = OtaPhase.Done,
                        progress = null,
                    )
                }
                log("完成 ✓ 版本=${result.versionAfter ?: "未知"} 电量=${b1?.let { "$it%" } ?: "未知"}")
            }
            is FwUpdateResult.Failed -> fail(id, result.summary)
        }

        // 6) 断开, 释放射频给下一台
        disconnect(id)
    }

    private fun fail(id: String, reason: String) {
        updateItem(id) { it.copy(status = DeviceOtaStatus.FAILED, failReason = reason, phase = null, progress = null) }
        log("失败 ✗ $reason")
    }

    private suspend fun disconnect(id: String) {
        ble.disconnect(id)
        registry.remove(id)
    }

    /** 短命 B2aConsole 读版本 + 电量(刷前一次拿全). 抛异常转 null(容错).  */
    private suspend fun readVersionBattery(device: ScannedDevice): Pair<String?, Int?> {
        val c = B2aConsole(ble, device.id, scope, clock, zone)
        c.start()
        return try {
            val v = runCatchingSuspend { c.getDeviceInfo().swVer }
            val b = runCatchingSuspend { c.getBattery().percent }
            v to b
        } finally {
            c.stop()
        }
    }

    /** 短命 B2aConsole 只读电量(刷后复读).  */
    private suspend fun readBattery(device: ScannedDevice): Int? {
        val c = B2aConsole(ble, device.id, scope, clock, zone)
        c.start()
        return try {
            runCatchingSuspend { c.getBattery().percent }
        } finally {
            c.stop()
        }
    }

    /** 容错: 非取消异常转 null(结构化并发下取消照常上抛).  */
    private inline fun <T> runCatchingSuspend(block: () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        null
    }

    private fun updateItem(id: String, f: (DeviceOtaItem) -> DeviceOtaItem) {
        _queue.update { cur -> cur.map { if (it.device.id == id) f(it) else it } }
    }

    /**
     * 非 stop 路径的销毁(VM onCleared 等). 善后执行权同样经 [runToken] 仲裁: CAS 败(运行已
     * 自然收尾 / stopBatch 已接管)则**直接退出**——"中止并离开"= stopBatch 后紧跟 close, 无
     * 仲裁会双善后并发重复 abort/disconnect. 赢家做**完整善后**(取消收尾→策略 abort→断开)
     * 后才释放租约; 善后跑 [abortScope](比 VM 长寿), 与 stopBatch 同款语义.
     */
    fun close() {
        val token = runToken.value?.takeIf { runToken.compareAndSet(it, null) } ?: return
        val job = batchJob
        val strategy = currentStrategy
        if (job != null) {
            abortScope.launch {
                try {
                    job.cancelAndJoin()
                    val active = _queue.value.firstOrNull { !it.removable }
                    if (active != null) {
                        strategy?.abort()
                        try {
                            disconnect(active.device.id)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    token.lease?.let { gate?.release(it) }
                }
            }
        } else {
            token.lease?.let { gate?.release(it) } // token 在而 job 无: 归还租约即可
        }
    }

    private fun log(text: String) {
        _opLog.tryEmit(text)
    }
}
