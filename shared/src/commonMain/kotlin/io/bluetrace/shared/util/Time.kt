package io.bluetrace.shared.util

/**
 * 纯 Kotlin（无 java.time）的 unix epoch → 本地日期时间分解，供 commonMain 生成
 * 会话文件夹名（`<Mode>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>`，§6.1）。
 *
 * 机器口径（文件夹/文件名时间戳）固定 `yyyyMMdd_HHmmss`，locale 无关（§7.6）。
 */
data class LocalDateTimeParts(
    val year: Int,
    val month: Int, // 1..12
    val day: Int,   // 1..31
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    /** `yyyyMMdd` */
    fun dateCompact(): String = pad4(year) + pad2(month) + pad2(day)

    /** `HHmmss` */
    fun timeCompact(): String = pad2(hour) + pad2(minute) + pad2(second)

    /** `yyyyMMdd_HHmmss` */
    fun compact(): String = dateCompact() + "_" + timeCompact()
}

/** 当前墙钟（unix epoch 毫秒）来源 —— 平台注入，commonMain 不直接依赖系统时钟。 */
fun interface EpochClock {
    fun nowMs(): Long
}

/** 时区 / UTC 偏移来源 —— 平台注入。`offsetSeconds` 为本地相对 UTC 的秒数（东八区 = 28800）。 */
interface TimeZoneProvider {
    /** IANA 时区 id，如 `Asia/Shanghai`；离线还原本地时间用（manifest，§6.2）。 */
    fun zoneId(): String

    /** 当前本地相对 UTC 的偏移秒数。 */
    fun offsetSeconds(): Int
}

/**
 * epoch 毫秒 + UTC 偏移 → 本地年月日时分秒。
 * 用 Howard Hinnant 的 days→civil 算法，跨平台、无依赖、proleptic 公历。
 */
fun epochMsToLocalParts(epochMs: Long, utcOffsetSeconds: Int): LocalDateTimeParts {
    val localSeconds = epochMs.floorDiv(1000L) + utcOffsetSeconds
    var days = localSeconds.floorDiv(86400L)
    var rem = localSeconds.mod(86400L)

    val hour = (rem / 3600L).toInt()
    rem %= 3600L
    val minute = (rem / 60L).toInt()
    val second = (rem % 60L).toInt()

    // days since 1970-01-01 → civil (year, month, day)
    days += 719468L // shift epoch to 0000-03-01
    val era = (if (days >= 0) days else days - 146096) / 146097
    val doe = days - era * 146097                       // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)   // [0, 365]
    val mp = (5 * doy + 2) / 153                         // [0, 11]
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()     // [1, 31]
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
    val year = (if (month <= 2) y + 1 else y).toInt()

    return LocalDateTimeParts(year, month, day, hour, minute, second)
}

private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()
private fun pad4(v: Int): String {
    val s = v.toString()
    return "0".repeat((4 - s.length).coerceAtLeast(0)) + s
}

/** 把毫秒时长格式化为 `HH:MM:SS`（采集计时显示，§5.6）。 */
fun formatDurationHms(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return pad2(h.toInt()) + ":" + pad2(m.toInt()) + ":" + pad2(s.toInt())
}
