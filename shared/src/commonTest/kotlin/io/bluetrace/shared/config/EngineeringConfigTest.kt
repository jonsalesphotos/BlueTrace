package io.bluetrace.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineeringConfigTest {

    @Test
    fun defaults_areEngineeringBaseline() {
        val c = EngineeringConfig()
        assertEquals(true, c.export.rawdataByDate)
        assertEquals(60, c.ota.reconnectScanSeconds)
        assertEquals(30, c.ota.lowBatteryPct)
        assertEquals(7, c.log.appRetainDays)
    }

    @Test
    fun parse_partialJson_fillsDefaults() {
        val c = parseEngineeringConfig("""{"ota":{"lowBatteryPct":50}}""")!!
        assertEquals(50, c.ota.lowBatteryPct)
        assertEquals(60, c.ota.reconnectScanSeconds, "缺失字段回默认")
        assertEquals(true, c.export.rawdataByDate)
    }

    @Test
    fun parse_unknownKeys_ignored() {
        val c = parseEngineeringConfig("""{"future":{"x":1},"export":{"rawdataByDate":false}}""")!!
        assertEquals(false, c.export.rawdataByDate)
    }

    @Test
    fun parse_garbage_returnsNull() {
        assertNull(parseEngineeringConfig(""))
        assertNull(parseEngineeringConfig("not json"))
        assertNull(parseEngineeringConfig("""{"ota":{"reconnectScanSeconds":"abc"}}"""))
    }

    /** 产品硬门：回连扫描预算配置只能调大——低于 60s 的配置被钳回 60s。 */
    @Test
    fun reconnectScanMs_flooredAt60s() {
        assertEquals(60_000L, parseEngineeringConfig("""{"ota":{"reconnectScanSeconds":5}}""")!!.ota.reconnectScanMs)
        assertEquals(60_000L, OtaConfig(reconnectScanSeconds = 0).reconnectScanMs)
        assertEquals(90_000L, OtaConfig(reconnectScanSeconds = 90).reconnectScanMs)
    }

    @Test
    fun roundTrip_encodesDefaults() {
        val text = EngineeringConfig().toJsonText()
        assertTrue(text.contains("rawdataByDate"), "encodeDefaults: 落地文件应是完整清单")
        assertEquals(EngineeringConfig(), parseEngineeringConfig(text))
    }
}
