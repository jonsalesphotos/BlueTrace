package io.bluetrace.shared.ble.mock

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.ble.extract16

import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.MockPacketCodec
import io.bluetrace.shared.s7.S7MockWatch
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.zx.PROFILE_ZX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1 BLE = Mock(第一版核心). 造假设备 + 连接后持续造数据(原始字节 [MockPacketCodec]).
 * 放 commonMain 便于 JVM 单测; 用 coroutines 定时 emit. 可注入断联事件演示「内联重连中 → 自动续」.
 *
 * 协议冻结后整类替换为 androidMain 的 Nordic 实现(同 [BleClient] 接口), 上层不改.
 */
class MockBleClient(
    private val clock: EpochClock,
    /** 断联后自动重连的定时器跑在这个 scope(app=应用域 / 测试=测试域).  */
    private val scope: CoroutineScope,
    private val emitIntervalMs: Long = 100,
    private val connectDelayMs: Long = 600,
    private val reconnectDelayMs: Long = 3000,
) : BleClient {

    /** 下一次扫描是否走「无结果空态」(设备C 演示).  */
    var nextScanEmpty: Boolean = false

    private val roster: List<ScannedDevice> = listOf(
        ScannedDevice("dut-0427", "BT-DUT-0427", "C4:7B:8D:0A:04:27", -52, DeviceKind.DUT),
        ScannedDevice("dut-0319", "BT-DUT-0319", "C4:7B:8D:0A:03:19", -63, DeviceKind.DUT),
        ScannedDevice("dut-0102", "BT-DUT-0102", "C4:7B:8D:0A:01:02", -78, DeviceKind.DUT),
        ScannedDevice("dut-0588", "BT-DUT-0588", "C4:7B:8D:0A:05:88", -86, DeviceKind.DUT),
        ScannedDevice(
            "ref-h10", "Polar H10", "A0:9E:1A:55:0D:10", -60, DeviceKind.REFERENCE, PROFILE_HRS,
            advertisedServices = listOf("180D"),
        ),
        // S7 手表(B2A 协议 Mock, 设备维护控制台联调用): MAC 用测试真机地址(S7_TEST_MAC, 非白名单).
        // 广播 UUID 表按真机口径(180A + FFE0/FFE1/FFE2/FFEB)——识别走 DeviceProfileCatalog(S7 档案命中 FFE0),
        // 名称仅展示; 广播名后缀 = MAC[1]MAC[0] 4 位 hex → FCC4(spec §1). roster 直接带 profileId=PROFILE_S7.
        ScannedDevice(
            "s7-fcc4", "SKG WATCH S7-FCC4", io.bluetrace.shared.domain.S7_TEST_MAC, -58, DeviceKind.DUT, PROFILE_S7,
            advertisedServices = listOf("180A", "FFE0", "FFE1", "FFE2", "FFEB"),
        ),
        // 追加两台 S7(多设备 OTA 演示/联调): 各自独立 S7MockWatch(见下 s7Watch(id)), MAC 末 4 位 = 名称后缀.
        ScannedDevice(
            "s7-a31b", "SKG WATCH S7-A31B", "71:61:48:19:A3:1B", -60, DeviceKind.DUT, PROFILE_S7,
            advertisedServices = listOf("180A", "FFE0", "FFE1", "FFE2", "FFEB"),
        ),
        ScannedDevice(
            "s7-2d90", "SKG WATCH S7-2D90", "71:61:48:19:2D:90", -67, DeviceKind.DUT, PROFILE_S7,
            advertisedServices = listOf("180A", "FFE0", "FFE1", "FFE2", "FFEB"),
        ),
        // ZX 假协议手表(W6 异构验收 Mock 设备, shared/.../zx 包): 与 S7 全维度异构——服务 AA00(非 FFE0),
        // 写/通知特征 AA01/AA02(非 FFE1/FFE2), Write With Response(非 Write Without Response),
        // OTA 原地生效不断链不回连(非自复位回连). 本条目是"新增协议只加包 + 一行注册"判据的框架侧唯一触点
        // ——命令面/通知面无需为其新增模拟分支(ZxDeviceControl/ZxFirmwareUpdateStrategy 走纯内存假数据,
        // 不依赖下方 write()/notifications() 的路由).
        ScannedDevice(
            "zx-9001", "ZX WATCH ZX-9001", "66:77:88:99:00:01", -55, DeviceKind.DUT, PROFILE_ZX,
            advertisedServices = listOf("AA00"),
        ),
    )
    private val byId: Map<String, ScannedDevice> = roster.associateBy { it.id }

    private val links = HashMap<String, MutableStateFlow<LinkState>>()

    private fun link(deviceId: String): MutableStateFlow<LinkState> =
        links.getOrPut(deviceId) { MutableStateFlow(LinkState.DISCONNECTED) }

    override fun scan(): Flow<List<ScannedDevice>> = flow {
        emit(emptyList())
        if (nextScanEmpty) {
            // 空态: 60s 内不产设备(ViewModel 计时自停后落空态)
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

    /** [spec] 接受即忽略: Mock 不做真实 GATT 发现/订阅, 连接由 [connectDelayMs] 定时器自驱(单通道). */
    override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {
        val l = link(device.id)
        l.value = LinkState.CONNECTING
        delay(connectDelayMs)
        l.value = LinkState.CONNECTED
    }

    override suspend fun disconnect(deviceId: String) {
        link(deviceId).value = LinkState.DISCONNECTED
    }

    override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)

    /** Mock 协商 MTU(可调, 联调 OTA 分片尺寸用; 默认 247 = 真机常见协商值).  */
    var mockMtu: Int = 247
    override fun negotiatedMtu(deviceId: String): Int = mockMtu

    /**
     * Mock 无真实 GATT 发现: 回该 roster 设备预置的广播 service 表(16-bit 短码), 供会话宿主
     * confirm 二次确认(S7 设备含 FFE0 -> S7Profile.confirm 通过); 未知设备回空.
     */
    override fun discoveredService16s(deviceId: String): List<String> =
        byId[deviceId]?.advertisedServices?.mapNotNull { extract16(it) } ?: emptyList()

    /** 演示「断联 → 内联重连中 → 自动续」: 标 RECONNECTING, [reconnectDelayMs] 后自动回 CONNECTED.  */
    fun injectDisconnect(deviceId: String) {
        val l = link(deviceId)
        if (l.value != LinkState.CONNECTED) return
        l.value = LinkState.RECONNECTING
        scope.launch {
            delay(reconnectDelayMs)
            if (l.value == LinkState.RECONNECTING) l.value = LinkState.CONNECTED
        }
    }

    /** [BleClient.debugInjectDisconnect] 的 Mock 实现(编排层经接口调用, 不再对 Mock 具体类型强转).  */
    override fun debugInjectDisconnect(deviceId: String) = injectDisconnect(deviceId)

    private val btOffDevices = HashSet<String>()

    /**
     * 系统蓝牙开关变化(app 监听 ACTION_STATE_CHANGED 广播驱动, §5.4 横切A):
     * 关 → 已连设备转"重连中"(暂停接收, 不自动续); 开 → 恢复已连.
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

    /** [BleClient.onAdapterStateChanged]: service 侧只依赖接口, 不再注入 Mock 具体类型.  */
    override fun onAdapterStateChanged(off: Boolean) = setBluetoothOff(off)

    // ---- S7 手表模拟(B2A 协议; 每设备一个 S7MockWatch + inbound, 支持多台联调 / 多设备 OTA 演示)----
    // 该设备是否走 S7 命令面模拟: roster 的 S7 设备已带 profileId=PROFILE_S7(W5 起按 profileId 判定,
    // 不再耦合 s7 包的识别工具). S7 应答帧回填的 Notify 通道短码见 [S7_NOTIFY_16](对齐真机口径).
    private fun isS7(device: ScannedDevice): Boolean = device.profileId == PROFILE_S7
    private val s7Watches = HashMap<String, S7MockWatch>()
    private val s7Inbounds = HashMap<String, MutableSharedFlow<BleNotification>>()
    // 刷后复位=真: OTA 末 STOP 后模拟设备自复位断链 → 上层等复位→重连闭环走通(演示更真, 无 90s 空等)
    private fun s7Watch(id: String): S7MockWatch = s7Watches.getOrPut(id) { S7MockWatch(clock).apply { otaRebootAfterComplete = true } }
    private fun s7Inbound(id: String): MutableSharedFlow<BleNotification> =
        s7Inbounds.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    /**
     * 下行写: S7 设备路由到 [S7MockWatch] 生成应答帧(模拟链路时延 40ms/帧);
     * 其余 Mock 设备无命令面, 静默丢弃(与真实 GATT 对未订阅特征写入为 no-op 一致).
     *
     * [char16] 接受即忽略: Mock 单通道(S7 命令面隐含写 FFE1), 无需按特征寻址.
     */
    override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
        val device = byId[deviceId] ?: return
        if (!isS7(device)) return
        if (link(deviceId).value != LinkState.CONNECTED) return
        val reply = s7Watch(deviceId).handle(bytes)
        scope.launch {
            for (frame in reply.frames) {
                delay(40)
                if (link(deviceId).value != LinkState.CONNECTED) break
                // 应答帧 = B2A 表->App Notify(TX=FFE2): 补填 characteristicId 与真机 AndroidBleClient 口径一致
                s7Inbound(deviceId).emit(BleNotification(deviceId, clock.nowMs(), frame, S7_NOTIFY_16))
            }
            if (reply.disconnectAfter) {
                delay(400) // 模拟设备执行关机/重启后 GATT 断开
                link(deviceId).value = LinkState.DISCONNECTED
            }
        }
    }

    /** S7 设备 Notify 流: 模拟应答帧 + 30s 周期心跳(仅连接态). 心跳同走 B2A TX(FFE2) 通道. */
    private fun s7Notifications(deviceId: String): Flow<BleNotification> = channelFlow {
        val l = link(deviceId)
        launch {
            s7Inbound(deviceId).collect { send(it) } // 已按设备分流
        }
        while (coroutineContext.isActive) {
            delay(30_000)
            if (l.value == LinkState.CONNECTED) {
                send(BleNotification(deviceId, clock.nowMs(), s7Watch(deviceId).heartbeatFrame(), S7_NOTIFY_16))
            }
        }
    }

    override fun notifications(deviceId: String): Flow<BleNotification> {
        val device = byId[deviceId]
        if (device != null && isS7(device)) {
            return s7Notifications(deviceId)
        }
        return dataNotifications(deviceId)
    }

    private fun dataNotifications(deviceId: String): Flow<BleNotification> = flow {
        val device = byId[deviceId] ?: return@flow
        val baseMs = clock.nowMs()
        var phase = 0
        var lastHrMs = 0L
        val l = link(deviceId)

        suspend fun send(stream: DecodedStream, tsUs: Long, channels: List<Int>) {
            val raw = MockPacketCodec.encode(stream, tsUs, channels)
            // characteristicId 留空: Mock 线数据协议(MockBleProfile)单通道, 解码走 DeviceParserHost 单通道兜底路由(其契约即"不带特征 id").
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

    private companion object {
        /** S7 Notify 通道短码(表->App, FFE2): 应答/心跳帧回填的 characteristicId, 对齐真机 AndroidBleClient. */
        const val S7_NOTIFY_16 = "FFE2"
    }
}
