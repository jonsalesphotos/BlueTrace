package io.bluetrace.shared.b2a

import io.bluetrace.shared.TestZone
import io.bluetrace.shared.ble.mock.MockBleClient
import io.bluetrace.shared.device.DeviceCommandException
import io.bluetrace.shared.device.DeviceCommandFailure
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.PROFILE_B2A
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B2aDeviceControl 六面契约测试(仿 B2aConsoleTest: MockBleClient + B2aMockWatch 闭环).
 * 验证控制面对 B2aConsole 的语义映射 + B2aCommandException -> DeviceCommandException 失败转换.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class B2aDeviceControlTest {

    private fun s7Device() =
        ScannedDevice("s7-fcc4", "SKG WATCH S7-FCC4", io.bluetrace.shared.domain.TEST_DUT_MAC, -58, DeviceKind.DUT, PROFILE_B2A)

    private fun TestScope.newControl(mock: MockBleClient): Pair<B2aConsole, B2aDeviceControl> {
        val console = B2aConsole(mock, "s7-fcc4", backgroundScope, virtualClock { testScheduler.currentTime }, TestZone())
        console.start()
        return console to B2aDeviceControl(console)
    }

    @Test
    fun info_mapsSwVerAndExtras() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        val info = control.info.get()
        assertEquals("1.2.7.0", info.swVer)
        assertEquals(null, info.hwVer) // S7 版本帧无独立硬件版本
        assertEquals(null, info.model)
        assertEquals("23", info.extras["bpVer"])
        assertEquals("1.0", info.extras["modemVer"])
        assertEquals("1.0", info.extras["secBlVer"])
    }

    @Test
    fun battery_mapsPercent() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        assertEquals(82, control.battery.percent())
    }

    @Test
    fun timeSync_zeroesDrift() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (console, control) = newControl(mock)
        control.timeSync.sync()
        val after = console.getDateTime()
        assertTrue(abs(console.driftSeconds(after)) <= 2, "对时后偏差应归零")
    }

    @Test
    fun logs_pullAssemblesWithProgress() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        var lastChunks = 0
        var lastBytes = 0
        val log = control.logs.pull { c, b -> lastChunks = c; lastBytes = b }
        assertTrue(log.size > 2000, "日志应有实际内容, 实际 ${log.size}B")
        assertTrue(lastChunks > 5, "应分多块回传, 实际 $lastChunks")
        assertEquals(log.size, lastBytes)
    }

    @Test
    fun power_rebootDisconnectIsSuccess() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        assertTrue(control.power.reboot(), "Mock 手表断链前判成功")
        assertEquals(LinkState.DISCONNECTED, mock.linkState("s7-fcc4").value)
    }

    @Test
    fun vendor_personWriteThenReadBack() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        val vendor = control.vendor // B2aDeviceControl.vendor 静态类型即 B2aVendorOps(无需转型)
        val target = B2aPerson(heightCm = 180, weightKg = 75, gender = 0, birthYear = 1990, birthMonth = 12, birthDay = 31)
        vendor.setPerson(target)
        assertEquals(target, vendor.getPerson())
    }

    @Test
    fun vendor_setDateTimeReadsBack() = runTest {
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        val applied = control.vendor.setDateTime(B2aDateTime(2026, 12, 31, 23, 59, 58, week = 1, timezone = -5))
        assertEquals(2026, applied.year)
        assertEquals(12, applied.month)
        assertEquals(31, applied.day)
        assertEquals(58, applied.second)
        assertEquals(4, applied.week) // 2026-12-31 周四, week 由 y/m/d 自算
    }

    @Test
    fun failure_timeoutConvertedToDeviceCommandException() = runTest {
        // 不 connect: write 静默丢弃 -> 3s 超时 -> B2aCommandException(Timeout) -> DeviceCommandException(Timeout)
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        val (_, control) = newControl(mock)
        val ex = assertFailsWith<DeviceCommandException> { control.battery.percent() }
        assertEquals(DeviceCommandFailure.Timeout, ex.failure)
    }

    @Test
    fun failure_deviceErrorCarriesCodeNameResolvedAtConstructionSite() = runTest {
        // 非法日期(月=13) -> Mock 回 PARAM(0x06) -> DeviceError -> DeviceCommandException(DeviceError(6,"PARAM"))
        val mock = MockBleClient(virtualClock { testScheduler.currentTime }, backgroundScope)
        mock.connect(s7Device())
        val (_, control) = newControl(mock)
        val ex = assertFailsWith<DeviceCommandException> {
            control.vendor.setDateTime(B2aDateTime(2026, 13, 1, 0, 0, 0, week = 1, timezone = 0))
        }
        val f = ex.failure
        assertTrue(f is DeviceCommandFailure.DeviceError, "应为 DeviceError, 实际 $f")
        assertEquals(6, f.code)
        assertEquals("PARAM", f.codeName) // codeName 在协议实现构造点解析(通用层零码表)
    }
}
