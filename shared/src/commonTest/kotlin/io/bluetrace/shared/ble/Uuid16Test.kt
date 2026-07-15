package io.bluetrace.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 通用 [extract16] 归一测试(W5 自 s7.B2aDetect 迁通用 ble 层, 断言随迁不丢).
 */
class Uuid16Test {

    @Test
    fun extract16_normalizesAllForms() {
        assertEquals("FFE0", extract16("ffe0"))
        assertEquals("FFE0", extract16("FFE0"))
        // 标准 SIG base 128-bit
        assertEquals("FFE0", extract16("0000FFE0-0000-1000-8000-00805F9B34FB"))
        // S7 私有 base 128-bit
        assertEquals("FFE1", extract16("0000FFE1-3C17-D293-8E48-14FE2E4DA212"))
        assertEquals(null, extract16("18"))
    }
}
