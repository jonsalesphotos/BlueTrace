package io.bluetrace.shared.ble

import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.MockPacketCodec
import io.bluetrace.shared.util.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1 BLE = Mock（第一版核心）。造假设备 + 连接后持续造数据（原始字节 [MockPacketCodec]）。
 * 放 commonMain 便于 JVM 单测；用 coroutines 定时 emit。可注入断联事件演示「内联重连中 → 自动续」。
 *
 * 协议冻结后整类替换为 androidMain 的 Nordic 实现（同 [BleClient] 接口），上层不改。
 */
class MockBleClient(
    private val clock: EpochClock,
    /** 断联后自动重连的定时器跑在这个 scope（app=应用域 / 测试=测试域）。 */
    private val scope: CoroutineScope,
    private val emitIntervalMs: Long = 100,
    private val connectDelayMs: Long = 600,
    private val reconnectDelayMs: Long = 3000,
) : BleClient {

    /** 下一次扫描是否走「无结果空态」（设备C 演示）。 */
    var nextScanEmpty: Boolean = false

    private val roster: List<ScannedDevice> = listOf(
        ScannedDevice("dut-0427", "BT-DUT-0427", "C4:7B:8D:0A:04:27", -52, DeviceKind.DUT),
        ScannedDevice("dut-0319", "BT-DUT-0319", "C4:7B:8D:0A:03:19", -63, DeviceKind.DUT),
        ScannedDevice("dut-0102", "BT-DUT-0102", "C4:7B:8D:0A:01:02", -78, DeviceKind.DUT),
        ScannedDevice("dut-0588", "BT-DUT-0588", "C4:7B:8D:0A:05:88", -86, DeviceKind.DUT),
        ScannedDevice("ref-h10", "Polar H10", "A0:9E:1A:55:0D:10", -60, DeviceKind.REFERENCE, PROFILE_HRS),
    )
    private val byId: Map<String, ScannedDevice> = roster.associateBy { it.id }

    private val links = HashMap<String, MutableStateFlow<LinkState>>()

    private fun link(deviceId: String): MutableStateFlow<LinkState> =
        links.getOrPut(deviceId) { MutableStateFlow(LinkState.DISCONNECTED) }

    override fun scan(): Flow<List<ScannedDevice>> = flow {
        emit(emptyList())
        if (nextScanEmpty) {
            // 空态：60s 内不产设备（ViewModel 计时自停后落空态）
            while (coroutineContext.isActive) delay(1000)
            return@flow
        }
        val discovered = ArrayList<ScannedDevice>()
        for (d in roster) {
            delay(450)
            discovered.add(d.copy(rssi = jitter(d.rssi)))
            emit(discovered.toList())
        }
        // 持续 RSSI 抖动直到取消
        while (coroutineContext.isActive) {
            delay(1200)
            emit(discovered.map { it.copy(rssi = jitter(it.rssi)) })
        }
    }

    override suspend fun connect(device: ScannedDevice) {
        val l = link(device.id)
        l.value = LinkState.CONNECTING
        delay(connectDelayMs)
        l.value = LinkState.CONNECTED
    }

    override suspend fun disconnect(deviceId: String) {
        link(deviceId).value = LinkState.DISCONNECTED
    }

    override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)

    /** 演示「断联 → 内联重连中 → 自动续」：标 RECONNECTING，[reconnectDelayMs] 后自动回 CONNECTED。 */
    fun injectDisconnect(deviceId: String) {
        val l = link(deviceId)
        if (l.value != LinkState.CONNECTED) return
        l.value = LinkState.RECONNECTING
        scope.launch {
            delay(reconnectDelayMs)
            if (l.value == LinkState.RECONNECTING) l.value = LinkState.CONNECTED
        }
    }

    private val btOffDevices = HashSet<String>()

    /**
     * 系统蓝牙开关变化（app 监听 ACTION_STATE_CHANGED 广播驱动，§5.4 横切A）：
     * 关 → 已连设备转"重连中"（暂停接收，不自动续）；开 → 恢复已连。
     */
    fun setBluetoothOff(off: Boolean) {
        if (off) {
            links.forEach { (id, flow) ->
                if (flow.value == LinkState.CONNECTED) {
                    flow.value = LinkState.RECONNECTING
                    btOffDevices.add(id)
                }
            }
        } else {
            btOffDevices.forEach { id ->
                links[id]?.let { if (it.value == LinkState.RECONNECTING) it.value = LinkState.CONNECTED }
            }
            btOffDevices.clear()
        }
    }

    override fun notifications(deviceId: String): Flow<BleNotification> = flow {
        val device = byId[deviceId] ?: return@flow
        val baseMs = clock.nowMs()
        var phase = 0
        var lastHrMs = 0L
        val l = link(deviceId)

        suspend fun send(stream: DecodedStream, tsUs: Long, channels: List<Int>) {
            val raw = MockPacketCodec.encode(stream, tsUs, channels)
            emit(BleNotification(deviceId, clock.nowMs(), raw))
        }

        while (coroutineContext.isActive) {
            if (l.value == LinkState.CONNECTED) {
                val now = clock.nowMs()
                val tsUs = (now - baseMs) * 1000
                when (device.kind) {
                    DeviceKind.DUT -> {
                        send(DecodedStream.PPG_G, tsUs, ppg(phase, 1.0))
                        send(DecodedStream.PPG_G, tsUs + 5_000, ppg(phase + 1, 1.0))
                        send(DecodedStream.PPG_IR, tsUs + 1_000, ppg(phase, 0.7))
                        send(DecodedStream.PPG_IR, tsUs + 6_000, ppg(phase + 1, 0.7))
                        send(DecodedStream.ACC, tsUs + 2_000, acc(phase))
                        if (now - lastHrMs >= 1000) {
                            lastHrMs = now
                            send(DecodedStream.HR, tsUs, listOf(hr(phase)))
                        }
                    }
                    DeviceKind.REFERENCE -> {
                        if (now - lastHrMs >= 1000) {
                            lastHrMs = now
                            send(DecodedStream.HR, tsUs, listOf(hr(phase)))
                        }
                    }
                }
                phase++
            }
            delay(emitIntervalMs)
        }
    }

    // ---- 假波形 ----
    private fun ppg(phase: Int, scale: Double): List<Int> {
        val v = 2048.0 + scale * 900.0 * sin(phase * 0.45) + Random.nextInt(-40, 41)
        return listOf(v.roundToInt())
    }

    private fun acc(phase: Int): List<Int> {
        val x = (40.0 * sin(phase * 0.30)).roundToInt() + Random.nextInt(-6, 7)
        val y = (40.0 * sin(phase * 0.30 + PI / 2)).roundToInt() + Random.nextInt(-6, 7)
        val z = 1000 + (25.0 * sin(phase * 0.12)).roundToInt() + Random.nextInt(-6, 7)
        return listOf(x, y, z)
    }

    private fun hr(phase: Int): Int = (72.0 + 5.0 * sin(phase * 0.02)).roundToInt() + Random.nextInt(-1, 2)

    private fun jitter(base: Int): Int = (base + Random.nextInt(-4, 5)).coerceIn(-99, -30)
}
