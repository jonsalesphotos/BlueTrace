package io.bluetrace.shared.util

/*
 * 通用格式化(commonMain 纯 Kotlin, **禁 String.format / JVM API**): 合一 app 多处重复实现.
 */

/**
 * 字节数 -> 人读大小(合一 app 三处 fmtMB): >=1MB 用 "%.1f MB", 否则 "%.0f KB"(均 HALF_UP 舍入,
 * 语义等价原 String.format 版; 字节数恒非负, 无需处理负值).
 */
fun formatMb(bytes: Long): String =
    if (bytes >= 1_000_000) formatFixed(bytes / 1_000_000.0, 1) + " MB"
    else formatFixed(bytes / 1_000.0, 0) + " KB"

/**
 * epoch 毫秒 + UTC 偏移秒 -> 本机时间戳 `yyyy-MM-dd HH:mm:ss.SSS`(合一两 VM 的 nowStamp).
 * 手法同 [io.bluetrace.shared.session.FileDiagnosticsLog] 的 p2/p3(毫秒由 epochMs%1000 单取, 因 parts 只到秒).
 */
fun formatFullStamp(epochMs: Long, offsetSeconds: Int): String {
    val p = epochMsToLocalParts(epochMs, offsetSeconds)
    val ms = epochMs.mod(1000L).toInt()
    return "${pad(p.year, 4)}-${pad(p.month, 2)}-${pad(p.day, 2)} " +
        "${pad(p.hour, 2)}:${pad(p.minute, 2)}:${pad(p.second, 2)}.${pad(ms, 3)}"
}

/**
 * 非负 double 按 HALF_UP 保留 [decimals] 位小数(纯 Kotlin, 替 String.format 的 %.Nf; 仅用于非负量:
 * +0.5 后向零截断 = 非负 HALF_UP).
 */
private fun formatFixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (value * factor + 0.5).toLong()
    if (decimals == 0) return scaled.toString()
    val intPart = scaled / factor
    val frac = scaled % factor
    return "$intPart.${pad(frac.toInt(), decimals)}"
}

/** 左补零到 [width] 位(非负整数). */
private fun pad(value: Int, width: Int): String {
    val s = value.toString()
    return if (s.length >= width) s else "0".repeat(width - s.length) + s
}
