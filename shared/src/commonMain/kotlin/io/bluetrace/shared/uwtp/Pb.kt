package io.bluetrace.shared.uwtp

/**
 * proto3 wire 最小编解码(手解, 无依赖), 只覆盖 UWTP v0.2 契约用到的子集:
 * varint(uint32/uint64) / fixed64 / length-delimited(子消息与 repeated message)。
 *
 * 契约对齐(工作稿 §6):
 * - 标量默认值(0)不上 wire(非 optional 省略语义), 接收端读默认值——`error_code` 省略即 0=OK,
 *   显式 `08 00` 同样合法(两种编码等价, §6.3/§6.8);
 * - 未知字段跳过; wire 截断/非法 -> [PbDecodeException] 拒绝(§6.8);
 * - 编码按字段号升序输出(与 protoc 参考生成器一致, golden 对拍依赖此顺序)。
 */
class PbDecodeException(message: String) : Exception(message)

/** proto wire type 常量。 */
internal object PbWire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LEN = 2
    const val FIXED32 = 5
}

/** 编码器: 调用方按字段号升序调用各 write 方法。 */
class PbWriter {
    private var buf = ByteArray(32)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) {
            var cap = buf.size * 2
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    private fun rawByte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                rawByte(v.toInt())
                return
            }
            rawByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun key(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** uint32(值域校验由调用方保证非负; 0 不上 wire)。 */
    fun uint32(field: Int, value: Long) {
        require(value in 0..0xFFFF_FFFFL) { "uint32 field $field out of range: $value" }
        if (value == 0L) return
        key(field, PbWire.VARINT)
        rawVarint(value)
    }

    fun uint32(field: Int, value: Int) = uint32(field, value.toLong())

    /** uint64(0 不上 wire; 负 Long 视作高位置位的 u64 原样编码)。 */
    fun uint64(field: Int, value: Long) {
        if (value == 0L) return
        key(field, PbWire.VARINT)
        rawVarint(value)
    }

    /** fixed64(小端 8B; 0 不上 wire)。object_token 等不透明 64bit 指纹用。 */
    fun fixed64(field: Int, value: Long) {
        if (value == 0L) return
        key(field, PbWire.FIXED64)
        ensure(8)
        var v = value
        repeat(8) {
            buf[size++] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    /** 子消息/repeated message 元素: 显式在场(即使内容为空也上 wire, 表达 Presence)。 */
    fun message(field: Int, encoded: ByteArray) {
        key(field, PbWire.LEN)
        rawVarint(encoded.size.toLong())
        ensure(encoded.size)
        encoded.copyInto(buf, size)
        size += encoded.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * 解码器: `while (hasMore()) { val tag = readTag(); when (tag ushr 3) { ... else -> skip(tag) } }`。
 * 已知字段 wire type 不符按坏 wire 拒绝(生产者错误), 未知字段按 wire type 跳过。
 */
class PbReader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {

    fun hasMore(): Boolean = pos < end

    /** 返回 tag(= field<<3 | wire); field==0 非法。 */
    fun readTag(): Int {
        val tag = readRawVarint()
        if (tag > 0xFFFF_FFFFL) throw PbDecodeException("tag too large")
        val t = tag.toInt()
        if ((t ushr 3) == 0) throw PbDecodeException("field 0")
        return t
    }

    private fun readRawVarint(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            if (pos >= end) throw PbDecodeException("varint truncated")
            val b = bytes[pos++].toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b >= 0) return result
            shift += 7
        }
        throw PbDecodeException("varint too long")
    }

    /** 要求 wire=VARINT 的 uint64。 */
    fun readUint64(tag: Int): Long {
        requireWire(tag, PbWire.VARINT)
        return readRawVarint()
    }

    /** 要求 wire=VARINT 的 uint32(超 32bit 部分截断, 与 protobuf 语义一致)。 */
    fun readUint32(tag: Int): Long = readUint64(tag) and 0xFFFF_FFFFL

    fun readUint32Int(tag: Int): Int = readUint32(tag).toInt()

    /** 要求 wire=FIXED64(小端 8B 原样取回)。 */
    fun readFixed64(tag: Int): Long {
        requireWire(tag, PbWire.FIXED64)
        if (pos + 8 > end) throw PbDecodeException("fixed64 truncated")
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    /** 要求 wire=LEN, 取出定界字节段(子消息/bytes)。 */
    fun readBytes(tag: Int): ByteArray {
        requireWire(tag, PbWire.LEN)
        val len = readRawVarint()
        if (len < 0 || len > (end - pos)) throw PbDecodeException("len-delimited truncated")
        val n = len.toInt()
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** 未知字段按 wire type 跳过; group(3/4)与未知 wire type 拒绝。 */
    fun skip(tag: Int) {
        when (tag and 0x07) {
            PbWire.VARINT -> readRawVarint()
            PbWire.FIXED64 -> {
                if (pos + 8 > end) throw PbDecodeException("fixed64 truncated")
                pos += 8
            }
            PbWire.LEN -> {
                val len = readRawVarint()
                if (len < 0 || len > (end - pos)) throw PbDecodeException("len-delimited truncated")
                pos += len.toInt()
            }
            PbWire.FIXED32 -> {
                if (pos + 4 > end) throw PbDecodeException("fixed32 truncated")
                pos += 4
            }
            else -> throw PbDecodeException("unsupported wire type ${tag and 0x07}")
        }
    }

    private fun requireWire(tag: Int, wire: Int) {
        if ((tag and 0x07) != wire) {
            throw PbDecodeException("field ${tag ushr 3}: wire ${tag and 0x07}, expect $wire")
        }
    }
}
