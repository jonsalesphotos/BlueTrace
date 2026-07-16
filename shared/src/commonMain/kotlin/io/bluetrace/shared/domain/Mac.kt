package io.bluetrace.shared.domain

/**
 * **设备身份规则(2026-07-16 用户定, 硬约束)**: 物理设备唯一身份 = **规范化 MAC**。
 *
 * - 内部规范形 = **大写 12 位 hex, 无分隔符**(如 `C8D3E0C1D8F7`)——[ScannedDevice.id] 的真实端契约,
 *   避免同一 MAC 以 `C8:D3:...`/`c8:d3:...`/`C8D3...` 多种字符串并存导致键漂移;
 * - **显示**时再格式化成冒号形式([formatMacColon]); [ScannedDevice.address] 保留平台原始冒号串,
 *   供展示与平台 API(`getRemoteDevice`/`getPeripheralById` 均要求冒号格式)使用;
 * - 名称只用于展示, **不得**作为身份或识别判据; 扫描/重连/OTA 后确认均按同一 MAC, 不允许名称兜底;
 * - 取不到 MAC(解析失败)即失败, 不猜测;
 * - Mock 的合成 id(如 `s7-fcc4`)是**测试夹具豁免**: 不经真实平台, 只要求自身运行期一致。
 */

/**
 * 归一化 MAC 到规范形: 剥离 `:`/`-`/空白后须恰为 12 位 hex, 返回大写; 否则 null(调用方按失败处理, 不猜).
 */
fun normalizeMac(raw: String): String? {
    val hex = raw.filter { it != ':' && it != '-' && !it.isWhitespace() }
    if (hex.length != 12) return null
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return hex.uppercase()
}

/** 规范形 -> 展示用冒号形式(`C8D3E0C1D8F7` -> `C8:D3:E0:C1:D8:F7`); 非 12 位输入原样返回(防御). */
fun formatMacColon(normalized: String): String =
    if (normalized.length == 12) normalized.chunked(2).joinToString(":") else normalized
