package io.bluetrace.shared.s7

/**
 * S7 手表 B2A 协议帧信封（规格：Docs/归档/s7/protocol-spec.md §2）。
 *
 * 线格式（全小端、packed、无转义）：
 * ```
 * 首包：[SOF 0xBB][status][uiLen LE16][uiCRC LE16][uiIndex LE16] [cmd][key][paramLen LE16][param...]
 * 续包：[SOF 0xBB][status][uiLen LE16][uiCRC LE16][uiIndex LE16] [param 续段...]
 * ```
 * uiCRC = CRC16-CCITT-FALSE，覆盖偏移 8 起 uiLen 字节（不含帧头）。
 */
object S7Crc {
    /** CRC16-CCITT-FALSE：poly 0x1021 / init 0xFFFF / 不反转 / xorout 0。 */
    fun crc16CcittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
            }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

/** 帧头 ucStatus 位（ENUM_HEAD_STATUS_TYPE）。 */
object S7Status {
    const val SUCC = 0x00
    const val FAIL = 0x01
    const val ACK = 0x02
    const val IS_MULTI_PKT = 0x04
    const val MULTI_PKT_END = 0x08
    const val OTA_PART = 0x80

    /** 多包 ID 占 bit[5:4]。 */
    fun multiPktId(status: Int): Int = (status shr 4) and 0x03
}

/** 一条完整（已重组）的 B2A 消息。 */
data class S7Message(
    val cmd: Int,
    val key: Int,
    val status: Int,
    val param: ByteArray,
) {
    val isFail: Boolean get() = (status and S7Status.FAIL) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is S7Message) return false
        return cmd == other.cmd && key == other.key && status == other.status && param.contentEquals(other.param)
    }

    override fun hashCode(): Int {
        var r = cmd
        r = 31 * r + key
        r = 31 * r + status
        r = 31 * r + param.contentHashCode()
        return r
    }
}

object S7FrameCodec {
    const val SOF = 0xBB
    const val HEAD_LEN = 8
    const val CMD_HEAD_LEN = 4

    /**
     * 编码单帧请求（首包）。控制台全部命令 param ≤ 20B，永不分片（spec §5.5）。
     * @param needAck 帧头置 EHST_ACK（要求应答）；对不回包命令（关机/重启/恢复出厂）也照置，设备侧自行忽略。
     */
    fun encodeRequest(cmd: Int, key: Int, param: ByteArray = ByteArray(0), needAck: Boolean = true): ByteArray =
        encodeFrame(
            status = if (needAck) S7Status.ACK else S7Status.SUCC,
            index = 0,
            payload = ByteArray(CMD_HEAD_LEN + param.size).also {
                it[0] = cmd.toByte()
                it[1] = key.toByte()
                writeLe16(it, 2, param.size)
                param.copyInto(it, CMD_HEAD_LEN)
            },
        )

    /** 编码设备侧应答/上报帧（Mock 与测试用）。 */
    fun encodeResponse(cmd: Int, key: Int, param: ByteArray = ByteArray(0), status: Int = S7Status.SUCC): ByteArray =
        encodeFrame(
            status = status,
            index = 0,
            payload = ByteArray(CMD_HEAD_LEN + param.size).also {
                it[0] = cmd.toByte()
                it[1] = key.toByte()
                writeLe16(it, 2, param.size)
                param.copyInto(it, CMD_HEAD_LEN)
            },
        )

    /** 组任意帧（含续包裸 payload）。 */
    fun encodeFrame(status: Int, index: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEAD_LEN + payload.size)
        out[0] = SOF.toByte()
        out[1] = status.toByte()
        writeLe16(out, 2, payload.size)
        writeLe16(out, 4, S7Crc.crc16CcittFalse(payload))
        writeLe16(out, 6, index)
        payload.copyInto(out, HEAD_LEN)
        return out
    }

    internal fun writeLe16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    internal fun readLe16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)
}

/**
 * 有状态解码器（每设备每连接一个实例；重连须 [reset]）。
 * 职责：单 notification 内逐帧切分 → CRC 校验 → 多包重组 → 产出 [S7Message]。
 *
 * 容错（spec §2）：
 * - **短帧特例**：uiLen < 4（如产测握手 uiLen=3）→ cmd=payload[0]、key=payload[1]、其余为参数；
 * - CRC 失败 / SOF 不符 / 长度越界 → 丢弃该帧起余下字节，计入 [crcErrors]/[frameErrors]，不抛异常；
 * - 多包 ID 不符 → 丢弃整片重组缓冲（协议规定）。
 */
class S7FrameDecoder {
    var crcErrors: Int = 0
        private set
    var frameErrors: Int = 0
        private set

    // 多包重组态
    private var pendingCmd = 0
    private var pendingKey = 0
    private var pendingId = -1
    private var pendingNextIndex = 0
    private var pendingDeclaredLen = -1
    private var pendingParts = ArrayList<ByteArray>()

    fun reset() {
        pendingId = -1
        pendingNextIndex = 0
        pendingDeclaredLen = -1
        pendingParts = ArrayList()
    }

    /** 喂一次 notification 的原始字节，返回其中完整消息（0..N 条）。 */
    fun feed(bytes: ByteArray): List<S7Message> {
        val out = ArrayList<S7Message>(1)
        var pos = 0
        while (bytes.size - pos >= S7FrameCodec.HEAD_LEN) {
            if ((bytes[pos].toInt() and 0xFF) != S7FrameCodec.SOF) {
                frameErrors++
                return out // 失同步：丢弃余下（协议无转义，无法可靠再同步）
            }
            val status = bytes[pos + 1].toInt() and 0xFF
            val len = S7FrameCodec.readLe16(bytes, pos + 2)
            val crc = S7FrameCodec.readLe16(bytes, pos + 4)
            val index = S7FrameCodec.readLe16(bytes, pos + 6)
            if (pos + S7FrameCodec.HEAD_LEN + len > bytes.size) {
                frameErrors++
                return out // 长度越界：丢弃余下
            }
            val payload = bytes.copyOfRange(pos + S7FrameCodec.HEAD_LEN, pos + S7FrameCodec.HEAD_LEN + len)
            pos += S7FrameCodec.HEAD_LEN + len
            if (S7Crc.crc16CcittFalse(payload) != crc) {
                crcErrors++
                continue // 丢本帧，尝试后续帧（帧边界仍可信）
            }
            decodeVerified(status, index, payload)?.let { out.add(it) }
        }
        if (pos < bytes.size) frameErrors++ // 尾部残余 < 8B
        return out
    }

    private fun decodeVerified(status: Int, index: Int, payload: ByteArray): S7Message? {
        val multi = (status and S7Status.IS_MULTI_PKT) != 0
        val end = (status and S7Status.MULTI_PKT_END) != 0
        if (!multi && !end) {
            // 单帧不动重组缓冲：设备主动帧（心跳等）与多包上行共用通道、插帧协议合法
            // （固件参考实现对单包直接处理不清缓存）；防泄漏由首片覆盖 + ID/index 不符 reset 保证。
            return singleFrame(status, payload)
        }
        // 多包路径
        val id = S7Status.multiPktId(status)
        if (index == 0) {
            // 首片：带命令头
            if (payload.size < S7FrameCodec.CMD_HEAD_LEN) {
                frameErrors++
                return null
            }
            pendingCmd = payload[0].toInt() and 0xFF
            pendingKey = payload[1].toInt() and 0xFF
            pendingId = id
            pendingNextIndex = 1
            pendingDeclaredLen = S7FrameCodec.readLe16(payload, 2)
            pendingParts = arrayListOf(payload.copyOfRange(S7FrameCodec.CMD_HEAD_LEN, payload.size))
        } else {
            // 续片：ID 与 index 连续性双校验（spec §2「uiIndex 递增、不符即整片丢弃」）——
            // 中间片丢失/被 CRC 剔除后，禁止把后续片拼成带洞的"成功"消息。
            if (pendingId != id || index != pendingNextIndex) {
                frameErrors++
                reset()
                return null
            }
            pendingNextIndex++
            pendingParts.add(payload)
        }
        if (!end) return null
        // 末片：拼接产出；首片命令头声明的 paramLen 与重组总长不符 → 整片丢弃
        val total = pendingParts.sumOf { it.size }
        if (pendingDeclaredLen >= 0 && total != pendingDeclaredLen) {
            frameErrors++
            reset()
            return null
        }
        val param = ByteArray(total)
        var off = 0
        for (p in pendingParts) {
            p.copyInto(param, off)
            off += p.size
        }
        val msg = S7Message(pendingCmd, pendingKey, status, param)
        reset()
        return msg
    }

    private fun singleFrame(status: Int, payload: ByteArray): S7Message? {
        return when {
            payload.size >= S7FrameCodec.CMD_HEAD_LEN -> {
                val declared = S7FrameCodec.readLe16(payload, 2)
                val avail = payload.size - S7FrameCodec.CMD_HEAD_LEN
                val take = if (declared in 0..avail) declared else avail // paramLen 与 uiLen 不符时以实际为准
                S7Message(
                    cmd = payload[0].toInt() and 0xFF,
                    key = payload[1].toInt() and 0xFF,
                    status = status,
                    param = payload.copyOfRange(S7FrameCodec.CMD_HEAD_LEN, S7FrameCodec.CMD_HEAD_LEN + take),
                )
            }
            // 短帧特例（uiLen<4，产测握手实证）：cmd/key/余下为参数
            payload.size >= 2 -> S7Message(
                cmd = payload[0].toInt() and 0xFF,
                key = payload[1].toInt() and 0xFF,
                status = status,
                param = payload.copyOfRange(2, payload.size),
            )
            else -> {
                frameErrors++
                null
            }
        }
    }
}
