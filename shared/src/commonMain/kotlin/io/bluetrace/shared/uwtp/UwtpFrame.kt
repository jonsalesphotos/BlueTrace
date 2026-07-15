package io.bluetrace.shared.uwtp

/**
 * UWTP 5B Core Header 帧编解码(工作稿 §3, 全小端):
 *
 * ```
 * offset 0  ver_flags  bit7..4=header_ver(=1)  bit3=EXT_HDR(恒0)  bit2=NEED_RSP  bit1..0=SEG_MODE
 * offset 1  main_type  0x01 CTRL / 0x10 LIVE / 0x11 TRANSFER
 * offset 2  sub_type   bit7=IS_RESPONSE | bit6..0=opcode
 * offset 3  seq        命令配对(响应回显) / 数据序列诊断
 * offset 4  len        payload 字节数
 * ```
 *
 * 一帧恰占一次 ATT notify/write, 不跨 PDU。同管道 0xBB 开头 = legacy B2A(与本协议无关);
 * `(首字节 & 0xF0) == 0x10` 才进 UWTP(§13.2 分流契约)。
 * SEG_MODE 非零帧在 Transport 分段(切片 S2/P3)落地前丢弃计数(§3 实现阶段规则)。
 */
data class UwtpFrame(
    val mainType: Int,
    val opcode: Int,
    val isResponse: Boolean,
    val needRsp: Boolean,
    val seq: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwtpFrame) return false
        return mainType == other.mainType && opcode == other.opcode &&
            isResponse == other.isResponse && needRsp == other.needRsp &&
            seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = mainType
        r = 31 * r + opcode
        r = 31 * r + isResponse.hashCode()
        r = 31 * r + needRsp.hashCode()
        r = 31 * r + seq
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

/** 帧解码结果: 非 UWTP 字节流(B2A 等)与坏帧分开, 供调用方分别计数。 */
sealed interface UwtpFrameDecode {
    data class Ok(val frame: UwtpFrame) : UwtpFrameDecode

    /** 首字节高 nibble != 0x1: 不是 UWTP(0xBB=B2A 等), 静默忽略不计错。 */
    data object NotUwtp : UwtpFrameDecode

    /** UWTP 首字节但帧非法: 丢弃计数(§5 规则 1 / §4 角色 1/1 非法)。 */
    data class Malformed(val reason: String) : UwtpFrameDecode
}

object UwtpFrameCodec {
    private const val VF_BASE = Uwtp.HEADER_VER shl 4 // 0x10
    private const val VF_EXT_HDR = 0x08
    private const val VF_NEED_RSP = 0x04
    private const val VF_SEG_MASK = 0x03
    private const val ST_RESPONSE = 0x80

    /** App 业务命令帧(NEED_RSP=1, 首字节 0x14)。 */
    fun encodeCommand(mainType: Int, opcode: Int, seq: Int, payload: ByteArray = EMPTY): ByteArray =
        encode(needRsp = true, mainType = mainType, subType = opcode, seq = seq, payload = payload)

    /** 数据/反馈帧(NEED_RSP=0, 首字节 0x10; App 侧用于 TRANSFER_ACK)。 */
    fun encodeData(mainType: Int, opcode: Int, seq: Int, payload: ByteArray = EMPTY): ByteArray =
        encode(needRsp = false, mainType = mainType, subType = opcode, seq = seq, payload = payload)

    /** 响应帧(测试/模拟设备用: sub_type = opcode|0x80, 回显 seq)。 */
    fun encodeResponse(mainType: Int, opcode: Int, seq: Int, payload: ByteArray = EMPTY): ByteArray =
        encode(needRsp = false, mainType = mainType, subType = opcode or ST_RESPONSE, seq = seq, payload = payload)

    private fun encode(needRsp: Boolean, mainType: Int, subType: Int, seq: Int, payload: ByteArray): ByteArray {
        require(payload.size <= Uwtp.PAYLOAD_HARD_MAX) { "payload ${payload.size} > ${Uwtp.PAYLOAD_HARD_MAX}" }
        val out = ByteArray(Uwtp.HEADER_LEN + payload.size)
        out[0] = (VF_BASE or (if (needRsp) VF_NEED_RSP else 0)).toByte()
        out[1] = mainType.toByte()
        out[2] = subType.toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = payload.size.toByte()
        payload.copyInto(out, Uwtp.HEADER_LEN)
        return out
    }

    /**
     * 解码一次 notify 的原始字节。校验顺序按工作稿 §5:
     * 包头长度 -> header_ver -> EXT_HDR -> SEG_MODE(分段实现前非零即弃) -> len 恰占整帧 -> 角色 1/1 非法。
     */
    fun decode(bytes: ByteArray): UwtpFrameDecode {
        if (bytes.isEmpty()) return UwtpFrameDecode.NotUwtp
        val vf = bytes[0].toInt() and 0xFF
        if ((vf ushr 4) != Uwtp.HEADER_VER) return UwtpFrameDecode.NotUwtp
        if (bytes.size < Uwtp.HEADER_LEN) return UwtpFrameDecode.Malformed("short_frame")
        if ((vf and VF_EXT_HDR) != 0) return UwtpFrameDecode.Malformed("ext_hdr")
        if ((vf and VF_SEG_MASK) != 0) return UwtpFrameDecode.Malformed("seg_mode")
        val len = bytes[4].toInt() and 0xFF
        if (bytes.size != Uwtp.HEADER_LEN + len) return UwtpFrameDecode.Malformed("len_mismatch")
        val needRsp = (vf and VF_NEED_RSP) != 0
        val sub = bytes[2].toInt() and 0xFF
        val isResponse = (sub and ST_RESPONSE) != 0
        if (isResponse && needRsp) return UwtpFrameDecode.Malformed("role_rsp_needrsp")
        return UwtpFrameDecode.Ok(
            UwtpFrame(
                mainType = bytes[1].toInt() and 0xFF,
                opcode = sub and 0x7F,
                isResponse = isResponse,
                needRsp = needRsp,
                seq = bytes[3].toInt() and 0xFF,
                payload = if (len == 0) EMPTY else bytes.copyOfRange(Uwtp.HEADER_LEN, Uwtp.HEADER_LEN + len),
            ),
        )
    }

    val EMPTY = ByteArray(0)
}
