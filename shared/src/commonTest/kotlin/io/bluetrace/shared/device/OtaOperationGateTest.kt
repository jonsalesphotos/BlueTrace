package io.bluetrace.shared.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [OtaOperationGate] 所有权语义: 唯一 Lease + 过期释放 no-op(误释放防护) + 幂等. */
class OtaOperationGateTest {

    @Test
    fun acquire_release_roundTrip() {
        val gate = OtaOperationGate()
        val l = assertNotNull(gate.tryAcquire())
        assertTrue(gate.busy)
        assertNull(gate.tryAcquire(), "已被占时二次获取应失败")
        gate.release(l)
        assertEquals(false, gate.busy)
        assertNotNull(gate.tryAcquire(), "释放后应可再获取")
    }

    /** 误释放防护核心: 旧持有者的延迟释放(如停止善后晚到)不得清掉新持有者的租约. */
    @Test
    fun staleRelease_isNoOp_doesNotClearNewOwner() {
        val gate = OtaOperationGate()
        val old = assertNotNull(gate.tryAcquire())
        gate.release(old) // 旧轮自然结束
        val fresh = assertNotNull(gate.tryAcquire()) // 新轮开始
        gate.release(old) // 旧轮善后 finally 的延迟释放(过期 token)
        assertTrue(gate.busy, "过期释放必须是 no-op, 不得清掉新持有者")
        assertEquals(fresh, gate.owner.value)
        gate.release(fresh)
        assertEquals(false, gate.busy)
    }

    @Test
    fun release_isIdempotent() {
        val gate = OtaOperationGate()
        val l = assertNotNull(gate.tryAcquire())
        gate.release(l)
        gate.release(l) // 重复释放无害
        assertEquals(false, gate.busy)
    }
}
