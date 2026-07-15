package io.bluetrace.shared.b2a

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.mock.MockBleClient
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_B2A
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class B2aConsoleTest {

    private fun s7Device() =
        ScannedDevice("s7-fcc4", "SKG WATCH S7-FCC4", io.bluetrace.shared.domain.TEST_DUT_MAC, -58, DeviceKind.DUT, PROFILE_B2A)

    private fun kotlinx.coroutines.test.TestScope.newConsole(mock: MockBleClient): B2aConsole =
        B2aConsole(
            ble = mock,
            deviceId = "s7-fcc4",
            scope = backgroundScope,
            clock = virtualClock { testScheduler.currentTime },
            zone = TestZone(),
        )

    @Test
    fun getDateTime_reflectsMockDrift_thenSyncTimeZeroesIt() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        val before = console.getDateTime()
        val driftBefore = console.driftSeconds(before)
        assertTrue(abs(driftBefore + 97) <= 2, "Mock 手表默认慢 97s，实测 drift=$driftBefore")

        val after = console.syncTime()
        val driftAfter = console.driftSeconds(after)
        assertTrue(abs(driftAfter) <= 2, "对时后偏差应归零，实测 drift=$driftAfter")
    }

    @Test
    fun setCustomDateTime_writesAndReadsBack() = runTest {
        // 自定义对时：写任意时间（测跨时区/过零点）→ Mock 手表回读一致
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        // 过零点边界 23:59:58 + 跨时区 tz=-5
        val target = B2aDateTime(2026, 12, 31, 23, 59, 58, week = 1, timezone = -5)
        val applied = console.setDateTime(target)
        assertEquals(2026, applied.year)
        assertEquals(12, applied.month)
        assertEquals(31, applied.day)
        assertEquals(23, applied.hour)
        assertEquals(59, applied.minute)
        assertEquals(58, applied.second)
        // week 由 y/m/d 自算：2026-12-31 是周四 → 4
        assertEquals(4, applied.week)
    }

    @Test
    fun readAllIdentityAndBattery() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        val info = console.getDeviceInfo()
        assertEquals("1.2.7.0", info.swVer)
        assertEquals("23", info.bpVer)

        val sn = console.getSnInfo()
        assertEquals("68 39 71 25 81", sn.devType) // device_type 码 hex 展示
        assertEquals("SN2407FCC4AB", sn.sn)
        assertEquals(io.bluetrace.shared.domain.TEST_DUT_MAC, sn.macHex)

        val bat = console.getBattery()
        assertEquals(82, bat.percent)
        assertEquals(4130, bat.voltageMv)

        assertEquals(0x0007035FL, console.getDevFunc())
        assertEquals(1, console.getBondState())
    }

    @Test
    fun person_writeThenReadBack() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        val target = B2aPerson(heightCm = 180, weightKg = 75, gender = 0, birthYear = 1990, birthMonth = 12, birthDay = 31)
        console.setPerson(target)
        assertEquals(target, console.getPerson())
    }

    @Test
    fun pullLog_assemblesChunks_untilIdleTimeout() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        var lastProgress: B2aLogPullProgress? = null
        val log = console.pullLog { lastProgress = it }
        val text = log.decodeToString()
        assertTrue(log.size > 2000, "日志应有实际内容，实际 ${log.size}B")
        assertTrue(text.contains("boot stage 0"), "应含首块内容")
        assertTrue(text.contains("eiotlog2.log (bak)"), "应含 bak 文件段")
        assertTrue(text.endsWith("temp=32C\n") || text.contains("[bak:23]"), "应含末块内容")
        assertEquals(true, lastProgress?.done)
        assertTrue((lastProgress?.chunks ?: 0) > 5, "应分多块回传")
    }

    @Test
    fun powerCommand_noReply_disconnectIsSuccess() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        val ok = console.sendPowerCommand(B2a.CTRL_RESET)
        assertTrue(ok, "Mock 手表应在 400ms 内断链")
        assertEquals(LinkState.DISCONNECTED, mock.linkState("s7-fcc4").value)
    }

    @Test
    fun powerCommand_notConnected_returnsFalseImmediately() = runTest {
        // TOCTOU 修复（评审 #4）：链路非 CONNECTED 时拒发，绝不把「本来就断着」判成命令生效
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        val console = newConsole(mock).also { it.start() }
        assertEquals(false, console.sendPowerCommand(B2a.CTRL_RESTORE))
    }

    @Test
    fun request_timesOut_whenNotConnected() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        // 不 connect：write 被静默丢弃（真实 GATT 同行为）→ 3s 超时
        val console = newConsole(mock).also { it.start() }
        val ex = assertFailsWith<B2aCommandException> { console.getBattery() }
        assertEquals(B2aFailure.Timeout, ex.failure)
    }

    @Test
    fun heartbeat_passivelyUpdatesState() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val console = newConsole(mock).also { it.start() }

        // 推进 30s 触发 Mock 心跳
        advanceTimeBy(31_000)
        runCurrent()
        val hb = console.heartbeat.value
        assertTrue(hb != null && hb.batteryPercent == 82, "心跳应被被动解析，实际 $hb")
    }
}
