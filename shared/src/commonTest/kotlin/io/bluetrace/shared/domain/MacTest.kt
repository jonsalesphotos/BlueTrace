package io.bluetrace.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MAC 身份规则(2026-07-16 硬约束)的规范化/格式化测试:
 * 内部规范形 = 大写 hex12 无分隔; 显示才用冒号; 解析失败返回 null(不猜)。
 */
class MacTest {

    @Test
    fun normalize_acceptsCommonForms_toUppercaseHex12() {
        assertEquals("C8D3E0C1D8F7", normalizeMac("C8:D3:E0:C1:D8:F7"))
        assertEquals("C8D3E0C1D8F7", normalizeMac("c8:d3:e0:c1:d8:f7"))
        assertEquals("C8D3E0C1D8F7", normalizeMac("C8-D3-E0-C1-D8-F7"))
        assertEquals("C8D3E0C1D8F7", normalizeMac("c8d3e0c1d8f7"))
        assertEquals("C8D3E0C1D8F7", normalizeMac(" C8 D3 E0 C1 D8 F7 "))
    }

    @Test
    fun normalize_isIdempotent() {
        val once = normalizeMac("c8:d3:e0:c1:d8:f7")!!
        assertEquals(once, normalizeMac(once))
    }

    @Test
    fun normalize_rejectsGarbage_withNull() {
        assertNull(normalizeMac(""), "空串")
        assertNull(normalizeMac("s7-fcc4"), "Mock 合成 id 不是 MAC")
        assertNull(normalizeMac("C8:D3:E0:C1:D8"), "10 位不够")
        assertNull(normalizeMac("C8:D3:E0:C1:D8:F7:AA"), "14 位过长")
        assertNull(normalizeMac("G8:D3:E0:C1:D8:F7"), "非 hex 字符")
    }

    @Test
    fun formatColon_roundTrip() {
        assertEquals("C8:D3:E0:C1:D8:F7", formatMacColon("C8D3E0C1D8F7"))
        assertEquals("C8D3E0C1D8F7", normalizeMac(formatMacColon("C8D3E0C1D8F7")))
        // 防御: 非 12 位输入原样返回
        assertEquals("weird", formatMacColon("weird"))
    }
}
