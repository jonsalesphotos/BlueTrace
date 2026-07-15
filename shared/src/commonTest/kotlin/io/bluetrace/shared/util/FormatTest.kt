package io.bluetrace.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Format 边界: formatMb 的 KB/MB 分档与 HALF_UP 舍入(语义等价原 String.format); formatFullStamp 补零 + 毫秒. */
class FormatTest {

    @Test
    fun formatMb_kbAndMbBoundaries() {
        assertEquals("0 KB", formatMb(0))
        assertEquals("0 KB", formatMb(1)) // 0.001 -> 0
        assertEquals("1 KB", formatMb(1234)) // 1.234 -> 1
        assertEquals("999 KB", formatMb(999_000)) // 999.0
        assertEquals("1.0 MB", formatMb(1_000_000)) // >=1MB 起用 MB
        assertEquals("1.5 MB", formatMb(1_500_000))
        assertEquals("2.5 MB", formatMb(2_500_000))
        assertEquals("19.6 MB", formatMb(19_600_000))
        assertEquals("24.0 MB", formatMb(24_000_000))
    }

    @Test
    fun formatFullStamp_padsAndAppendsMillis() {
        // epoch 0 (offset 0) = 1970-01-01 00:00:00.000: 验补零(单位数月/日/时 -> "01"/"00")
        assertEquals("1970-01-01 00:00:00.000", formatFullStamp(0L, 0))
        assertEquals("1970-01-01 00:00:00.123", formatFullStamp(123L, 0))
        assertEquals("1970-01-01 00:00:01.000", formatFullStamp(1000L, 0))
        // 1天 + 1h1m1s + 500ms
        assertEquals("1970-01-02 01:01:01.500", formatFullStamp(90_061_500L, 0))
    }
}
