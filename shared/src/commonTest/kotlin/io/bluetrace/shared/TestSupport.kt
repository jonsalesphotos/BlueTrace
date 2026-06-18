package io.bluetrace.shared

import io.bluetrace.shared.domain.AssignedDevice
import io.bluetrace.shared.domain.CollectMode
import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.PROFILE_HRS
import io.bluetrace.shared.domain.SessionConfig
import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider

const val TEST_BASE_EPOCH = 1779348600_000L // 2026-05-21 15:30:00 +08

class TestZone(
    private val zone: String = "Asia/Shanghai",
    private val offset: Int = 8 * 3600,
) : TimeZoneProvider {
    override fun zoneId(): String = zone
    override fun offsetSeconds(): Int = offset
}

/** EpochClock 绑定测试虚拟时钟：传入 () -> currentVirtualMs（= testScheduler.currentTime）。 */
fun virtualClock(base: Long = TEST_BASE_EPOCH, nowVirtual: () -> Long): EpochClock =
    EpochClock { base + nowVirtual() }

fun testSubject() = Subject(
    id = "s1", alias = "shb", sex = Sex.MALE, birth = "1992-5", heightCm = 175, weightKg = 75.0,
)

fun sessionConfig(
    devices: List<AssignedDevice>,
    enabled: Set<CollectType> = CollectType.defaults,
    gnss: Boolean = false,
    start: Long = TEST_BASE_EPOCH,
) = SessionConfig(
    subject = testSubject(),
    mode = CollectMode.WEAR,
    devices = devices,
    enabledTypes = enabled,
    gnssEnabled = gnss,
    startEpochMs = start,
    timezoneId = "Asia/Shanghai",
    utcOffsetSeconds = 8 * 3600,
)

fun dutAssigned(id: String = "dut-0427", name: String = "BT-DUT-0427", addr: String = "C4:7B:8D:0A:04:27") =
    AssignedDevice(id, name, addr, DeviceKind.DUT)

fun refAssigned(id: String = "ref-h10", name: String = "Polar H10", addr: String = "A0:9E:1A:55:0D:10") =
    AssignedDevice(id, name, addr, DeviceKind.REFERENCE, PROFILE_HRS)
