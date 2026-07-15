package io.bluetrace.shared.uwtp

/**
 * UWTP v0.2-draft 注册表常量(App 侧客户端).
 *
 * 真源分层(工作稿 §14.1): main_type/opcode/错误码的唯一真源 = 工作稿 Registry
 * (apollo4_watch_s7 Docs/08_采集V2_离线存储与通信/S7_UWTP_v0.2_工作稿.md);
 * PB 字段编号真源 = 上游 proto_v02 目录的 *.proto(本仓快照 shared/proto/uwtp_v02/).
 * 状态: DRAFT/Registered-Draft——v1.0 前编号可调, 调整须六项同步.
 */
object Uwtp {
    const val HEADER_LEN = 5
    const val HEADER_VER = 1

    /** payload 上限 = MTU-8(工作稿 §3); Core len 单字节, 绝对上限 255. */
    const val PAYLOAD_HARD_MAX = 255

    // ---- main_type ----
    const val MT_CTRL = 0x01
    const val MT_LIVE = 0x10
    const val MT_TRANSFER = 0x11

    // ---- CTRL opcode(§9.3) ----
    const val OP_CTRL_STATE_QUERY = 0x01
    const val OP_CTRL_INFO = 0x02

    // ---- LIVE opcode(§10.1) ----
    const val OP_LIVE_START = 0x02
    const val OP_LIVE_STOP = 0x03
    const val OP_LIVE_RAW_CHUNK = 0x11
    const val OP_LIVE_RESULT_BUNDLE = 0x12

    // ---- TRANSFER opcode(§12.2) ----
    const val OP_TRANSFER_OBJECT_LIST = 0x02
    const val OP_TRANSFER_BEGIN = 0x03
    const val OP_TRANSFER_DATA = 0x04
    const val OP_TRANSFER_ACK = 0x05
    const val OP_TRANSFER_FINISH = 0x06
    const val OP_TRANSFER_ABORT = 0x07
    const val OP_TRANSFER_DELETE = 0x09
    const val OP_TRANSFER_ERROR_EVENT = 0x10

    // ---- Common 错误码(§8.2, 低 16 bit; 高 8 bit=错误域) ----
    const val ERR_OK = 0x0000
    const val ERR_INVALID_REQUEST = 0x0001
    const val ERR_NOT_SUPPORTED = 0x0002
    const val ERR_INVALID_STATE = 0x0003
    const val ERR_BUSY = 0x0004
    const val ERR_INVALID_PARAM = 0x0005
    const val ERR_NOT_FOUND = 0x0006
    const val ERR_RESOURCE_EXHAUSTED = 0x0007
    const val ERR_NO_SPACE = 0x0008
    const val ERR_INTEGRITY_ERROR = 0x0009
    const val ERR_IO_ERROR = 0x000A
    const val ERR_TIMEOUT = 0x000B
    const val ERR_RETRY_LATER = 0x000C
    const val ERR_TOO_LARGE = 0x000D
    const val ERR_MTU_TOO_SMALL = 0x000E
    const val ERR_INTERNAL_ERROR = 0x000F

    /**
     * TRANSFER 域错误: 续传对象同一性校验不符(§8.5/D-10)。
     * DRAFT 编号——工作稿标注"编号待本域错误码正式登记时统一分配, 不预冻结 0x1101",
     * 联调基线暂用 0x1101, 固件侧改号时此处同步。
     */
    const val ERR_OBJECT_CHANGED = 0x1101

    private val commonNames = arrayOf(
        "OK", "INVALID_REQUEST", "NOT_SUPPORTED", "INVALID_STATE", "BUSY",
        "INVALID_PARAM", "NOT_FOUND", "RESOURCE_EXHAUSTED", "NO_SPACE", "INTEGRITY_ERROR",
        "IO_ERROR", "TIMEOUT", "RETRY_LATER", "TOO_LARGE", "MTU_TOO_SMALL", "INTERNAL_ERROR",
    )

    /** 错误码可读名(未知码回落十六进制)。 */
    fun errorName(code: Int): String = when {
        code in commonNames.indices -> commonNames[code]
        code == ERR_OBJECT_CHANGED -> "OBJECT_CHANGED"
        else -> "0x" + code.toString(16).uppercase()
    }

    // ---- 三个正交状态取值(§9.1) ----
    const val STATE_IDLE = 0
    const val STATE_ACTIVE = 1 // offline 域含义为 CAPTURING
}
