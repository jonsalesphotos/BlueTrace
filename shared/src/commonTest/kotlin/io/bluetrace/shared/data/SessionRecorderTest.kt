package io.bluetrace.shared.data

import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.SessionFileCategory
import io.bluetrace.shared.protocol.DecodedSample
import io.bluetrace.shared.protocol.MockPacketCodec
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRecorderTest {

    private fun read(fs: FakeFileSystem, path: okio.Path): String =
        fs.source(path).buffer().use { it.readUtf8() }

    @Test
    fun recordsRawHexCsvAndCombo_intoD6Layout() {
        val fs = FakeFileSystem()
        val layout = SessionLayout("/sessions/Wear_shb_20260521_153000_0427".toPath())
        val recorder = SessionRecorder(
            fileSystem = fs,
            layout = layout,
            enabledTypes = setOf(CollectType.PPG_G, CollectType.ACC), // ppg_ir 关
            gnssEnabled = false,
        )

        // 原始 HEX 行（dut.hexlog）
        val raw = MockPacketCodec.encode(DecodedStream.ACC, 1000, listOf(1, 2, 1003))
        val hex = recorder.recordRaw(DeviceKind.DUT, epochMs = 1779348600_123L, rawBytes = raw)
        assertEquals("7E 03 E8 03 00 00 03 01 00 02 00 EB 03", hex)

        // 解码样本：ppg_g(启用) / ppg_ir(未启用) / acc(启用) / hr(始终)
        recorder.recordSamples(
            listOf(
                DecodedSample(DecodedStream.PPG_G, 1000, 1779348600_123L, listOf(2048)),
                DecodedSample(DecodedStream.PPG_IR, 1000, 1779348600_123L, listOf(1500)),
                DecodedSample(DecodedStream.ACC, 1000, 1779348600_123L, listOf(1, 2, 1003)),
                DecodedSample(DecodedStream.HR, 1000, 1779348600_123L, listOf(72)),
            ),
        )
        recorder.recordLabel(1779348600_500L, "PIN", "rest baseline")
        val files = recorder.finalizeFiles()

        // raw hexlog 存在且内容是 "<epoch>: HEX"
        assertTrue(fs.exists(layout.rawHex(DeviceKind.DUT)))
        assertEquals("1779348600123: 7E 03 E8 03 00 00 03 01 00 02 00 EB 03\n", read(fs, layout.rawHex(DeviceKind.DUT)))

        // 启用路有 CSV，未启用 ppg_ir 无 CSV
        assertTrue(fs.exists(layout.csv("ppg_g")))
        assertTrue(fs.exists(layout.csv("acc")))
        assertTrue(fs.exists(layout.csv("hr")))      // HR 始终落
        assertTrue(!fs.exists(layout.csv("ppg_ir"))) // 未启用 → 不落
        assertTrue(fs.exists(layout.comboCsv))       // 组合包兼容 CSV

        // CSV header + 行格式 epoch_ms,device_ts_us,ch...
        val ppg = read(fs, layout.csv("ppg_g")).trim().lines()
        assertEquals("epoch_ms,device_ts_us,ppg", ppg[0])
        assertEquals("1779348600123,1000,2048", ppg[1])

        // 文件清单分类正确
        assertTrue(files.any { it.category == SessionFileCategory.RAW_HEX })
        assertTrue(files.any { it.category == SessionFileCategory.COMBO_CSV })
        assertEquals(1L, recorder.rawLineCount)
    }

    @Test
    fun gnssCsv_onlyWhenEnabled() {
        val fs = FakeFileSystem()
        val layout = SessionLayout("/s/x".toPath())
        val off = SessionRecorder(fs, layout, setOf(CollectType.ACC), gnssEnabled = false)
        off.recordGps(1, 31.0, 121.0, 10.0, 1.0, 5.0)
        off.finalizeFiles()
        assertTrue(!fs.exists(layout.gpsCsv))

        val fs2 = FakeFileSystem()
        val layout2 = SessionLayout("/s/y".toPath())
        val on = SessionRecorder(fs2, layout2, setOf(CollectType.ACC), gnssEnabled = true)
        on.recordGps(1, 31.0, 121.0, 10.0, 1.0, 5.0)
        on.finalizeFiles()
        assertTrue(fs2.exists(layout2.gpsCsv))
    }
}
