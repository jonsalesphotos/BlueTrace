package io.bluetrace.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTest {

    @Test
    fun epochToLocalParts_shanghai_knownInstant() {
        // 2026-05-21 15:30:00 +08:00 == 2026-05-21 07:30:00 UTC == 1779348600 unix seconds
        val epochMs = 1779348600_000L
        val parts = epochMsToLocalParts(epochMs, 8 * 3600)
        assertEquals(2026, parts.year)
        assertEquals(5, parts.month)
        assertEquals(21, parts.day)
        assertEquals(15, parts.hour)
        assertEquals(30, parts.minute)
        assertEquals(0, parts.second)
        assertEquals("20260521", parts.dateCompact())
        assertEquals("153000", parts.timeCompact())
        assertEquals("20260521_153000", parts.compact())
    }

    @Test
    fun epochToLocalParts_utc_epochZero() {
        val parts = epochMsToLocalParts(0L, 0)
        assertEquals(1970, parts.year)
        assertEquals(1, parts.month)
        assertEquals(1, parts.day)
        assertEquals(0, parts.hour)
    }

    @Test
    fun durationFormat() {
        assertEquals("00:00:00", formatDurationHms(0))
        assertEquals("00:02:14", formatDurationHms((2 * 60 + 14) * 1000L))
        assertEquals("01:00:00", formatDurationHms(3600_000L))
        assertEquals("00:00:00", formatDurationHms(-5))
    }
}
