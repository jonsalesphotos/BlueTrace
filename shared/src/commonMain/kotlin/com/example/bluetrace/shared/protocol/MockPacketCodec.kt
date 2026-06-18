package com.example.bluetrace.shared.protocol

import com.example.bluetrace.shared.domain.DecodedStream

/**
 * Mock 包编解码 —— v1 的「假 wire 格式」单一事实源：[MockBleClient][com.example.bluetrace.shared.ble.MockBleClient]
 * 用 [encode] 造原始字节，[MockSampleDecoder] 用 [decode] 还原样本。
 *
 * 协议冻结后：真实 BLE 实现产真实 12B 帧头 + protobuf/定宽 payload（§4），换上真实 Decoder 即可，
 * 上层（SessionController / 写入器 / UI）不变。**本格式只属 Mock，绝不是真实协议。**
 *
 * 布局（小端，单样本/包）：
 * ```
 * [0]=0x7E sync  [1]=streamCode  [2..5]=deviceTsUs(u32 LE)  [6]=channelCount  [7..]=ch值(s16 LE)*channelCount
 * ```
 */
object MockPacketCodec {
    const val SYNC: Int = 0x7E

    private val streamByCode: Map<Int, DecodedStream> = mapOf(
        1 to DecodedStream.PPG_G,
        2 to DecodedStream.PPG_IR,
        3 to DecodedStream.ACC,
        4 to DecodedStream.GYRO,
        5 to DecodedStream.MAG,
        6 to DecodedStream.TEMP,
        7 to DecodedStream.HR,
    )
    private val codeByStream: Map<DecodedStream, Int> = streamByCode.entries.associate { (k, v) -> v to k }

    fun encode(stream: DecodedStream, deviceTsUs: Long, channels: List<Int>): ByteArray {
        val code = codeByStream.getValue(stream)
        val out = ByteArray(7 + channels.size * 2)
        out[0] = SYNC.toByte()
        out[1] = code.toByte()
        out[2] = (deviceTsUs and 0xFF).toByte()
        out[3] = ((deviceTsUs ushr 8) and 0xFF).toByte()
        out[4] = ((deviceTsUs ushr 16) and 0xFF).toByte()
        out[5] = ((deviceTsUs ushr 24) and 0xFF).toByte()
        out[6] = channels.size.toByte()
        var i = 7
        for (v in channels) {
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /** 解码单包；非法/不识别返回 null（上层计错丢弃，不抛异常打断管线，§4.6）。 */
    fun decode(rawBytes: ByteArray, receivedAtMs: Long): DecodedSample? {
        if (rawBytes.size < 7) return null
        if ((rawBytes[0].toInt() and 0xFF) != SYNC) return null
        val stream = streamByCode[rawBytes[1].toInt() and 0xFF] ?: return null
        val deviceTsUs = (rawBytes[2].toLong() and 0xFF) or
            ((rawBytes[3].toLong() and 0xFF) shl 8) or
            ((rawBytes[4].toLong() and 0xFF) shl 16) or
            ((rawBytes[5].toLong() and 0xFF) shl 24)
        val channelCount = rawBytes[6].toInt() and 0xFF
        if (rawBytes.size < 7 + channelCount * 2) return null
        val channels = ArrayList<Int>(channelCount)
        var i = 7
        repeat(channelCount) {
            val lo = rawBytes[i].toInt() and 0xFF
            val hi = rawBytes[i + 1].toInt() // 保留符号（s16）
            channels.add((hi shl 8) or lo)
            i += 2
        }
        return DecodedSample(stream, deviceTsUs, receivedAtMs, channels)
    }
}

/** 把字节转成实时流展示的 HEX（大写、空格分隔），如 `7E 02 1A 08 …`（§5.6）。 */
fun ByteArray.toHexLine(): String {
    val sb = StringBuilder(size * 3)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0F])
        sb.append(' ')
    }
    if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
    return sb.toString()
}

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
