package io.bluetrace.shared.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * OTA 操作租约(app 级单例): 全 app 同一时刻至多一个 OTA 运行**或其停止善后**在飞.
 *
 * 单实例内的 stopping 门(MultiOtaController/OtaTestViewModel 各自持有)挡不住**跨实例**的新一轮:
 * 单/多设备两屏各有编排器, "中止并离开"退屏后重进又是全新 VM 实例——旧善后跑在 app scope 上
 * (含尽力重启指令与断开, 断链观测窗最长 10s+), 期间从另一实例开始新一轮会被旧善后误伤
 * (对同一设备补发复位/断开). 本租约由两屏编排与重建后的实例共享(Koin single):
 * 开始前 [tryAcquire], 运行自然结束或停止善后**完全结束**后 [release].
 */
class OtaOperationGate {
    private val _busy = MutableStateFlow(false)

    /** 占用中(运行中或停止善后中): UI 据此禁用两屏的开始/重试与模式切换. */
    val busy: StateFlow<Boolean> = _busy

    /** 尝试占用(原子 CAS): 已被占返回 false, 调用方不得开始新一轮. */
    fun tryAcquire(): Boolean = _busy.compareAndSet(expect = false, update = true)

    /** 释放: 运行自然结束, 或手动停止的善后完全结束后调. 幂等. */
    fun release() {
        _busy.value = false
    }
}
