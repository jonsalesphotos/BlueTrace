package io.bluetrace.shared.s7

/**
 * B2A FILE_TRANS（Cmd `0x0F`）· OTA 文件传输协议编解码。
 *
 * 设计与真实包坐实见 `Docs/OTA/S7采集OTA_设计.md`；字段由固件 `apollo4_watch_s7`
 * (`ble2appWrap.c` B2A_FileTrans*Ack) 反推，**字节级偏移/字节序待真机抓包验证**（Phase 2）。
 *
 * 会话时序（App 驱动）：REQ 报会话总量 → 逐文件 START/TRANS×N/STOP → 末文件 STOP 即整包完成。
 * - 校验：TRANS 的 9B 应答含 U32 逐字节累加和，App 自算比对（[additiveChecksum]）；
 * - 分片：TRANS 数据 = 一条多包 B2A 消息（cmd=0x0F,key=TRANS），切片 ≤ sliceMaxSize=(MTU−15)×17。
 *
 * ⭐ golden 日志坐实（2026-07-08，ota.log）：`ParamPktLen:232 = MTU(247)−15`、`SliceMaxSize:3944=232×17`。
 * 每帧 param 须预留 **3B ATT 写头**（opcode 1 + handle 2）：线上帧 = 8B B2A头+4B命令头+param ≤ MTU−3。
 * 曾用 MTU−12（=235）会让首帧上链 247B → ATT PDU 250B > MTU → Android 写被拒、首切片即挂（BUG-1）。
 */
object S7FileTrans {
    // 子命令（ENUM_B2A_FILE_TRANS_KEY_TYPE，ble2appEx.h:453-458）
    const val KEY_START = 0x01
    const val KEY_TRANS = 0x02
    const val KEY_STOP = 0x03
    const val KEY_REQ = 0x04
    const val KEY_OFFSET = 0x06

    // ModuleId（会话级，REQ 里；ENUM_B2A_FILE_TRANS_MODULE_ID_TYPE，ble2appEx.h:464-477）
    const val MODULE_OTA = 1 // 唯一刷主 MCU 资源+固件的模组；BLE 收流永不刷 SecBL/boot

    // FileType（文件级，START[12]；BFTT_*，ble2appEx.h:480-495）。设备不据此路由（由文件名决定落地），仅驱动进度 UI。
    const val FT_FW = 2 // fw.dat 主镜像
    const val FT_RES = 3 // ResData/ResFat/ResCheck
    const val FT_FONT = 7 // fCheck/fCN*/fNum*

    // REQ 应答状态（ENUM_B2A_FT_REQ_STATUS_TYPE，ble2appEx.h:498-509）
    const val REQ_OK = 0
    const val REQ_DISK_FULL = 1
    const val REQ_BUSY = 2
    const val REQ_MEMORY = 3

    /** 每切片最多 17 包（MAC_SLICE_CNT）——sliceMaxSize = iMaxParamPktLen×17，iMaxParamPktLen = MTU−15。 */
    const val SLICE_PACKET_CNT = 17

    /** ATT 通知/写头（opcode 1B + handle 2B）：单次 GATT 写的可用载荷 = MTU − 3（golden 日志坐实）。 */
    const val ATT_HEADER = 3

    /**
     * 每帧 param 上限 = 协商 MTU − 3B ATT 写头 − (8B 帧头 + 4B 命令头) = MTU−15。首帧上链恰 ≤ MTU−3。
     * golden 日志：MTU 247 → 232（`FiTransReqFoMMI ParamPktLen:232`）。
     */
    fun maxParamPerFrame(mtu: Int): Int =
        (mtu - ATT_HEADER - S7FrameCodec.HEAD_LEN - S7FrameCodec.CMD_HEAD_LEN).coerceAtLeast(1)

    /** 本地 sliceMaxSize 推算 = (MTU−15)×17（MTU 247 → 3944，与设备回值一致；短应答时作权威）。 */
    fun defaultSliceMaxSize(mtu: Int): Int = maxParamPerFrame(mtu) * SLICE_PACKET_CNT

    /** U32 逐字节算术累加和（路径 B 校验；对 9B 应答里的 checksum 做期望值比对）。 */
    fun additiveChecksum(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long {
        var sum = 0L
        for (i in from until to) sum += (bytes[i].toInt() and 0xFF)
        return sum and 0xFFFFFFFFL
    }

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    // ---- 编码（App→表，均为单帧小包，param ≤ 20B） ----

    /**
     * REQ：报会话总量（`[0]moduleId [1]ucIsOffset [2]fileCount [3]rsv [4-7]totalSize LE`）。
     * fileCount/totalSize = 整场合计（该 module 这一波所有文件）。
     */
    fun encodeReq(moduleId: Int, fileCount: Int, totalSize: Long, isOffset: Boolean = false): ByteArray {
        val p = ByteArray(8)
        p[0] = moduleId.toByte()
        p[1] = if (isOffset) 1 else 0
        p[2] = fileCount.toByte()
        p[3] = 0
        le32(totalSize).copyInto(p, 4)
        return S7FrameCodec.encodeRequest(S7.CMD_FILE_TRANS, KEY_REQ, p)
    }

    /**
     * START：单文件头（`[0-3]fileSize [4-7]offset [8-11]sliceSize [12]type [13]zipFlag [14-15]nameLen [16..]name`）。
     * 文件名 ≤12 字符（szFile[13]）决定设备落地位置。zipFlag 固件未消费，恒 0。
     */
    fun encodeStart(name: String, fileSize: Long, sliceSize: Int, fileType: Int, offset: Long = 0): ByteArray {
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size <= 12) { "OTA file name too long (>12): $name" }
        val p = ByteArray(16 + nameBytes.size)
        le32(fileSize).copyInto(p, 0)
        le32(offset).copyInto(p, 4)
        le32(sliceSize.toLong()).copyInto(p, 8)
        p[12] = fileType.toByte()
        p[13] = 0 // zipFlag：一律明文整文件
        S7FrameCodec.writeLe16(p, 14, nameBytes.size)
        nameBytes.copyInto(p, 16)
        return S7FrameCodec.encodeRequest(S7.CMD_FILE_TRANS, KEY_START, p)
    }

    /** STOP：无 payload；设备内部 idx++，末文件即整包完成。 */
    fun encodeStop(): ByteArray = S7FrameCodec.encodeRequest(S7.CMD_FILE_TRANS, KEY_STOP)

    /** OFFSET：查询断点续传偏移（应答 4B offset）。 */
    fun encodeOffsetQuery(): ByteArray = S7FrameCodec.encodeRequest(S7.CMD_FILE_TRANS, KEY_OFFSET)

    /**
     * TRANS：一切片 = 一条多包消息（cmd=0x0F,key=TRANS,param=slice），切成 1..17 帧。
     * @param mtu 协商 MTU，决定每帧 param 段上限。
     */
    fun encodeSlice(slice: ByteArray, mtu: Int, multiPktId: Int = 0): List<ByteArray> =
        S7FrameCodec.encodeMultiPacket(S7.CMD_FILE_TRANS, KEY_TRANS, slice, maxParamPerFrame(mtu), multiPktId)

    // ---- 解析（表→App 应答） ----

    /** TRANS 的 9B 数据应答：`[0-3]recvLen LE [4-7]checkSum LE [8]status`。 */
    fun parseDataAck(param: ByteArray): OtaDataAck? {
        if (param.size < 9) return null
        return OtaDataAck(
            recvLen = readLe32(param, 0),
            checkSum = readLe32(param, 4),
            status = param[8].toInt() and 0xFF,
        )
    }

    /**
     * REQ 的 12B 应答：`[0]status [1]moduleId [2]fileCount [3]currFileIdx [4-7]sliceMaxSize LE [8-11]offset LE`。
     * ⚠️ 真机应答真实字节格式待抓包坐实（BUG-2；golden 日志设备侧只见 8B 内部记录、非上链 TX 帧）——
     * 短于 12B 返 null，会话据此走"按已授权继续、sliceMax 用本地算值"的防御分支（见 [S7OtaSession]）。
     */
    fun parseReqReply(param: ByteArray): OtaReqReply? {
        if (param.size < 12) return null
        return OtaReqReply(
            status = param[0].toInt() and 0xFF,
            moduleId = param[1].toInt() and 0xFF,
            fileCount = param[2].toInt() and 0xFF,
            currFileIdx = param[3].toInt() and 0xFF,
            sliceMaxSize = readLe32(param, 4).toInt(),
            offset = readLe32(param, 8),
        )
    }

    /** OFFSET 应答：4B 断点偏移。 */
    fun parseOffset(param: ByteArray): Long? = if (param.size < 4) null else readLe32(param, 0)

    // ---- 应答编码（设备侧 / Mock 用） ----

    fun encodeReqReply(reply: OtaReqReply): ByteArray {
        val p = ByteArray(12)
        p[0] = reply.status.toByte()
        p[1] = reply.moduleId.toByte()
        p[2] = reply.fileCount.toByte()
        p[3] = reply.currFileIdx.toByte()
        le32(reply.sliceMaxSize.toLong()).copyInto(p, 4)
        le32(reply.offset).copyInto(p, 8)
        return S7FrameCodec.encodeResponse(S7.CMD_FILE_TRANS, KEY_REQ, p)
    }

    fun encodeDataAck(ack: OtaDataAck): ByteArray {
        val p = ByteArray(9)
        le32(ack.recvLen).copyInto(p, 0)
        le32(ack.checkSum).copyInto(p, 4)
        p[8] = ack.status.toByte()
        return S7FrameCodec.encodeResponse(S7.CMD_FILE_TRANS, KEY_TRANS, p)
    }

    fun encodeCommAck(key: Int, code: Int): ByteArray =
        S7FrameCodec.encodeResponse(S7.CMD_FILE_TRANS, key, byteArrayOf(code.toByte()))

    fun encodeOffsetReply(offset: Long): ByteArray =
        S7FrameCodec.encodeResponse(S7.CMD_FILE_TRANS, KEY_OFFSET, le32(offset))
}

/** REQ 的 12B 应答（等设备端 MMI 授权后异步返回）。 */
data class OtaReqReply(
    val status: Int,
    val moduleId: Int,
    val fileCount: Int,
    val currFileIdx: Int,
    val sliceMaxSize: Int,
    val offset: Long,
)

/** TRANS 的 9B 数据应答。 */
data class OtaDataAck(
    val recvLen: Long,
    val checkSum: Long,
    val status: Int,
) {
    val ok: Boolean get() = status == S7Status.SUCC
}
