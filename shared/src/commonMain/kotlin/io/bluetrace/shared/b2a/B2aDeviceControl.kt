package io.bluetrace.shared.b2a

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.BatteryOps
import io.bluetrace.shared.device.ControlPlaneFactory
import io.bluetrace.shared.device.DeviceCmdInfo
import io.bluetrace.shared.device.DeviceCommandException
import io.bluetrace.shared.device.DeviceCommandFailure
import io.bluetrace.shared.device.DeviceControl
import io.bluetrace.shared.device.DeviceInfoOps
import io.bluetrace.shared.device.LogOps
import io.bluetrace.shared.device.PowerOps
import io.bluetrace.shared.device.TimeSyncOps
import io.bluetrace.shared.device.VendorOps
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * S7 厂商扩展面(设计 V2 §3.2): B2A 专属操作——通用六分面之外的 S7 工程细节.
 * UI 的 S7 专属块经 `vendor as? B2aVendorOps` 消费(受限向下转型, 只在 S7 专属 UI 块内).
 * 类型沿用 [B2aDateTime]/[B2aPerson]/[B2aSnapshot] 等——vendor 面本就是 S7 专属.
 *
 * 通用面(info/battery/timeSync/logs/power)承载通用操作; 本面补齐 S7 独有的富信息与操作:
 * SN/IMEI/ICCID, 功能掩码, 绑定态, 设备时间, 用户信息, 找表, 恢复出厂(电源面只有重启/关机),
 * 心跳/操作日志流. **均为 [B2aConsole] 的薄委托, 不改 B2aConsole 本体.**
 *
 * 失败一律经 [mapS7] 转 [io.bluetrace.shared.device.DeviceCommandException](与通用面同一异常类型),
 * 故消费方(UI)不接触任何 S7 异常类型.
 */
interface B2aVendorOps : VendorOps {
    /** 30s 周期心跳(seq/电量), 连接态由设备主动上报; UI 头卡展示. */
    val heartbeat: StateFlow<B2aHeartbeat?>

    /** 控制台操作日志(TX/RX 面板); replay 缓冲, 回屏可见近况. */
    val opLog: SharedFlow<B2aOpLine>

    /** 读 SN 信息(型号 / SN / MAC / IMEI / ICCID)——通用 info 面不含这些. */
    suspend fun snInfo(): B2aSnInfo

    /** 读电量(含电压 / 容量)——通用 battery 面只给百分比. */
    suspend fun battery(): B2aBattery

    /** 读功能掩码 u32(逐位含义文档缺失, 仅 hex 展示). */
    suspend fun devFunc(): Long

    /** 读绑定态. */
    suspend fun bondState(): Int

    /** 读设备当前时间(通用 timeSync 面只 sync 不回读; 展示设备时间 / 偏差用). */
    suspend fun dateTime(): B2aDateTime

    /** 设备时间与手机时间偏差秒数(正=设备快); 纯计算, 无 BLE. */
    fun driftSeconds(dt: B2aDateTime): Long

    /** SET 任意日期时间(自定义对时, 测跨时区 / 过零点); 返回读回的设备时间. */
    suspend fun setDateTime(dt: B2aDateTime): B2aDateTime

    /** 读用户信息(身高 / 体重 / 性别 / 生日). */
    suspend fun getPerson(): B2aPerson

    /** 写用户信息. */
    suspend fun setPerson(person: B2aPerson)

    /** 找表(start=true 开始响铃找表, false 停止). */
    suspend fun findWatch(start: Boolean)

    /**
     * 恢复出厂(S7 独有的第三电源命令; 通用 [io.bluetrace.shared.device.PowerOps] 只有重启/关机).
     * 语义同电源面 = "已发送 + 尽力确认"(固件不回包, 以断链为成功旁证).
     */
    suspend fun restore(): Boolean
}

/**
 * S7 控制面(设计 V2 §3.2): 把现有 [B2aConsole] 包装成六通用分面 + [B2aVendorOps].
 * [B2aConsole] **原样保留不改**; 本类只做**语义映射 + 失败转换**——
 * [B2aCommandException] -> [DeviceCommandException], codeName 在此构造点由 [B2aFailure.DeviceError.name]
 * 解析(通用层零 S7 码表依赖).S7 六通用面全有(故均非空).
 */
class B2aDeviceControl(private val console: B2aConsole) : DeviceControl {

    override val info: DeviceInfoOps = object : DeviceInfoOps {
        override suspend fun get(): DeviceCmdInfo = mapS7 {
            val di = console.getDeviceInfo()
            DeviceCmdInfo(
                swVer = di.swVer,
                hwVer = null, // S7 版本帧无独立硬件版本字段
                model = null, // 型号 / SN 走 vendor / SnInfo, 不占通用三段
                extras = mapOf(
                    "modemVer" to di.modemVer,
                    "secBlVer" to di.secBlVer,
                    "bpVer" to di.bpVer,
                ),
            )
        }
    }

    override val battery: BatteryOps = object : BatteryOps {
        override suspend fun percent(): Int = mapS7 { console.getBattery().percent }
    }

    override val timeSync: TimeSyncOps = object : TimeSyncOps {
        override suspend fun sync() {
            mapS7 { console.syncTime() }
        }
    }

    override val logs: LogOps = object : LogOps {
        override suspend fun pull(onProgress: (chunks: Int, bytes: Int) -> Unit): ByteArray =
            mapS7 { console.pullLog { p -> onProgress(p.chunks, p.bytes) } }
    }

    override val power: PowerOps = object : PowerOps {
        // "已发送 + 尽力确认": S7 电源命令固件不回包, 以断链为成功旁证(见 B2aConsole.sendPowerCommand).
        override suspend fun reboot(): Boolean = mapS7 { console.sendPowerCommand(B2a.CTRL_RESET) }
        override suspend fun powerOff(): Boolean = mapS7 { console.sendPowerCommand(B2a.CTRL_POWER_OFF) }
    }

    override val vendor: B2aVendorOps = object : B2aVendorOps {
        override val heartbeat get() = console.heartbeat
        override val opLog get() = console.opLog
        override suspend fun snInfo(): B2aSnInfo = mapS7 { console.getSnInfo() }
        override suspend fun battery(): B2aBattery = mapS7 { console.getBattery() }
        override suspend fun devFunc(): Long = mapS7 { console.getDevFunc() }
        override suspend fun bondState(): Int = mapS7 { console.getBondState() }
        override suspend fun dateTime(): B2aDateTime = mapS7 { console.getDateTime() }
        override fun driftSeconds(dt: B2aDateTime): Long = console.driftSeconds(dt)
        override suspend fun setDateTime(dt: B2aDateTime): B2aDateTime = mapS7 { console.setDateTime(dt) }
        override suspend fun getPerson(): B2aPerson = mapS7 { console.getPerson() }
        override suspend fun setPerson(person: B2aPerson) {
            mapS7 { console.setPerson(person) }
        }
        override suspend fun findWatch(start: Boolean) {
            mapS7 { console.findWatch(start) }
        }
        override suspend fun restore(): Boolean = mapS7 { console.sendPowerCommand(B2a.CTRL_RESTORE) }
    }

    /** close = 停 S7 会话(停订阅 / 清分片缓冲); 断链归会话宿主. */
    override fun close() = console.stop()
}

/** [B2aCommandException] -> [DeviceCommandException]: codeName 在此解析(通用层零码表). */
private fun B2aCommandException.toDeviceCommand(): DeviceCommandException =
    DeviceCommandException(
        when (val f = failure) {
            B2aFailure.Timeout -> DeviceCommandFailure.Timeout
            is B2aFailure.DeviceError -> DeviceCommandFailure.DeviceError(f.code, f.name)
        },
    )

/** 包装 B2aConsole 调用: 把 [B2aCommandException] 统一转成通用 [DeviceCommandException]. */
private suspend fun <T> mapS7(block: suspend () -> T): T =
    try {
        block()
    } catch (e: B2aCommandException) {
        throw e.toDeviceCommand()
    }

/**
 * S7 控制面工厂: 造 [B2aConsole](start 订阅), 包成 [B2aDeviceControl].挂在 [B2aDeviceProfile.controlPlane].
 * 会话宿主连接 + 确认后调 [create]; 归还时 [DeviceControl.close] 停会话.
 */
class B2aControlPlaneFactory : ControlPlaneFactory {
    override fun create(
        ble: BleClient,
        deviceId: String,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
    ): DeviceControl {
        val console = B2aConsole(ble, deviceId, scope, clock, zone)
        console.start()
        return B2aDeviceControl(console)
    }
}
