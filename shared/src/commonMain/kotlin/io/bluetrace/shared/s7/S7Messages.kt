package io.bluetrace.shared.s7

import io.bluetrace.shared.domain.Sex
import io.bluetrace.shared.domain.Subject
import io.bluetrace.shared.s7.S7FrameCodec.readLe16
import io.bluetrace.shared.s7.S7FrameCodec.writeLe16

/**
 * B2A 命令字/子键常量 + 维护命令 payload 编解码（规格：Docs/归档/s7/protocol-spec.md §3–4）。
 * 解析全部带越界防御：长度不符返回 null，不抛异常（线内容不可信）。
 */
object S7 {
    // 一级命令字
    const val CMD_BOND = 0x01
    const val CMD_GET = 0x02
    const val CMD_SET = 0x03
    const val CMD_PUSH = 0x04
    const val CMD_IND = 0x05
    const val CMD_RPT_DATA = 0x06
    const val CMD_DEV_CTRL = 0x07
    const val CMD_TEST = 0x08
    const val CMD_FILE_TRANS = 0x0F
    const val CMD_RPT_DATA2 = 0x86

    // GET/SET 子键（本控制台用到的）
    const val KEY_DATE_TIME = 0x01
    const val KEY_PERSON_DATA = 0x04
    const val KEY_DEV_INFO = 0x21
    const val KEY_DEV_FUNC = 0x22
    const val KEY_DEV_BLE_MAC = 0x23
    const val KEY_DEV_VOL = 0x24
    const val KEY_SN_INFO = 0x26
    const val KEY_BOND_STATE = 0x28

    // DEV_CTRL 子键
    const val CTRL_POWER_OFF = 0x01
    const val CTRL_RESET = 0x02
    const val CTRL_RESTORE = 0x03
    const val CTRL_FIND = 0x04
    const val CTRL_FIND_END = 0x05
    const val CTRL_FILE_LOG = 0x07
    const val CTRL_ACK_FILE_LOG = 0x09

    // IND 子键
    const val IND_HEARTBEAT = 0x0C

    /** 应用层错误码 EBEC（0x00–0x0D）→ 名称（诊断展示用，非本地化文案）。 */
    val errorName: Map<Int, String> = mapOf(
        0x00 to "SUCC", 0x01 to "FAIL", 0x02 to "TIMEOUT", 0x03 to "FORMAT",
        0x04 to "MEMORY", 0x05 to "NOT_SUPPORT", 0x06 to "PARAM", 0x07 to "BUSY",
        0x08 to "LOW_BAT", 0x09 to "NO_DATA", 0x0A to "MD5", 0x0B to "CRC",
        0x0C to "FATAL", 0x0D to "FSUM_FAIL",
    )

    /** CommAck（1B）：0=成功，1=失败，5=不支持。 */
    fun commAckOk(param: ByteArray): Boolean = param.isNotEmpty() && param[0].toInt() == 0
}

/** 日期时间（GET 响应 / SET 请求同 9B 布局）。SET 的 timezone=0 表示保持设备本地时区。 */
data class S7DateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val week: Int,
    /** GET 响应为有符号 int8；SET 请求 0=不改时区。 */
    val timezone: Int,
) {
    fun encode(): ByteArray = ByteArray(9).also {
        writeLe16(it, 0, year)
        it[2] = month.toByte()
        it[3] = day.toByte()
        it[4] = hour.toByte()
        it[5] = minute.toByte()
        it[6] = second.toByte()
        it[7] = week.toByte()
        it[8] = timezone.toByte()
    }

    /** `yyyy-MM-dd HH:mm:ss` 展示串。 */
    fun display(): String = "${pad4(year)}-${pad2(month)}-${pad2(day)} ${pad2(hour)}:${pad2(minute)}:${pad2(second)}"

    companion object {
        fun parse(b: ByteArray): S7DateTime? {
            if (b.size < 9) return null
            val dt = S7DateTime(
                year = readLe16(b, 0),
                month = b[2].toInt() and 0xFF,
                day = b[3].toInt() and 0xFF,
                hour = b[4].toInt() and 0xFF,
                minute = b[5].toInt() and 0xFF,
                second = b[6].toInt() and 0xFF,
                week = b[7].toInt() and 0xFF,
                timezone = b[8].toInt(), // 有符号
            )
            // 字段值域校验（线内容不可信：RTC 未初始化/恢复出厂后可能回垃圾值）——
            // 非法即 null，上层统一转 DeviceError(FORMAT)，避免 monthDays 等下游越界崩溃。
            if (dt.month !in 1..12 || dt.day !in 1..31 || dt.hour > 23 || dt.minute > 59 || dt.second > 59) return null
            return dt
        }

        private fun pad2(v: Int) = if (v < 10) "0$v" else "$v"
        private fun pad4(v: Int) = v.toString().padStart(4, '0')
    }
}

/** 电量（GET 0x24 响应，10B）。 */
data class S7Battery(
    val capacityMah: Int,
    val voltageMv: Int,
    val percent: Int,
    val chargeStatus: Int,
) {
    companion object {
        fun parse(b: ByteArray): S7Battery? {
            if (b.size < 10) return null
            return S7Battery(
                capacityMah = readLe16(b, 0),
                voltageMv = readLe16(b, 2),
                percent = b[4].toInt() and 0xFF,
                chargeStatus = b[5].toInt() and 0xFF,
                // ulLastTime[6..9] 固件恒 0，忽略
            )
        }
    }
}

/**
 * 身份信息（GET 0x26 响应，定长 59B=5+12+6+16+20，零填充）。
 * 真机实证（2026-07-02，SKG WATCH S7-FCC4）：BleMac[6] 为 **LE 反序** 存储 → 展示时反转；
 * SN/IMEI/ICCID 为 ASCII（尾部可含非打印填充 → 截断）；DevType[5] 可能为二进制码 → 回退 hex 展示。
 */
data class S7SnInfo(
    val devType: String,
    val sn: String,
    val macHex: String,
    val imei: String,
    val iccid: String,
) {
    companion object {
        fun parse(b: ByteArray): S7SnInfo? {
            if (b.size < 59) return null
            return S7SnInfo(
                // DevType 恒为 5B 二进制型号码（真机实证 68 39 71 25 81 = 文档 overseas device_type），hex 展示
                devType = hexStr(b, 0, 5),
                sn = asciiTrim(b, 5, 12),
                // LE 反序 → 反转成常规展示序（真机实证：线上 C4:FC:19:48:61:71 = 实际 71:61:48:19:FC:C4）
                macHex = b.copyOfRange(17, 23).reversed().joinToString(":") { hex2(it) },
                imei = asciiTrim(b, 23, 16),
                iccid = asciiTrim(b, 39, 20),
            )
        }

        /** ASCII 段：遇 0x00 或非打印字节即截断（真机 IMEI/FW 尾部实测有非打印填充）。 */
        private fun asciiTrim(b: ByteArray, offset: Int, len: Int): String = buildString {
            for (i in offset until offset + len) {
                val c = b[i].toInt() and 0xFF
                if (c !in 0x20..0x7E) break
                append(c.toChar())
            }
        }

        private fun hexStr(b: ByteArray, offset: Int, len: Int): String {
            val slice = b.copyOfRange(offset, offset + len)
            if (slice.all { it.toInt() == 0 }) return ""
            return slice.joinToString(" ") { hex2(it) }
        }

        internal fun hex2(byte: Byte): String {
            val v = byte.toInt() and 0xFF
            return "0123456789ABCDEF"[v shr 4].toString() + "0123456789ABCDEF"[v and 0xF]
        }
    }
}

/** 设备版本信息（GET 0x21 响应，变长 TLV：swLen+sw | mdLen+md | secLen+sec | reserved(1) | bpLen+bp）。 */
data class S7DeviceInfo(
    val swVer: String,
    val modemVer: String,
    val secBlVer: String,
    val bpVer: String,
) {
    fun encode(): ByteArray {
        val sw = swVer.encodeToByteArray()
        val md = modemVer.encodeToByteArray()
        val sec = secBlVer.encodeToByteArray()
        val bp = bpVer.encodeToByteArray()
        val out = ByteArray(1 + sw.size + 1 + md.size + 1 + sec.size + 1 + 1 + bp.size)
        var p = 0
        out[p++] = sw.size.toByte(); sw.copyInto(out, p); p += sw.size
        out[p++] = md.size.toByte(); md.copyInto(out, p); p += md.size
        out[p++] = sec.size.toByte(); sec.copyInto(out, p); p += sec.size
        out[p++] = 1 // reserved 固定 1
        out[p++] = bp.size.toByte(); bp.copyInto(out, p)
        return out
    }

    companion object {
        fun parse(b: ByteArray): S7DeviceInfo? {
            var p = 0
            fun seg(): String? {
                if (p >= b.size) return null
                val len = b[p].toInt() and 0xFF
                p += 1
                if (p + len > b.size) return null
                // 真机实证：TLV 段内可含非打印尾部填充 → 截断
                val s = b.decodeToString(p, p + len).takeWhile { it.code in 0x20..0x7E }
                p += len
                return s
            }
            val sw = seg() ?: return null
            val md = seg() ?: return null
            val sec = seg() ?: return null
            if (p >= b.size) return null
            p += 1 // reserved
            val bp = seg() ?: ""
            return S7DeviceInfo(sw, md, sec, bp)
        }
    }
}

/** 用户信息（GET 7B / SET 8B）。身高 cm、体重 kg（协议为 u8 整数）。 */
data class S7Person(
    val heightCm: Int,
    val weightKg: Int,
    val gender: Int,
    val birthYear: Int,
    val birthMonth: Int,
    val birthDay: Int,
) {
    /** SET 请求 8B（GET 布局 + 1B reserve）。 */
    fun encodeSet(): ByteArray = ByteArray(8).also {
        it[0] = heightCm.toByte()
        it[1] = weightKg.toByte()
        it[2] = gender.toByte()
        writeLe16(it, 3, birthYear)
        it[5] = birthMonth.toByte()
        it[6] = birthDay.toByte()
        it[7] = 0 // ulReserve
    }

    companion object {
        fun parse(b: ByteArray): S7Person? {
            if (b.size < 7) return null
            return S7Person(
                heightCm = b[0].toInt() and 0xFF,
                weightKg = b[1].toInt() and 0xFF,
                gender = b[2].toInt() and 0xFF,
                birthYear = readLe16(b, 3),
                birthMonth = b[5].toInt() and 0xFF,
                birthDay = b[6].toInt() and 0xFF,
            )
        }
    }
}

/** 心跳（IND 0x0C，设备→App，8B：utc(4 LE)+seq(2 LE)+battery(1)+reserved(1)）。 */
data class S7Heartbeat(
    val utcSeconds: Long,
    val seq: Int,
    val batteryPercent: Int,
) {
    companion object {
        fun parse(b: ByteArray): S7Heartbeat? {
            if (b.size < 8) return null
            val utc = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            return S7Heartbeat(utc, readLe16(b, 4), b[6].toInt() and 0xFF)
        }
    }
}

/** 字节串 → 空格分隔 hex（操作日志展示；超长截断）。 */
fun ByteArray.toHexPreview(maxBytes: Int = 24): String {
    val n = size.coerceAtMost(maxBytes)
    val head = (0 until n).joinToString(" ") { S7SnInfo.hex2(this[it]) }
    return if (size > maxBytes) "$head …(${size}B)" else head
}

/** Subject → S7Person 域映射(B3 下沉自 app; 性别编码 0/1/2 语义待实机核对, audit 清单)。 */
fun Subject.toS7Person(): S7Person {
    val parts = birth.split("-")
    return S7Person(
        heightCm = heightCm ?: 170,
        weightKg = (weightKg ?: 65.0).toInt(),
        gender = when (sex) {
            Sex.MALE -> 1
            Sex.FEMALE -> 0
            Sex.OTHER -> 2
        },
        birthYear = parts.getOrNull(0)?.toIntOrNull() ?: 1990,
        birthMonth = parts.getOrNull(1)?.toIntOrNull() ?: 1,
        birthDay = parts.getOrNull(2)?.toIntOrNull() ?: 1,
    )
}
