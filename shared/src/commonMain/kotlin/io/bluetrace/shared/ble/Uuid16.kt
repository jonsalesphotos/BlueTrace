package io.bluetrace.shared.ble

/**
 * 通用 BLE 工具: 128-bit UUID -> 16-bit 短码(大写 4 位 hex). 与任何具体协议无关,
 * 故放 [io.bluetrace.shared.ble] 通用层(此前误置于 s7 包内, 造成 protocol.registry -> s7 包环, W5 解).
 *
 * BLE 各实现(Android/Nordic/Mock)扫描/发现回来的可能是 4 位短码或 128-bit 全串, 统一经此归一后比对:
 * - `"ffe0"` / `"FFE0"` -> `FFE0`
 * - 标准 SIG base 128-bit `0000FFE0-0000-1000-8000-00805F9B34FB` -> `FFE0`
 * - 私有 base 128-bit `0000FFE0-3C17-...` -> `FFE0`(非 0000 前缀仍取 bits 16..31 段)
 * - 无法归一(长度 <4 等) -> null
 */
fun extract16(uuid: String): String? {
    val u = uuid.trim().uppercase()
    return when {
        u.length == 4 -> u
        u.length >= 8 && u.startsWith("0000") -> u.substring(4, 8)
        u.length >= 8 -> u.substring(4, 8) // 非 0000 前缀的 128-bit: 仍取 bits 16..31 段
        else -> null
    }
}
