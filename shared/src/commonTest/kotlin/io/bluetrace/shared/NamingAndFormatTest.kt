package io.bluetrace.shared

import io.bluetrace.shared.data.sessionFolderName
import io.bluetrace.shared.session.formatRelativeCenti
import kotlin.test.Test
import kotlin.test.assertEquals

class NamingAndFormatTest {

    @Test
    fun sessionFolderName_followsSpecPattern() {
        // <Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>（§6.1）
        val config = sessionConfig(listOf(dutAssigned()))
        assertEquals("Wear_shb_20260521_153000_0427", sessionFolderName(config))
    }

    @Test
    fun sessionFolderName_prefersDutShort_overReference() {
        val config = sessionConfig(listOf(refAssigned(), dutAssigned(addr = "C4:7B:8D:0A:99:AB")))
        // DUT 优先取短标识（MAC 末 4 位）
        assertEquals("Wear_shb_20260521_153000_99ab", sessionFolderName(config))
    }

    @Test
    fun relativeCenti_format() {
        assertEquals("00:00.00", formatRelativeCenti(0))
        assertEquals("02:14.92", formatRelativeCenti((2 * 60 + 14) * 1000L + 920))
        assertEquals("00:00.00", formatRelativeCenti(-100))
        assertEquals("10:00.00", formatRelativeCenti(10 * 60_000L))
    }
}
