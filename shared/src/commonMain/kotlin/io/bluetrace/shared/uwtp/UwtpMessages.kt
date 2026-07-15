package io.bluetrace.shared.uwtp

/*
 * UWTP v0.2-draft Protobuf 消息(手解), 字段号与类型对齐 shared/proto/uwtp_v02 下各 .proto(DRAFT 快照,
 * 上游真源 = apollo4_watch_s7 Docs/.../proto_v02)。改字段必须六项同步并重跑 golden 对拍测试。
 *
 * 通用契约(工作稿 §6.3): 所有响应 field 1 = uint32 error_code, 省略即 0=OK, 不检查 Presence;
 * u32 字段一律用非负 Long 承载(避免符号问题), 错误码用 Int(低 16 bit 有效)。
 */

// ==== uwtp.common ====

/** RAW Schema 引用: 设备支持集合(CTRL INFO)/会话实际选择(LIVE_START_RSP)共用。 */
data class RawSchemaRef(
    val schemaId: Int = 0,
    val schemaRev: Int = 0,
    val sourceProfile: Int = 0, // 0x0000 STANDARD_RAW / 0x0101 GOODIX_HR_RAW / 0x0102 GOODIX_SPO2_RAW
    val channelMask: Long = 0,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, schemaId)
        uint32(2, schemaRev)
        uint32(3, sourceProfile)
        uint64(4, channelMask)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RawSchemaRef {
            var schemaId = 0
            var schemaRev = 0
            var sourceProfile = 0
            var channelMask = 0L
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> schemaId = r.readUint32Int(tag)
                    2 -> schemaRev = r.readUint32Int(tag)
                    3 -> sourceProfile = r.readUint32Int(tag)
                    4 -> channelMask = r.readUint64(tag)
                    else -> r.skip(tag)
                }
            }
            return RawSchemaRef(schemaId, schemaRev, sourceProfile, channelMask)
        }
    }
}

/** 最小错误响应编码结构(§6.4): 仅 field 1, 可被任意响应 Schema 解码; 不是独立 wire 消息身份。 */
object MinimalErrorRsp {
    fun encode(errorCode: Int): ByteArray = PbWriter().apply { uint32(1, errorCode) }.toByteArray()

    /** 从任意响应 payload 读 error_code(省略=0)。 */
    fun readErrorCode(bytes: ByteArray): Int {
        var code = 0
        val r = PbReader(bytes)
        while (r.hasMore()) {
            val tag = r.readTag()
            if ((tag ushr 3) == 1) code = r.readUint32Int(tag) else r.skip(tag)
        }
        return code
    }
}

// ==== uwtp.ctrl ====

/** STATE_QUERY 请求: 必须携带非零 query_flags(bit0=QUERY_RUNTIME_STATE)——不是空请求(§9.3)。 */
data class CtrlStateQueryReq(val queryFlags: Int = 0x01) {
    fun encode(): ByteArray = PbWriter().apply { uint32(1, queryFlags) }.toByteArray()
}

/** 三个正交状态(§9.1): offline 0=IDLE/1=CAPTURING; live/transfer 0=IDLE/1=ACTIVE。 */
data class CtrlRuntimeState(
    val offlineState: Int = 0,
    val liveState: Int = 0,
    val transferState: Int = 0,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, offlineState)
        uint32(2, liveState)
        uint32(3, transferState)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CtrlRuntimeState {
            var offline = 0
            var live = 0
            var transfer = 0
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> offline = r.readUint32Int(tag)
                    2 -> live = r.readUint32Int(tag)
                    3 -> transfer = r.readUint32Int(tag)
                    else -> r.skip(tag)
                }
            }
            return CtrlRuntimeState(offline, live, transfer)
        }
    }
}

/** STATE_QUERY 响应: 成功必须携带 runtime 子消息(接收端检查 Presence, §9.3)。 */
data class CtrlStateQueryRsp(
    val errorCode: Int = 0,
    val runtime: CtrlRuntimeState? = null,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        runtime?.let { message(2, it.encode()) }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CtrlStateQueryRsp {
            var code = 0
            var runtime: CtrlRuntimeState? = null
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> runtime = CtrlRuntimeState.decode(r.readBytes(tag))
                    else -> r.skip(tag)
                }
            }
            return CtrlStateQueryRsp(code, runtime)
        }
    }
}

/** INFO 响应(§7): 固件版本 + 全局 registry_rev + RAW 解码发现。请求为空 PB(0 字节)。 */
data class CtrlInfoRsp(
    val errorCode: Int = 0,
    val fwVersion: Long = 0,
    val registryRev: Int = 0, // 首版=1; 0 保留无效
    val rawSchemas: List<RawSchemaRef> = emptyList(),
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        uint32(2, fwVersion)
        uint32(3, registryRev)
        for (s in rawSchemas) message(4, s.encode())
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): CtrlInfoRsp {
            var code = 0
            var fw = 0L
            var rev = 0
            val schemas = ArrayList<RawSchemaRef>(2)
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> fw = r.readUint32(tag)
                    3 -> rev = r.readUint32Int(tag)
                    4 -> schemas.add(RawSchemaRef.decode(r.readBytes(tag)))
                    else -> r.skip(tag)
                }
            }
            return CtrlInfoRsp(code, fw, rev, schemas)
        }
    }
}

// ==== uwtp.live ====

/** LIVE_START 请求(配置内联, D-1)。 */
data class LiveStartReq(
    val rawChannelMask: Long = 0,
    val resultMask: Long = 0,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint64(1, rawChannelMask)
        uint64(2, resultMask)
    }.toByteArray()
}

/** LIVE_START 响应: effective 配置 + 本会话选定 RAW Schema("会话实际选择")。 */
data class LiveStartRsp(
    val errorCode: Int = 0,
    val effectiveChannelMask: Long = 0,
    val effectiveResultMask: Long = 0,
    val schemaId: Int = 0,
    val schemaRev: Int = 0,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        uint64(2, effectiveChannelMask)
        uint64(3, effectiveResultMask)
        uint32(4, schemaId)
        uint32(5, schemaRev)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): LiveStartRsp {
            var code = 0
            var chMask = 0L
            var resMask = 0L
            var schemaId = 0
            var schemaRev = 0
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> chMask = r.readUint64(tag)
                    3 -> resMask = r.readUint64(tag)
                    4 -> schemaId = r.readUint32Int(tag)
                    5 -> schemaRev = r.readUint32Int(tag)
                    else -> r.skip(tag)
                }
            }
            return LiveStartRsp(code, chMask, resMask, schemaId, schemaRev)
        }
    }
}

// LIVE_STOP: 请求空 PB; 响应仅 error_code(MinimalErrorRsp.readErrorCode 即可解)。

// ==== uwtp.transfer ====

/** OBJECT_LIST 请求(cursor 分页, 0=从头)。 */
data class ObjectListReq(val cursor: Long = 0) {
    fun encode(): ByteArray = PbWriter().apply { uint32(1, cursor) }.toByteArray()
}

/** 离线文件条目(file_id=session_id 单调不复用; object_token=不透明版本指纹 D-10)。 */
data class ObjectEntry(
    val fileId: Long = 0,
    val size: Long = 0,
    val startUtc: Long = 0,
    val flags: Int = 0, // COMPLETE/RECOVERED 等, 值表待专项⑤
    val objectToken: Long = 0, // fixed64 原样保存/回显/比较, 不解释
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, fileId)
        uint32(2, size)
        uint32(3, startUtc)
        uint32(4, flags)
        fixed64(5, objectToken)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ObjectEntry {
            var fileId = 0L
            var size = 0L
            var startUtc = 0L
            var flags = 0
            var token = 0L
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> fileId = r.readUint32(tag)
                    2 -> size = r.readUint32(tag)
                    3 -> startUtc = r.readUint32(tag)
                    4 -> flags = r.readUint32Int(tag)
                    5 -> token = r.readFixed64(tag)
                    else -> r.skip(tag)
                }
            }
            return ObjectEntry(fileId, size, startUtc, flags, token)
        }
    }
}

data class ObjectListRsp(
    val errorCode: Int = 0,
    val objects: List<ObjectEntry> = emptyList(),
    val nextCursor: Long = 0, // 0=已到末尾
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        for (o in objects) message(2, o.encode())
        uint32(3, nextCursor)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ObjectListRsp {
            var code = 0
            val objects = ArrayList<ObjectEntry>(8)
            var next = 0L
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> objects.add(ObjectEntry.decode(r.readBytes(tag)))
                    3 -> next = r.readUint32(tag)
                    else -> r.skip(tag)
                }
            }
            return ObjectListRsp(code, objects, next)
        }
    }
}

/** TRANSFER_BEGIN 请求: 建立连接域事务(断连即清, 续传凭 App 记录的 offset+token 重新 BEGIN, D-7)。 */
data class TransferBeginReq(
    val fileId: Long = 0,
    val startOffset: Long = 0,
    val crcMode: Int = 0, // 0=OFF, 1=每 DATA 帧带 frame_crc32
    val ackEveryN: Int = 0, // 窗口帧数; 数值属 S7 Binding(P0 实测前非冻结)
    val objectToken: Long = 0, // 0=首次/不校验; 非 0 不符 -> OBJECT_CHANGED
    val objectSize: Long = 0, // 辅助校验; 0=不校验
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, fileId)
        uint32(2, startOffset)
        uint32(3, crcMode)
        uint32(4, ackEveryN)
        fixed64(5, objectToken)
        uint32(6, objectSize)
    }.toByteArray()
}

data class TransferBeginRsp(
    val errorCode: Int = 0,
    val transferId: Long = 0, // 连接内事务世代号
    val totalSize: Long = 0,
    val objectToken: Long = 0, // 权威指纹, App 持久化供续传
    val acceptedOffset: Long = 0,
    val acceptedCrcMode: Int = 0,
    val acceptedAckEveryN: Int = 0,
    val maxDataLen: Int = 0, // 单 DATA 帧数据区上限 = uwtp_payload_max - 前缀
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        uint32(2, transferId)
        uint32(3, totalSize)
        fixed64(4, objectToken)
        uint32(5, acceptedOffset)
        uint32(6, acceptedCrcMode)
        uint32(7, acceptedAckEveryN)
        uint32(8, maxDataLen)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): TransferBeginRsp {
            var code = 0
            var id = 0L
            var total = 0L
            var token = 0L
            var accOffset = 0L
            var accCrc = 0
            var accAck = 0
            var maxData = 0
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> id = r.readUint32(tag)
                    3 -> total = r.readUint32(tag)
                    4 -> token = r.readFixed64(tag)
                    5 -> accOffset = r.readUint32(tag)
                    6 -> accCrc = r.readUint32Int(tag)
                    7 -> accAck = r.readUint32Int(tag)
                    8 -> maxData = r.readUint32Int(tag)
                    else -> r.skip(tag)
                }
            }
            return TransferBeginRsp(code, id, total, token, accOffset, accCrc, accAck, maxData)
        }
    }
}

/** TRANSFER_ACK(feedback): 累计确认到最大连续偏移。 */
data class TransferAck(
    val transferId: Long = 0,
    val nextExpectedOffset: Long = 0,
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, transferId)
        uint32(2, nextExpectedOffset)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): TransferAck {
            var id = 0L
            var next = 0L
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> id = r.readUint32(tag)
                    2 -> next = r.readUint32(tag)
                    else -> r.skip(tag)
                }
            }
            return TransferAck(id, next)
        }
    }
}

/** FINISH/ABORT 请求共形(仅 transfer_id); 响应仅 error_code。 */
data class TransferIdReq(val transferId: Long = 0) {
    fun encode(): ByteArray = PbWriter().apply { uint32(1, transferId) }.toByteArray()
}

/** DELETE_OBJECT 请求(单删/批删同一身份; repeated uint32 按 proto3 默认 packed 编码)。 */
data class TransferDeleteReq(val fileIds: List<Long> = emptyList()) {
    fun encode(): ByteArray = PbWriter().apply { packedUint32(1, fileIds) }.toByteArray()
}

data class TransferDeleteRsp(
    val errorCode: Int = 0,
    val deletedCount: Int = 0,
) {
    companion object {
        fun decode(bytes: ByteArray): TransferDeleteRsp {
            var code = 0
            var count = 0
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> count = r.readUint32Int(tag)
                    else -> r.skip(tag)
                }
            }
            return TransferDeleteRsp(code, count)
        }
    }
}

/** TRANSFER_ERROR_EVENT(0x10, event): 设备停发的异步错误通知; 仅当前活动 transfer_id 的事件有效。 */
data class TransferErrorEvent(
    val errorCode: Int = 0,
    val transferId: Long = 0,
    val lastValidOffset: Long = 0, // 设备确认仍有效的连续对象偏移
) {
    fun encode(): ByteArray = PbWriter().apply {
        uint32(1, errorCode)
        uint32(2, transferId)
        uint32(3, lastValidOffset)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): TransferErrorEvent {
            var code = 0
            var id = 0L
            var last = 0L
            val r = PbReader(bytes)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> code = r.readUint32Int(tag)
                    2 -> id = r.readUint32(tag)
                    3 -> last = r.readUint32(tag)
                    else -> r.skip(tag)
                }
            }
            return TransferErrorEvent(code, id, last)
        }
    }
}

// ==== TRANSFER_DATA 二进制前缀(非 PB, §12.4) ====

/**
 * DATA 帧 payload: `transfer_id u32 LE + offset u32 LE [+ frame_crc32 u32 LE] + data[]`。
 * 不携带 data_len(由 Core len 减前缀得到)。crc_mode=1 时 frame_crc32 覆盖 transfer_id||offset||data
 * (覆盖式样待专项⑤冻结, 阶段一固件只回 accepted_crc_mode=0)。
 */
data class TransferDataFrame(
    val transferId: Long,
    val offset: Long,
    val data: ByteArray,
    /** crc_mode=1 时链上携带的 CRC(原样保留); crc_mode=0 为 null。 */
    val frameCrc32: Long? = null,
) {
    val crcOk: Boolean
        get() = frameCrc32 == null || frameCrc32 == computeCrc(transferId, offset, data)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferDataFrame) return false
        return transferId == other.transferId && offset == other.offset &&
            frameCrc32 == other.frameCrc32 && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var r = transferId.hashCode()
        r = 31 * r + offset.hashCode()
        r = 31 * r + data.contentHashCode()
        r = 31 * r + (frameCrc32?.hashCode() ?: 0)
        return r
    }

    companion object {
        const val PREFIX_NOCRC = 8
        const val PREFIX_CRC = 12

        fun parse(payload: ByteArray, crcMode: Int): TransferDataFrame? {
            val prefix = if (crcMode == 1) PREFIX_CRC else PREFIX_NOCRC
            if (payload.size < prefix) return null
            val id = readLe32(payload, 0)
            val offset = readLe32(payload, 4)
            val crc = if (crcMode == 1) readLe32(payload, 8) else null
            return TransferDataFrame(id, offset, payload.copyOfRange(prefix, payload.size), crc)
        }

        fun build(transferId: Long, offset: Long, data: ByteArray, withCrc: Boolean = false): ByteArray =
            buildRaw(transferId, offset, data, if (withCrc) computeCrc(transferId, offset, data) else null)

        /** 显式 CRC 版(golden 对拍/坏帧注入用: 样本 CRC 为占位值, 覆盖式样待专项⑤)。 */
        fun buildRaw(transferId: Long, offset: Long, data: ByteArray, frameCrc32: Long?): ByteArray {
            val prefix = if (frameCrc32 != null) PREFIX_CRC else PREFIX_NOCRC
            val out = ByteArray(prefix + data.size)
            writeLe32(out, 0, transferId)
            writeLe32(out, 4, offset)
            if (frameCrc32 != null) writeLe32(out, 8, frameCrc32)
            data.copyInto(out, prefix)
            return out
        }

        /** frame_crc32 参考实现: CRC32(IEEE) over `transfer_id LE || offset LE || data`。 */
        fun computeCrc(transferId: Long, offset: Long, data: ByteArray): Long {
            val head = ByteArray(8)
            writeLe32(head, 0, transferId)
            writeLe32(head, 4, offset)
            val crc = Crc32()
            crc.update(head, 0, head.size)
            crc.update(data, 0, data.size)
            return crc.value
        }

        private fun readLe32(src: ByteArray, off: Int): Long =
            (src[off].toLong() and 0xFF) or
                ((src[off + 1].toLong() and 0xFF) shl 8) or
                ((src[off + 2].toLong() and 0xFF) shl 16) or
                ((src[off + 3].toLong() and 0xFF) shl 24)

        private fun writeLe32(dst: ByteArray, off: Int, value: Long) {
            dst[off] = (value and 0xFF).toByte()
            dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
            dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
            dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
