package io.bluetrace.shared.device

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.PROFILE_B2A
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.MockBleProfile
import io.bluetrace.shared.b2a.B2aDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProfileCatalogTest {

    private fun dev(
        id: String = "d",
        name: String = "X",
        adv: List<String> = emptyList(),
        kind: DeviceKind = DeviceKind.DUT,
        profileId: String? = null,
    ) = ScannedDevice(id, name, "00:11:22:33:44:55", -50, kind, profileId, adv)

    // 真实后端目录: [S7, HRS](顺序即识别优先级)
    private val realCatalog = DeviceProfileCatalog(listOf(B2aDeviceProfile(), HrsDeviceProfile()))

    // Mock 后端目录: 唯一 catch-all
    private val mockCatalog = DeviceProfileCatalog(listOf(MockDeviceProfile()))

    // ---- identify ----

    @Test
    fun `identify S7 by advertised FFE0`() {
        val p = realCatalog.identify(dev(adv = listOf("180A", "FFE0")))
        assertEquals(PROFILE_B2A, p?.profileId)
        assertEquals(DeviceKind.DUT, p?.kind)
    }

    @Test
    fun `identify HRS by advertised 180D`() {
        val p = realCatalog.identify(dev(adv = listOf("180D")))
        assertEquals(PROFILE_HRS, p?.profileId)
        assertEquals(DeviceKind.REFERENCE, p?.kind)
    }

    @Test
    fun `identify unknown advertisement returns null`() {
        assertNull(realCatalog.identify(dev(adv = listOf("1234"))))
        assertNull(realCatalog.identify(dev(adv = emptyList())))
    }

    @Test
    fun `identify order is priority - S7 before HRS when both services present`() {
        // 顺序即优先级: 首个 matches 命中. 同时含 FFE0 与 180D 时 [S7,HRS] 取 S7.
        val p = realCatalog.identify(dev(adv = listOf("180D", "FFE0")))
        assertEquals(PROFILE_B2A, p?.profileId)
    }

    @Test
    fun `identify catch-all Mock matches anything`() {
        assertEquals(MockBleProfile.ID, mockCatalog.identify(dev(adv = emptyList()))?.profileId)
        assertEquals(MockBleProfile.ID, mockCatalog.identify(dev(adv = listOf("FFE0")))?.profileId)
    }

    // ---- byId ----

    @Test
    fun `byId looks up by stable id`() {
        assertEquals(PROFILE_B2A, realCatalog.byId(PROFILE_B2A)?.profileId)
        assertEquals(PROFILE_HRS, realCatalog.byId(PROFILE_HRS)?.profileId)
        assertNull(realCatalog.byId("nope"))
    }

    // ---- toProtocolRegistry (解码识别真源唯一化) ----

    @Test
    fun `toProtocolRegistry collects only non-null dataPlanes (real = HRS, S7 skipped)`() {
        val reg = realCatalog.toProtocolRegistry()
        // B2a.dataPlane=null 被跳过, 只留 HRS(与旧 DI 真实后端 listOf(HrsProfile()) 等价)
        assertEquals(listOf(PROFILE_HRS), reg.all.map { it.id })
        assertEquals(PROFILE_HRS, reg.byId(PROFILE_HRS)?.id)
        assertNull(reg.byId(PROFILE_B2A))
    }

    @Test
    fun `toProtocolRegistry for Mock backend yields MockBleProfile`() {
        val reg = mockCatalog.toProtocolRegistry()
        assertEquals(listOf(MockBleProfile.ID), reg.all.map { it.id })
    }

    // ---- confirm 默认逻辑(本波只落默认实现+单测, 运行时接线归 W3) ----

    @Test
    fun `confirm default checks discovered services contain gatt service`() {
        val s7 = B2aDeviceProfile()
        assertTrue(s7.confirm(listOf("180A", "FFE0"))) // 含 FFE0
        assertFalse(s7.confirm(listOf("180A", "180D"))) // 不含 FFE0 -> 识别撤销

        val hrs = HrsDeviceProfile()
        assertTrue(hrs.confirm(listOf("180D")))
        assertFalse(hrs.confirm(listOf("FFE0")))
    }

    // ---- annotate 打标(扫描去识别化后统一识别) ----

    @Test
    fun `annotate stamps raw HRS device to REFERENCE with profileId`() {
        // 原始上报(真实客户端): profileId=null, kind=DUT
        val a = realCatalog.annotate(dev(adv = listOf("180D")))
        assertEquals(PROFILE_HRS, a.profileId)
        assertEquals(DeviceKind.REFERENCE, a.kind) // "HRS 设备 -> REFERENCE"
    }

    @Test
    fun `annotate stamps raw S7 device to DUT with profileId`() {
        val a = realCatalog.annotate(dev(adv = listOf("180A", "FFE0")))
        assertEquals(PROFILE_B2A, a.profileId)
        assertEquals(DeviceKind.DUT, a.kind) // "S7 -> DUT"
    }

    @Test
    fun `annotate leaves unknown device unchanged`() {
        val raw = dev(adv = listOf("1234"))
        assertEquals(raw, realCatalog.annotate(raw)) // 无命中: 原样(kind=DUT/profileId=null)
    }

    @Test
    fun `annotate preserves Mock roster identity (guard - catch-all does not flatten)`() {
        // Mock 目录只有 catch-all(matches 恒真); 已带身份的 roster 设备不被压平为 Mock.ID/DUT
        val polar = dev(id = "ref-h10", adv = listOf("180D"), kind = DeviceKind.REFERENCE, profileId = PROFILE_HRS)
        val ap = mockCatalog.annotate(polar)
        assertEquals(PROFILE_HRS, ap.profileId)
        assertEquals(DeviceKind.REFERENCE, ap.kind) // 未被 MockDeviceProfile.kind=DUT 覆盖

        val s7 = dev(id = "s7", adv = listOf("FFE0"), kind = DeviceKind.DUT, profileId = PROFILE_B2A)
        assertEquals(PROFILE_B2A, mockCatalog.annotate(s7).profileId)
    }

    @Test
    fun `annotate stamps plain Mock DUT via catch-all`() {
        // profileId=null 的 plain DUT: Mock catch-all 打上 Mock.ID, kind 仍 DUT
        val plain = dev(id = "dut-0427", adv = emptyList(), kind = DeviceKind.DUT, profileId = null)
        val a = mockCatalog.annotate(plain)
        assertEquals(MockBleProfile.ID, a.profileId)
        assertEquals(DeviceKind.DUT, a.kind)
    }
}
