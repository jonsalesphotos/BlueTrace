package io.bluetrace.data.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.PROFILE_S7
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.s7.B2aDetect
import io.bluetrace.shared.util.EpochClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真实 Android BLE Central（v1 精简版，真机联调用）。
 *
 * 范围（够控制台 + HRS 参考即可，复杂能力交给后续 Nordic Kotlin-BLE 替换，D-V4-19）：
 * - 扫描：无过滤全量扫描，提取广播 Service UUID 表（协议识别靠 [B2aDetect]，nRF Connect 式）；
 * - 连接：connectGatt(TRANSPORT_LE) → requestMtu(247) → discoverServices → 订阅已知协议的 Notify
 *   特征（B2A: FFE2；HRS: 2A37）→ CCC 写成功才算 CONNECTED；
 * - 写：对 B2A RX（FFE1）Write Without Response（上层 S7Console 单飞保证串行，无需写队列）；
 * - 断链：不自动重连（v1；断了就 DISCONNECTED，控制台按钮自动禁用）。
 *
 * 所有蓝牙调用包 SecurityException（权限可被运行期收回）。
 */
class AndroidBleClient(
    private val context: Context,
    private val clock: EpochClock,
) : BleClient {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private class Conn(
        val gatt: BluetoothGatt,
        var writeChar: BluetoothGattCharacteristic? = null,
        val ready: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val conns = ConcurrentHashMap<String, Conn>()
    private val links = ConcurrentHashMap<String, MutableStateFlow<LinkState>>()

    // 设备级持久通知流（与连接生命周期解耦：订阅可先于 connect——console attach 早于连接完成）
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
                // 2=REGISTRATION_FAILED 5=SCANNING_TOO_FREQUENTLY（Android 静默节流的显式出口）
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
        // nRF Connect 式全量展示：无名设备用占位名（便于确认周围广播活性、按 MAC 定位目标）
        val name = scanRecord?.deviceName ?: device?.name ?: "(unnamed)"
        val adv = scanRecord?.serviceUuids?.mapNotNull { B2aDetect.extract16(it.uuid.toString()) } ?: emptyList()
        val profile = when {
            adv.contains(B2aDetect.SERVICE_16) -> PROFILE_S7
            adv.contains("180D") -> PROFILE_HRS
            else -> null
        }
        ScannedDevice(
            id = addr,
            name = name,
            address = addr,
            rssi = rssi,
            kind = if (profile == PROFILE_HRS) DeviceKind.REFERENCE else DeviceKind.DUT,
            profileId = profile,
            advertisedServices = adv,
        )
    } catch (e: SecurityException) {
        null
    }

    // ---- 连接 / 订阅 ----

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: ScannedDevice) {
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
                        // 先协商 MTU（目标 247，SPEC：App 只消费协商结果）；失败也会回调，届时再发现服务
                        if (!safe { gatt.requestMtu(247) }) safe { gatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        l.value = LinkState.DISCONNECTED
                        conn.ready.complete(false)
                        safe { gatt.close() }
                        conns.remove(device.id)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                safe { gatt.discoverServices() }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    conn.ready.complete(false)
                    return
                }
                val chars = gatt.services.flatMap { it.characteristics }
                val byShort = chars.groupBy { B2aDetect.extract16(it.uuid.toString()) }
                // B2A 优先（连接后确认策略：RX+TX 同在即认定，spec §1）；否则 HRS
                val b2aTx = byShort[B2aDetect.TX_16]?.firstOrNull()
                val b2aRx = byShort[B2aDetect.RX_16]?.firstOrNull()
                val hrsMeasure = byShort["2A37"]?.firstOrNull()
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
                    // 无 CCC 描述符的非常规实现：直接视为就绪
                    l.value = LinkState.CONNECTED
                    conn.ready.complete(true)
                    return
                }
                val ok = safe {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(ccc)
                    }
                }
                if (!ok) conn.ready.complete(false)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCC_UUID) {
                    val ok = status == BluetoothGatt.GATT_SUCCESS
                    if (ok) l.value = LinkState.CONNECTED
                    conn.ready.complete(ok)
                }
            }

            // API 33+
            override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                notifyFlow(device.id).tryEmit(BleNotification(device.id, clock.nowMs(), value))
            }

            // API ≤32
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) {
                    val v = ch.value ?: return
                    notifyFlow(device.id).tryEmit(BleNotification(device.id, clock.nowMs(), v))
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
            // 调用方协程被取消（如连接页返回销毁 VM）：必须清理 GATT，
            // 否则泄漏的连接占住设备（设备停止广播、无法再连）——真机实证过的坑。
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
        link(deviceId).value = LinkState.DISCONNECTED
    }

    override fun linkState(deviceId: String): StateFlow<LinkState> = link(deviceId)

    override fun notifications(deviceId: String): Flow<BleNotification> = notifyFlow(deviceId)

    @SuppressLint("MissingPermission")
    override suspend fun write(deviceId: String, bytes: ByteArray) {
        val conn = conns[deviceId] ?: return
        val ch = conn.writeChar ?: return
        if (link(deviceId).value != LinkState.CONNECTED) return
        safe {
            if (Build.VERSION.SDK_INT >= 33) {
                conn.gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                conn.gatt.writeCharacteristic(ch)
            }
        }
    }

    private inline fun safe(block: () -> Any?): Boolean = try {
        val r = block()
        (r as? Boolean) ?: true
    } catch (e: SecurityException) {
        false
    }

    companion object {
        private const val TAG = "BtRealBle"
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
