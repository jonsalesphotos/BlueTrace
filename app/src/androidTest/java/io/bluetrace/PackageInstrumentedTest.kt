package io.bluetrace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 基础仪器化测试：确认正式包名（v2 包名重命名）。 */
@RunWith(AndroidJUnit4::class)
class PackageInstrumentedTest {
    @Test
    fun usesOfficialPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.bluetrace", context.packageName)
    }
}
