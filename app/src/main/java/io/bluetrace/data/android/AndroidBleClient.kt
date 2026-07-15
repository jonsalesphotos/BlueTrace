package io.bluetrace.data.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.ble.GattSpec
import io.bluetrace.shared.ble.extract16
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真实 Android BLE Central(v1 精简版, 真机联调用).
 *
 * 范围(够控制台 + HRS 参考即可, 复杂能力交给后续 Nordic Kotlin-BLE 替换, D-V4-19):
 * - 扫描: 无过滤全量扫描, 提取广播 Service UUID 表(协议识别归 DeviceProfileCatalog, 本层只 extract16 归一);
 * - 连接: connectGatt(TRANSPORT_LE) → requestMtu(247) → discoverServices → 订阅已知协议的 Notify
 *   特征(B2A: FFE2; HRS: 2A37)→ CCC 写成功才算 CONNECTED;
 * - 写: 对 B2A RX(FFE1)Write Without Response, **逐帧串行 + 等 onCharacteristicWrite 再发下一帧**
 *   (写流控/背压——多包切片如 OTA 一切片 17 帧背靠背发会溢出 GATT 缓冲丢包, ota3.log 实证);
 * - 断链: 不自动重连(v1; 断了就 DISCONNECTED, 控制台按钮自动禁用).
 *
 * 所有蓝牙调用包 SecurityException(权限可被运行期收回).
 */
class AndroidBleClient(
    private val context: Context,
    private val clock: EpochClock,
) : BleClient {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private class Conn(
        val gatt: BluetoothGatt,
        /** 默认写特征(char16=null 的写落这里): 探测路径=B2A RX(FFE1); GattSpec 路径=spec.writeChar16. */
        var writeChar: BluetoothGattCharacteristic? = null,
        /** char16 寻址写: 16-bit 短码(extract16 口径) -> 可写特征; 查无静默丢弃. */
        val writeChars: MutableMap<String, BluetoothGattCharacteristic> = mutableMapOf(),
        /** 连接级写类型: GattSpec.writeWithResponse 决定(默认无响应写); W1 全部特征仍走无响应写. */
        @Volatile var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        val ready: CompletableDeferred<Boolean> = CompletableDeferred(),
        /** 协商 MTU(onMtuChanged 存下; OTA 分片尺寸推算用, 观测非配置). 默认 BLE ATT 23.  */
        @Volatile var mtu: Int = 23,
        /** 写流控: 逐帧串行([writeLock])+ 等 [writeAck](onCharacteristicWrite 完成)再发下一帧, 防多包切片溢出 GATT 缓冲丢包.  */
        val writeLock: Mutex = Mutex(),
        @Volatile var writeAck: CompletableDeferred<Int>? = null,
        /** GattSpec 路径待订阅的 Notify 特征队列(CCC 写异步且同一刻只能一个在飞, 故串行); 探测路径不用. */
        val pendingNotify: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque(),
        /** true=GattSpec 串行订阅路径(onDescriptorWrite 续订下一个); false=现状探测单特征路径. */
        @Volatile var specSubscribe: Boolean = false,
    )

    private val conns = ConcurrentHashMap<String, Conn>()
    private val links = ConcurrentHashMap<String, MutableStateFlow<LinkState>>()
    // 连接后发现的 16-bit 短码服务表(会话宿主 confirm 二次确认用; 断连清理).
    private val discoveredServices16 = ConcurrentHashMap<String, List<String>>()

    // 设备级持久通知流(与连接生命周期解耦: 订阅可先于 connect——console attach 早于连接完成)
    private val notifyFlows = ConcurrentHashMap<String, MutableSharedFlow<BleNotification>>()

    private fun link(id: String): MutableStateFlow<LinkState> =
        links.getOrPut(id) { MutableStateFlow(LinkState.DISCONNECTED) }

    private fun notifyFlow(id: String): MutableSharedFlow<BleNotification> =
        notifyFlows.getOrPut(id) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    // ---- 扫描 ----

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val found = LinkedHashMap<String, ScannedDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.toScanned() ?: return
                if (found.size < 5) android.util.Log.d(TAG, "scanResult ${dev.name} ${dev.address} rssi=${dev.rssi} adv=${dev.advertisedServices}")
                found[dev.id] = dev
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                // 2=REGISTRATION_FAILED 5=SCANNING_TOO_FREQUENTLY(Android 静默节流的显式出口)
                android.util.Log.w(TAG, "onScanFailed errorCode=$errorCode")
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            android.util.Log.d(TAG, "startScan issued")
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "startScan SecurityException: ${e.message}")
            trySend(emptyList())
        }
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toScanned(): ScannedDevice? = try {
        val addr = device?.address ?: return null
        // nRF Connect 式全量展示: 无名设备用占位名(便于确认周围广播活性, 按 MAC 定位目标)
        val name = scanRecord?.deviceName ?: device?.name ?: "(unnamed)"
        val adv = scanRecord?.serviceUuids?.mapNotNull { extract16(it.uuid.toString()) } ?: emptyList()
        // 扫描层去识别化(W2): 只上报原始广播(profileId=null/kind=DUT), 识别归 DeviceProfileCatalog
        // (消费投影层 annotate 打标); 名称/MAC/RSSI/广播 service 原样带出.
        ScannedDevice(
            id = addr,
            name = name,
            address = addr,
            rssi = rssi,
            kind = DeviceKind.DUT,
            profileId = null,
            advertisedServices = adv,
        )
    } catch (e: SecurityException) {
        null
    }

    // ---- 连接 / 订阅 ----

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: ScannedDevice, spec: GattSpec?) {
        val l = link(device.id)
        if (l.value == LinkState.CONNECTED || l.value == LinkState.CONNECTING) return
        val btDevice = try {
            adapter?.getRemoteDevice(device.id)
        } catch (e: IllegalArgumentException) {
            null
        } ?: run {
            l.value = LinkState.DISCONNECTED
            return
        }
        l.value = LinkState.CONNECTING

        lateinit var conn: Conn
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // 先协商 MTU(目标 247, SPEC: App 只消费协商结果); 失败也会回调, 届时再发现服务
                        if (!safe { gatt.requestMtu(247) }) safe { gatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        l.value = LinkState.DISCONNECTED
                        conn.ready.complete(false)
                        safe { gatt.close() }
                        conns.remove(device.id)
                        discoveredServices16.remove(device.id)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) conns[device.id]?.mtu = mtu
                safe { gatt.discoverServices() }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    conn.ready.complete(false)
                    return
                }
                // 服务发现完成: 存 16-bit 短码服务表(spec/探测两路径共用), 供会话宿主 confirm 二次确认.
                discoveredServices16[device.id] = gatt.services.mapNotNull { extract16(it.uuid.toString()) }
                // GattSpec 路径(W1 加法): 按 spec 定位服务/登记写特征/串行订阅多 Notify; 协议知识不进本实现.
                if (spec != null) {
                    val svc = gatt.services.firstOrNull { extract16(it.uuid.toString()) == spec.serviceUuid16 }
                    if (svc == null) {
                        conn.ready.complete(false)
                        return
                    }
                    val byShortInSvc = svc.characteristics.associateBy { extract16(it.uuid.toString()) }
                    // 声明的通道必须全量存在(全量契约): 写特征声明了就必须在, notify 一个都不能少——
                    // 缺写特征=之后所有写静默丢弃, 缺一路 notify=静默丢一路数据, 都比连接失败更糟.
                    val declaredWrite = spec.writeChar16
                    if (declaredWrite != null) {
                        val wc = byShortInSvc[declaredWrite]
                        if (wc == null) {
                            conn.ready.complete(false)
                            return
                        }
                        conn.writeChar = wc
                        conn.writeChars[declaredWrite] = wc
                    }
                    conn.writeType = if (spec.writeWithResponse) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    val toSubscribe = ArrayList<BluetoothGattCharacteristic>(spec.notifyChar16s.size)
                    for (n16 in spec.notifyChar16s) {
                        val ch = byShortInSvc[n16] ?: run {
                            conn.ready.complete(false)
                            return
                        }
                        toSubscribe.add(ch)
                    }
                    if (toSubscribe.isEmpty()) {
                        conn.ready.complete(false)
                        return
                    }
                    // CCC descriptor 写异步且同一刻只能一个在飞: 串行状态机(写一个 CCC -> onDescriptorWrite -> 下一个).
                    conn.specSubscribe = true
                    conn.pendingNotify.addAll(toSubscribe)
                    subscribeNext(conn, l)
                    return
                }
                val chars = gatt.services.flatMap { it.characteristics }
                val byShort = chars.groupBy { extract16(it.uuid.toString()) }
                // B2A 优先(连接后确认策略: RX+TX 同在即认定, spec §1); 否则 HRS
                val b2aTx = byShort[PROBE_B2A_TX_16]?.firstOrNull()
                val b2aRx = byShort[PROBE_B2A_RX_16]?.firstOrNull()
                val hrsMeasure = byShort[PROBE_HRS_MEASURE_16]?.firstOrNull()
                val notifyChar = when {
                    b2aTx != null && b2aRx != null -> {
                        conn.writeChar = b2aRx
                        b2aTx
                    }
                    hrsMeasure != null -> hrsMeasure
                    else -> null
                }
                if (notifyChar == null) {
                    conn.ready.complete(false)
                    return
                }
                safe { gatt.setCharacteristicNotification(notifyChar, true) }
                val ccc = notifyChar.getDescriptor(CCC_UUID)
                if (ccc == null) {
                    // 无 CCC 描述符的非常规实现: 直接视为就绪
                    l.value = LinkState.CONNECTED
                    conn.ready.complete(true)
                    return
                }
                val ok = safe { writeCcc(gatt, ccc) }
                if (!ok) conn.ready.complete(false)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CCC_UUID) return
                val ok = status == BluetoothGatt.GATT_SUCCESS
                if (conn.specSubscribe) {
                    // GattSpec 串行订阅: 本特征成功 -> 续订下一个(全部成功才置 CONNECTED); 任一失败即撤销
                    if (ok) subscribeNext(conn, l) else conn.ready.complete(false)
                } else {
                    if (ok) l.value = LinkState.CONNECTED
                    conn.ready.complete(ok)
                }
            }

            // 写完成信号(no-response 写 Android 亦回调): 驱动逐帧写流控, 防多包切片背靠背溢出 GATT 缓冲丢包
            override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                conn.writeAck?.complete(status)
            }

            // API 33+
            override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                // 补填来源特征 16-bit 短码: 多 Notify 协议(如 B2A FFE2 与 DUT 数据特征并存)按此分流解码 + hexlog 标注
                notifyFlow(device.id).tryEmit(BleNotification(device.id, clock.nowMs(), value, extract16(ch.uuid.toString())))
            }

            // API ≤32
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) {
                    val v = ch.value ?: return
                    notifyFlow(device.id).tryEmit(BleNotification(device.id, clock.nowMs(), v, extract16(ch.uuid.toString())))
                }
            }
        }

        val gatt = try {
            btDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        }
        if (gatt == null) {
            l.value = LinkState.DISCONNECTED
            return
        }
        conn = Conn(gatt)
        conns[device.id] = conn

        val ok = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { conn.ready.await() } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 调用方协程被取消(如连接页返回销毁 VM): 必须清理 GATT,
            // 否则泄漏的连接占住设备(设备停止广播, 无法再连)——真机实证过的坑.
            safe { gatt.disconnect() }
            safe { gatt.close() }
            conns.remove(device.id)
            l.value = LinkState.DISCONNECTED
            throw e
        }
        if (!ok) {
            safe { gatt.disconnect() }
            safe { gatt.close() }
            conns.remove(device.id)
            l.value = LinkState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect(deviceId: String) {
        conns.remove(deviceId)?.let { c ->
            safe { c.gatt.disconnect() }
            safe { c.gatt.close() }
        }
        discoveredServices16.remove(deviceId)
        link(deviceId).value = LinkState.DISCONNECTED
    }

    override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)

    override fun notifications(deviceId: String): Flow<BleNotification> = notifyFlow(deviceId)

    /** 连接后发现的 16-bit 短码服务表(会话宿主 confirm 用); 未连接/未发现回空. */
    override fun discoveredService16s(deviceId: String): List<String> = discoveredServices16[deviceId] ?: emptyList()

    override fun negotiatedMtu(deviceId: String): Int = conns[deviceId]?.mtu ?: 23

    /**
     * 下行写: **逐帧串行 + 等 onCharacteristicWrite 再发下一帧**(写流控/背压).
     * 无流控时多包切片(如 OTA 一切片 17 帧 Write-Without-Response)背靠背发会溢出 GATT 写缓冲,
     * `writeCharacteristic` 返 BUSY 被静默丢——真机 ota3.log 实证: 单帧过, 17 帧全丢 → 设备看门狗 abort.
     */
    @SuppressLint("MissingPermission")
    override suspend fun write(deviceId: String, bytes: ByteArray, char16: String?) {
        val conn = conns[deviceId] ?: return
        // char16=null 写默认写特征(现状); 否则按 16-bit 短码寻址, 查无静默丢弃(同"未连接静默丢弃"契约).
        val ch = if (char16 == null) {
            conn.writeChar ?: return
        } else {
            conn.writeChars[extract16(char16) ?: return] ?: return
        }
        if (link(deviceId).value != LinkState.CONNECTED) return
        conn.writeLock.withLock {
            repeat(WRITE_MAX_ATTEMPTS) {
                val ack = CompletableDeferred<Int>()
                conn.writeAck = ack
                val accepted = startWrite(conn, ch, bytes)
                if (accepted) {
                    // 等发送完成信号作节流; 个别机型不回调 → 短超时兜底继续(退化为无流控但不卡死).
                    withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.await() }
                    conn.writeAck = null
                    return
                }
                conn.writeAck = null
                delay(WRITE_BUSY_BACKOFF_MS) // writeCharacteristic 返 BUSY(上一帧在飞)→ 稍等重试
            }
        }
    }

    /** 单次 writeCharacteristic; 写类型取 [Conn.writeType](默认 No-Response); 返回是否被 GATT 接受(false=BUSY/错误, 需重试). */
    @SuppressLint("MissingPermission")
    private fun startWrite(conn: Conn, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean = safe {
        if (Build.VERSION.SDK_INT >= 33) {
            conn.gatt.writeCharacteristic(ch, bytes, conn.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = conn.writeType
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            conn.gatt.writeCharacteristic(ch)
        }
    }

    /**
     * GattSpec 路径的多 Notify 串行订阅推进: 取队首特征 setNotification + 写 CCC, 等 onDescriptorWrite 回调再来续订;
     * 队列空 = 全部成功 -> 置 CONNECTED/ready.complete(true). CCC 写异步且同一刻只能一个在飞, 故必须串行.
     *
     * 通道可用性契约(特征存在性之外的一半): 本地通知注册失败/特征无 notify|indicate 属性/缺 CCC 描述符,
     * 任一都判连接失败——spec 声明的通道必须真正可收数据, 静默跳过=CONNECTED 却收不到任何响应.
     * CCC 值按特征属性选(notify/indicate), 探测路径(B2A FFE2/HRS 2A37 均为 notify)行为不变.
     */
    @SuppressLint("MissingPermission")
    private fun subscribeNext(conn: Conn, l: MutableStateFlow<LinkState>) {
        val ch = conn.pendingNotify.removeFirstOrNull()
        if (ch == null) {
            l.value = LinkState.CONNECTED
            conn.ready.complete(true)
            return
        }
        val cccValue = when {
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                conn.ready.complete(false)
                return
            }
        }
        if (!safe { conn.gatt.setCharacteristicNotification(ch, true) }) {
            conn.ready.complete(false)
            return
        }
        val ccc = ch.getDescriptor(CCC_UUID)
        if (ccc == null) {
            conn.ready.complete(false)
            return
        }
        val ok = safe { writeCcc(conn.gatt, ccc, cccValue) }
        if (!ok) conn.ready.complete(false)
    }

    /**
     * 写 CCC 使能(API 33+/≤32 分支); 返回是否被 GATT 接受. 探测/GattSpec 两路径共用同一时序.
     * [value] 按特征属性选 notify/indicate 使能值(探测路径固定 notify——B2A/HRS 均为 notify, 行为不变).
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCcc(
        gatt: BluetoothGatt,
        ccc: BluetoothGattDescriptor,
        value: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(ccc, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            ccc.value = value
            gatt.writeDescriptor(ccc)
        }

    private inline fun safe(block: () -> Any?): Boolean = try {
        val r = block()
        (r as? Boolean) ?: true
    } catch (e: SecurityException) {
        false
    }

    companion object {
        private const val TAG = "BtRealBle"
        // 现状探测路径(spec==null)的已知通道短码——B2A(RX+TX 同在即认定)/HRS 心率测量.
        // 声明式路径(spec!=null)由各 profile 的 GattSpec 提供通道, 不经这些常量; 探测路径是迁移期
        // 遗留兜底(设计 §3.4), 故这些 S7/HRS 短码在本层自持, 不再耦合 s7 包的识别工具(W5 去耦).
        private const val PROBE_B2A_TX_16 = "FFE2" // B2A 表->App Notify
        private const val PROBE_B2A_RX_16 = "FFE1" // B2A App->表 Write Without Response
        private const val PROBE_HRS_MEASURE_16 = "2A37" // 标准心率测量
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 15_000L
        // 写流控: 等 onCharacteristicWrite 的兜底超时(正常回调仅 ms 级, 此仅防个别机型不回调卡死)+ BUSY 重试
        private const val WRITE_ACK_TIMEOUT_MS = 300L
        private const val WRITE_MAX_ATTEMPTS = 8
        private const val WRITE_BUSY_BACKOFF_MS = 6L
    }
}
