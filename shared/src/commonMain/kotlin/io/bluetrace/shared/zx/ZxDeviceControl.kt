package io.bluetrace.shared.zx

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.device.BatteryOps
import io.bluetrace.shared.device.ControlPlaneFactory
import io.bluetrace.shared.device.DeviceCmdInfo
import io.bluetrace.shared.device.DeviceControl
import io.bluetrace.shared.device.DeviceInfoOps
import io.bluetrace.shared.device.LogOps
import io.bluetrace.shared.device.PowerOps
import io.bluetrace.shared.device.TimeSyncOps
import io.bluetrace.shared.device.VendorOps
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import kotlinx.coroutines.CoroutineScope

/**
 * ZX 控制面(W6 异构验收 Mock 实现, 设计 V2 §3.2): 与 S7 六分面对照——**故意缺 [logs]**(设计文档
 * Open Questions #4 判据: 控制台日志块应因此自动隐藏)与 [vendor](非 marker 面, 走通用分面块渲染——
 * W5 交接备注指出的"通用分面读路径"由本协议首次验证, 而非只走 S7 vendor 富信息路径).
 *
 * info/battery/timeSync/power 四面直接返回内存假数据. 本协议无真实设备也无编解码器, 与 S7 包装
 * 真实 [io.bluetrace.shared.b2a.B2aConsole] 的实现形态不同——协议实现内部怎么产出数据是协议私有细节,
 * 框架([io.bluetrace.shared.device.DeviceControl]/[ControlPlaneFactory])对此零假设.
 *
 * 不经 [BleClient] 做任何真实收发: [io.bluetrace.shared.ble.mock.MockBleClient] 的命令路由是
 * S7 专属分支(其余 Mock 设备写入本就"静默丢弃", 同真实 GATT 对未订阅特征写入 no-op)——本类直接以
 * 内存假数据承担协议实现职责, 不需要框架为"第二个协议"新增任何模拟应答器.
 */
class ZxDeviceControl : DeviceControl {

    override val info: DeviceInfoOps = object : DeviceInfoOps {
        override suspend fun get(): DeviceCmdInfo = DeviceCmdInfo(
            swVer = "zx-2.0",
            hwVer = "zx-hw-1",
            model = "ZX-FAKE",
        )
    }

    override val battery: BatteryOps = object : BatteryOps {
        override suspend fun percent(): Int = 88
    }

    override val timeSync: TimeSyncOps = object : TimeSyncOps {
        override suspend fun sync() {
            // 假协议: 无真实设备时钟可写, "已发送"语义(no-op 即完成, 不抛异常).
        }
    }

    /** 故意缺: W6 判据——控制台日志块应据 logs==null 自动隐藏. */
    override val logs: LogOps? = null

    override val power: PowerOps = object : PowerOps {
        override suspend fun reboot(): Boolean = true
        override suspend fun powerOff(): Boolean = true
    }

    /** 无厂商扩展面: 走通用分面块渲染路径(非"vendor as? XxxVendorOps"专属块). */
    override val vendor: VendorOps? = null

    /** 无会话/订阅资源占用(内存假数据), 无需清理. */
    override fun close() = Unit
}

/**
 * ZX 控制面工厂: 挂在 [ZxDeviceProfile.controlPlane]. 签名与
 * [io.bluetrace.shared.b2a.B2aControlPlaneFactory] 一致但 [ble]/[deviceId]/[scope]/[clock]/[zone]
 * 均未使用——证明该工厂签名不偏向任何协议(S7 用得上 ble 做真实收发, ZX 用不上纯内存假数据,
 * 框架侧同一签名两者都能装, 未用到的能力不强加实现负担).
 */
class ZxControlPlaneFactory : ControlPlaneFactory {
    override fun create(
        ble: BleClient,
        deviceId: String,
        scope: CoroutineScope,
        clock: EpochClock,
        zone: TimeZoneProvider,
    ): DeviceControl = ZxDeviceControl()
}
