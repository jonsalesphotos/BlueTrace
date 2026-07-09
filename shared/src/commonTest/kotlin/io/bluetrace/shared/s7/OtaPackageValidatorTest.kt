package io.bluetrace.shared.s7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaPackageValidatorTest {

    private fun e(name: String, size: Long = 100) = OtaEntryInfo(name, size)

    private val otaAll = listOf(
        e("fCheck.dat", 108), e("fCN26.dat", 154673), e("fCN34.dat", 1412389), e("fCN40.dat", 1986802),
        e("fNum48.dat", 47915), e("fNum64.dat", 82617), e("fNum72.dat", 103817), e("fNum80.dat", 126573),
        e("fNum96.dat", 178549), e("fNum120.dat", 287808),
        e("fw.dat", 1668744), e("ResCheck.dat", 4596), e("ResData.dat", 17899972), e("ResFat.dat", 3048),
    )
    private val otaPart = listOf(e("ResData.dat", 17899972), e("ResFat.dat", 3048), e("ResCheck.dat", 4596), e("fw.dat", 1668744))

    @Test
    fun otaAll_isValid_hasFonts_noWarnings() {
        val v = OtaPackageValidator.validate(otaAll)
        assertTrue(v.valid)
        assertTrue(v.hasFonts)
        assertTrue(v.errors.isEmpty())
        assertTrue(v.warnings.isEmpty())
        assertEquals(14, v.fileCount)
        assertEquals(otaAll.sumOf { it.size }, v.totalSize)
    }

    @Test
    fun otaPart_isValid_butWarnsNoFonts() {
        val v = OtaPackageValidator.validate(otaPart)
        assertTrue(v.valid, "四文件必推集应有效")
        assertFalse(v.hasFonts)
        assertTrue(v.warnings.any { it.contains("字库") }, "缺字库应软警告: ${v.warnings}")
    }

    /** O-1：仅 fw.dat → 有效 + O-1 警告（依赖设备已有 Res）。 */
    @Test
    fun fwOnly_isValid_withO1Warning() {
        val v = OtaPackageValidator.validate(listOf(e("fw.dat", 1668744)))
        assertTrue(v.valid, "仅 fw.dat 应有效(O-1)")
        assertFalse(v.hasFonts)
        assertTrue(v.warnings.any { it.contains("O-1") }, "应有 O-1 警告: ${v.warnings}")
    }

    /** 无 fw.dat 但有 Res → 有效 + 警告（仅刷资源）。 */
    @Test
    fun noFwButRes_isValid_withWarning() {
        val noFw = otaAll.filter { it.name != "fw.dat" }
        val v = OtaPackageValidator.validate(noFw)
        assertTrue(v.valid)
        assertTrue(v.warnings.any { it.contains("无 fw.dat") }, "应有无 fw 警告: ${v.warnings}")
    }

    /** 只有字库、无 fw 无 Res → 无效（非有效 OTA 包）。 */
    @Test
    fun nothingFlashable_isInvalid() {
        val v = OtaPackageValidator.validate(listOf(e("fCheck.dat", 108), e("fCN26.dat", 154673)))
        assertFalse(v.valid)
    }

    @Test
    fun nameTooLong_isInvalid() {
        val v = OtaPackageValidator.validate(otaPart + e("ResDataX.dat")) // 12? "ResDataX.dat"=12 ok; use 13
        assertTrue(v.valid) // 12 字符仍合法
        val v2 = OtaPackageValidator.validate(otaPart + e("ResData_XX.dat")) // 14 字符
        assertFalse(v2.valid)
        assertTrue("ResData_XX.dat" in v2.tooLongNames)
    }

    @Test
    fun emptyFile_isInvalid() {
        val v = OtaPackageValidator.validate(otaPart + e("Extra.dat", 0))
        assertFalse(v.valid)
        assertTrue("Extra.dat" in v.emptyFiles)
    }

    @Test
    fun emptyPackage_isInvalid() {
        val v = OtaPackageValidator.validate(emptyList())
        assertFalse(v.valid)
        assertEquals(0, v.fileCount)
    }

    @Test
    fun sortByPushOrder_matchesGoldenOrder() {
        // 乱序输入 → 排回 golden 顺序：字库 → fw → Res 三件套
        val shuffled = listOf("ResFat.dat", "fw.dat", "fCheck.dat", "ResData.dat", "fNum48.dat", "fCN26.dat", "ResCheck.dat")
        val sorted = OtaPackageValidator.sortByPushOrder(shuffled) { it }
        assertEquals(
            listOf("fCheck.dat", "fCN26.dat", "fNum48.dat", "fw.dat", "ResCheck.dat", "ResData.dat", "ResFat.dat"),
            sorted,
        )
    }

    @Test
    fun sortByPushOrder_unknownFilesAppendedInOrder() {
        val input = listOf("fw.dat", "unknownB.dat", "fCheck.dat", "unknownA.dat")
        val sorted = OtaPackageValidator.sortByPushOrder(input) { it }
        assertEquals(listOf("fCheck.dat", "fw.dat", "unknownB.dat", "unknownA.dat"), sorted)
    }
}
