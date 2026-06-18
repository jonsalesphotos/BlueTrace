package io.bluetrace.domain

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.ScannedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已连接登记的扁平限额（DUT≤3 + 参考≤1，D-V4-3）。纯 Kotlin，JVM 单测。 */
class ConnectionRegistryTest {

    private fun dut(i: Int) = ScannedDevice("dut$i", "BT-DUT-$i", "C4:7B:8D:0A:00:0$i", -50, DeviceKind.DUT)
    private fun ref() = ScannedDevice("ref", "Polar H10", "A0:9E:1A:55:0D:10", -60, DeviceKind.REFERENCE, PROFILE_HRS)

    @Test
    fun dutLimitedToThree_referenceToOne() {
        val r = ConnectionRegistry()
        repeat(3) { r.add(dut(it)) }
        assertEquals(3, r.dutCount())
        assertFalse("4th DUT must be blocked", r.canConnect(DeviceKind.DUT))
        assertTrue("reference still allowed", r.canConnect(DeviceKind.REFERENCE))

        r.add(ref())
        assertEquals(1, r.referenceCount())
        assertFalse("2nd reference must be blocked", r.canConnect(DeviceKind.REFERENCE))
        assertEquals(4, r.count())
    }

    @Test
    fun removingFreesSlot() {
        val r = ConnectionRegistry()
        repeat(3) { r.add(dut(it)) }
        assertFalse(r.canConnect(DeviceKind.DUT))
        r.remove("dut0")
        assertEquals(2, r.dutCount())
        assertTrue(r.canConnect(DeviceKind.DUT))
        assertFalse(r.isConnected("dut0"))
        assertTrue(r.isConnected("dut1"))
    }

    @Test
    fun addIsIdempotent() {
        val r = ConnectionRegistry()
        r.add(dut(1)); r.add(dut(1))
        assertEquals(1, r.count())
    }
}
