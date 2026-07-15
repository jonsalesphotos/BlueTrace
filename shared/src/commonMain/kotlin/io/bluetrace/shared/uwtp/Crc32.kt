package io.bluetrace.shared.uwtp

/**
 * CRC-32(IEEE 802.3): poly 0xEDB88320(反射) / init 0xFFFFFFFF / xorout 0xFFFFFFFF。
 * commonMain 纯 Kotlin 实现(KMP 无 java.util.zip); 用于 crc_mode=1 的 frame_crc32 校验。
 * 注意: frame_crc32 精确覆盖式样待专项⑤冻结, 本实现为参考基线(transfer_id||offset||data)。
 */
class Crc32 {
    private var crc = 0xFFFF_FFFFL

    fun update(data: ByteArray, offset: Int, length: Int) {
        var c = crc
        for (i in offset until offset + length) {
            c = (c ushr 8) xor TABLE[((c xor data[i].toLong()) and 0xFF).toInt()]
        }
        crc = c
    }

    /** 当前 CRC 值(u32, 以非负 Long 承载)。 */
    val value: Long get() = (crc xor 0xFFFF_FFFFL) and 0xFFFF_FFFFL

    companion object {
        private val TABLE = LongArray(256) { n ->
            var c = n.toLong()
            repeat(8) {
                c = if ((c and 1L) != 0L) 0xEDB8_8320L xor (c ushr 1) else c ushr 1
            }
            c
        }

        fun of(data: ByteArray): Long = Crc32().apply { update(data, 0, data.size) }.value
    }
}
