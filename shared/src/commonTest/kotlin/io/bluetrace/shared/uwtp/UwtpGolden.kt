package io.bluetrace.shared.uwtp

/*
 * DRAFT golden 样本(payload 级 wire 字节), 逐字节复制自 shared/proto/uwtp_v02/golden 下各 .hex
 * (上游真源 = apollo4_watch_s7 Docs/08_采集V2_离线存储与通信/proto_v02/golden, 2026-07-15 P1 首版)。
 * 本文件由脚本从 .hex 生成(空样本在 .hex 中以 "(空)" 标记), 勿手改内容; proto 变更时六项同步重生成。
 */
object UwtpGolden {
    /** ctrl_info_req.hex: 空 payload */
    const val ctrlInfoReq = ""
    /** ctrl_info_rsp.hex */
    const val ctrlInfoRsp = "10 01 18 01 22 0b 08 81 20 10 01 18 81 02 20 80 02"
    /** ctrl_state_query_req.hex */
    const val ctrlStateQueryReq = "08 01"
    /** ctrl_state_query_rsp_busy.hex */
    const val ctrlStateQueryRspBusy = "12 04 08 01 18 01"
    /** ctrl_state_query_rsp_idle.hex */
    const val ctrlStateQueryRspIdle = "12 00"
    /** data_prefix_crc.hex */
    const val dataPrefixCrc = "01 00 00 00 00 00 01 00 ef be ad de"
    /** data_prefix_nocrc.hex */
    const val dataPrefixNocrc = "01 00 00 00 00 00 01 00"
    /** live_start_req.hex */
    const val liveStartReq = "08 80 02"
    /** live_start_rsp.hex */
    const val liveStartRsp = "10 80 02 20 81 20 28 01"
    /** live_stop_req.hex: 空 payload */
    const val liveStopReq = ""
    /** live_stop_rsp_ok.hex: 空 payload */
    const val liveStopRspOk = ""
    /** minimal_error_not_supported.hex */
    const val minimalErrorNotSupported = "08 02"
    /** object_list_rsp.hex */
    const val objectListRsp = "12 18 08 07 10 80 80 80 32 18 80 dc d6 d2 06 20 01 29 88 77 66 55 44 33 22 11"
    /** transfer_ack.hex */
    const val transferAck = "08 01 10 80 80 04"
    /** transfer_begin_req_first.hex */
    const val transferBeginReqFirst = "08 07 20 10"
    /** transfer_begin_req_resume.hex */
    const val transferBeginReqResume = "08 07 10 80 80 04 20 10 29 88 77 66 55 44 33 22 11 30 80 80 80 32"
    /** transfer_begin_rsp.hex */
    const val transferBeginRsp = "10 01 18 80 80 80 32 21 88 77 66 55 44 33 22 11 38 20 40 e7 01"
    /** transfer_error_event.hex */
    const val transferErrorEvent = "08 0a 10 01 18 b9 60"
}

/** 十六进制串("aa bb"/"aabb" 均可) -> 字节。 */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/** 字节 -> 小写空格分隔十六进制串(与 golden .hex 同型)。 */
fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString(" ") { b ->
        val v = b.toInt() and 0xFF
        (if (v < 0x10) "0" else "") + v.toString(16)
    }
