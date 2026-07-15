package io.bluetrace.shared.s7

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.ScannedDevice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class B2aDetectTest {

    private fun dev(
        name: String = "ANY-WATCH",
        profileId: String? = null,
        adv: List<String> = emptyList(),
    ) = ScannedDevice("id", name, "00:11:22:33:44:55", -50, DeviceKind.DUT, profileId, adv)

    // extract16 归一测试随函数迁至 io.bluetrace.shared.ble.Uuid16Test(W5 通用化).

    @Test
    fun matchesAdvertisement_byServiceUuid_noNameNeeded() {
        // 名称随意(不再是判据), 广播含 FFE0 即命中
        assertTrue(B2aDetect.matchesAdvertisement(dev(name = "XYZ-999", adv = listOf("180A", "FFE0"))))
        // 128-bit 全串同样命中
        assertTrue(B2aDetect.matchesAdvertisement(dev(adv = listOf("0000FFE0-3C17-D293-8E48-14FE2E4DA212"))))
    }

    @Test
    fun matchesAdvertisement_rejectsNonB2a() {
        // 标准心率带(180D)与无广播设备不命中
        assertFalse(B2aDetect.matchesAdvertisement(dev(name = "Polar H10", adv = listOf("180D"))))
        assertFalse(B2aDetect.matchesAdvertisement(dev(name = "SKG WATCH S7-FCC4"))) // 仅名称不作判据
    }

    @Test
    fun confirmByCharacteristics_needsBothRxTx() {
        val rx = "0000FFE1-3C17-D293-8E48-14FE2E4DA212"
        val tx = "0000FFE2-3C17-D293-8E48-14FE2E4DA212"
        assertTrue(B2aDetect.confirmByCharacteristics(listOf(rx, tx, "00002902-0000-1000-8000-00805F9B34FB")))
        assertFalse(B2aDetect.confirmByCharacteristics(listOf(rx))) // 缺 TX
        assertFalse(B2aDetect.confirmByCharacteristics(listOf("2A37"))) // HRS 特征
    }
}
