package io.bluetrace.shared.device

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope

/**
 * 设备信息(通用三段 + 厂商扩展, 设计 V2 §3.2): 软件版本/硬件版本/型号可空(协议未必都提供);
 * [extras] 承载协议自有的额外字段(如 S7 的 modem/secBl/bp 组件版本), 通用 UI 只读三段, 详情走 extras/vendor.
 */
data class DeviceCmdInfo(
    val swVer: String?,
    val hwVer: String?,
    val model: String?,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * 一次控制命令的失败原因(通用层, **零协议码表依赖**):
 * [DeviceError.codeName] 在**协议实现构造异常时**解析(S7 = B2a.errorName[code]), 通用层不认识任何具体码.
 */
sealed interface DeviceCommandFailure {
    /** 超时: 命令发出后未在期限内收到应答. */
    data object Timeout : DeviceCommandFailure

    /** 设备回错误码; [codeName] 由协议实现在构造点解析后带入(通用层零码表). */
    data class DeviceError(val code: Int, val codeName: String) : DeviceCommandFailure

    /** 设备不支持该操作(分面模型下通常表现为分面 null; 保留此项供协议内部细分). */
    data object Unsupported : DeviceCommandFailure
}

/** 控制命令失败异常(分面方法统一抛此; 通用消费方按 [failure] 分支, 不接触任何协议异常类型). */
class DeviceCommandException(val failure: DeviceCommandFailure) : Exception(
    when (failure) {
        DeviceCommandFailure.Timeout -> "timeout"
        is DeviceCommandFailure.DeviceError -> "device error ${failure.codeName}"
        DeviceCommandFailure.Unsupported -> "unsupported"
    },
)

/** 设备信息面: 读版本/型号等. */
interface DeviceInfoOps {
    suspend fun get(): DeviceCmdInfo
}

/** 电量面: 读电量百分比(0..100). */
interface BatteryOps {
    suspend fun percent(): Int
}

/** 对时面: 用手机当前本地时间同步设备时钟. */
interface TimeSyncOps {
    suspend fun sync()
}

/** 日志面: 拉设备日志; [onProgress] 回传已收块数与字节数. */
interface LogOps {
    suspend fun pull(onProgress: (chunks: Int, bytes: Int) -> Unit = { _, _ -> }): ByteArray
}

/**
 * 电源面: 重启/关机.语义 = **"已发送 + 尽力确认"**——部分设备(如 S7)对电源命令固件强制不回包,
 * 只能以断链为成功旁证.返回 true=观测到设备断链(命令大概率生效); false=未观测到断链 / 链路非连接态拒发.
 * **不是**"设备保证执行完成"的强确认.
 */
interface PowerOps {
    suspend fun reboot(): Boolean
    suspend fun powerOff(): Boolean
}

/**
 * 厂商扩展面(空 marker): 通用层不认识其内容; UI 的厂商专属块经 `vendor as? XxxVendorOps` 消费——
 * 受限,语义正确的向下转型, 只发生在本就是该协议专属的 UI 块内(S7 见 `B2aVendorOps`).
 */
interface VendorOps

/**
 * 设备控制面(设计 V2 §3.2): **分面是否为 null 即能力是否存在**——无 capabilities 集合,
 * 无运行时 Unsupported 窗口,无"集合与方法不一致".通用 UI 只消费六个通用分面的存在性与语义操作;
 * 厂商专属块经 [vendor] 向下转型消费.
 */
interface DeviceControl {
    val info: DeviceInfoOps?
    val battery: BatteryOps?
    val timeSync: TimeSyncOps?
    val logs: LogOps?
    val power: PowerOps?
    val vendor: VendorOps?

    /** 释放控制面资源(S7 = 停订阅 / 停会话).**不负责断链**(断链归会话宿主 / 调用方). */
    fun close()
}

/**
 * 控制面工厂(W2 空 marker 转正, 设计 V2 §3.2): 由 profile 声明式提供, 会话宿主
 * [DeviceSessionManager] 连接 + 确认后调 [create] 造 [DeviceControl].协议实现在此包装其命令会话.
 */
interface ControlPlaneFactory {
    fun create(
        ble: BleClient,
        deviceId: String,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
    ): DeviceControl
}
