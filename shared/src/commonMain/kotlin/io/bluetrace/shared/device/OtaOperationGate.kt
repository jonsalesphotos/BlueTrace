package io.bluetrace.shared.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * OTA 操作租约(app 级单例): 全 app 同一时刻至多一个 OTA 运行**或其停止善后**在飞.
 *
 * 单实例内的 stopping 门(MultiOtaController/OtaTestViewModel 各自持有)挡不住**跨实例**的新一轮:
 * 单/多设备两屏各有编排器, "中止并离开"退屏后重进又是全新 VM 实例——旧善后跑在 app scope 上
 * (含尽力重启指令与断开, 断链观测窗最长 10s+), 期间从另一实例开始新一轮会被旧善后误伤.
 *
 * **所有权模型(Lease token)**: [tryAcquire] 返回唯一 [Lease], [release] 只对**当前持有者**生效
 * (CAS 校验 token)——旧运行的延迟释放(如"停止恰逢自然结束"后善后再 release)不可能清掉新持有者的
 * 租约; 无 token 的布尔开关做不到这一点. 持有方职责:
 * - 运行自然结束 -> release(自己的 lease);
 * - 手动停止 -> lease **显式移交**给善后协程, 善后完全结束后 release;
 * - 非停止路径的取消(VM onCleared / controller.close) -> 兜底 release 未移交的 lease,
 *   否则租约永久占用(重进后任何实例都无法开始, 只能杀进程).
 */
class OtaOperationGate {

    /** 租约凭据: 仅由本 gate 发放; 持有它才有资格释放. */
    class Lease internal constructor()

    private val _owner = MutableStateFlow<Lease?>(null)

    /** 当前持有者(null=空闲). UI 层用 `owner.value != null` 或 map 出布尔. */
    val owner: StateFlow<Lease?> = _owner

    /** 占用中(运行中或停止善后中): UI 据此禁用两屏的开始/重试与模式切换. */
    val busy: Boolean get() = _owner.value != null

    /** 尝试占用(原子 CAS): 空闲则发放新租约, 已被占返回 null. */
    fun tryAcquire(): Lease? {
        val lease = Lease()
        return if (_owner.compareAndSet(null, lease)) lease else null
    }

    /** 释放: 仅当 [lease] 仍是当前持有者才生效(CAS)——过期租约的延迟释放是 no-op. 幂等. */
    fun release(lease: Lease) {
        _owner.compareAndSet(lease, null)
    }
}
