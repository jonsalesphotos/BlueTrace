package io.bluetrace.shared.b2a

import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.epochMsToLocalParts

/** 模拟应答：回传帧序列 + 是否随后断链（关机/重启/恢复出厂不回包、以断链收尾）。 */
data class B2aMockReply(
    val frames: List<ByteArray>,
    val disconnectAfter: Boolean = false,
)

/**
 * S7 手表设备侧模拟器（commonMain）：按 B2A 协议应答维护命令。
 * 双用途：MockBleClient 的 S7 数据源（真机到位前联调控制台）+ commonTest 闭环夹具。
 *
 * 模拟状态刻意做了「有偏差」：设备时钟比手机慢 [clockDriftMs]，便于验证对时前后 UI 偏差显示。
 */
class B2aMockWatch(
    private val clock: EpochClock,
    /** 模拟设备时钟漂移（设备时间 = 手机时间 + drift；默认慢 97s，对时后归零）。 */
    private var clockDriftMs: Long = -97_000,
    /** 模拟本地时区偏移秒（默认东八区）。 */
    private val zoneOffsetSeconds: Int = 8 * 3600,
) {
    private val decoder = B2aFrameDecoder()

    // 可变设备态
    private var person = B2aPerson(heightCm = 175, weightKg = 68, gender = 1, birthYear = 1993, birthMonth = 6, birthDay = 12)
    private var batteryPercent = 82
    private var finding = false
    var heartbeatSeq = 0
        private set

    // ---- FILE_TRANS / OTA 模拟态 ----
    private var otaFileCount = 0
    private var otaCurrFileIdx = 0
    private var otaCurName: String? = null
    private var otaCurBuf = ArrayList<Byte>()
    private val otaFiles = LinkedHashMap<String, ByteArray>()

    /** 整包收讫（末文件 STOP 后置位；测试断言用）。 */
    var otaComplete = false
        private set

    /** 已完整收到的文件（name→bytes；测试比对推包正确性）。 */
    val otaReceivedFiles: Map<String, ByteArray> get() = otaFiles

    /** 设备回报的 sliceMaxSize（默认 = MTU 247 口径 (247−15)×17 = 3944；REQ 应答带回）。 */
    var otaSliceMax: Int = B2aFileTrans.defaultSliceMaxSize(247)

    /** 注入：REQ 拒绝状态（null=OK；测 disk_full/busy 分支）。 */
    var otaRejectReq: Int? = null

    /**
     * 注入：REQ 应答回 **非 12B 短帧**（模拟真机疑似 8B 回显，测 BUG-2 防御分支）。
     * 会话应据此 parse=null → 按已授权继续、sliceMax 用本地算值，不 abort。
     * 8B 布局仿 golden 日志 `01 00 0e 00 8f 6e 6c 01`：moduleId/isOffset/fileCount(LE16)/totalSize(LE32)。
     */
    var otaShortReqReply: Boolean = false

    /** 注入：末文件 STOP(整包完成)后随 ack 断链，模拟设备自复位（测 [OtaProvisioner] 的等复位→重连闭环）。 */
    var otaRebootAfterComplete: Boolean = false

    /** 注入：接下来 N 个切片回坏校验和且不落盘（模拟 NAK，测重传）。 */
    var otaCorruptSlices: Int = 0

    /** 注入：当前文件在此累计偏移的切片恒回坏校验和（永不通过，测切片重传耗尽 → SliceFailed）。null=不注入。 */
    var otaFailAtOffset: Long? = null

    /** 记录每个成功落盘切片的字节长（测 EC-7：会话是否按设备回的 sliceMaxSize 分片）。REQ 时清空。 */
    private val otaSliceSizes = ArrayList<Int>()
    val otaSliceLog: List<Int> get() = otaSliceSizes

    private val deviceInfo = B2aDeviceInfo(swVer = "1.2.7.0", modemVer = "1.0", secBlVer = "1.0", bpVer = "23")
    private val snBytes: ByteArray = ByteArray(59).also {
        // DevType[5] = 二进制型号码（真机口径：68 39 71 25 81 = overseas device_type）
        byteArrayOf(0x68, 0x39, 0x71, 0x25, 0x81.toByte()).copyInto(it, 0)
        "SN2407FCC4AB".encodeToByteArray().copyInto(it, 5) // SN[12]
        // BleMac[6] = TEST_DUT_MAC（71:61:48:19:FC:C4）按 **LE 反序** 填充（真机实证 2026-07-02）
        byteArrayOf(0xC4.toByte(), 0xFC.toByte(), 0x19, 0x48, 0x61, 0x71).copyInto(it, 17)
        "861234056789012".encodeToByteArray().copyInto(it, 23) // IMEI[16] 15 位 + 0
        "89860425987654321098".encodeToByteArray().copyInto(it, 39) // ICCID[20]
    }

    /** 模拟设备日志（now + bak 两文件拼接语义；数块回传）。 */
    private val logContent: ByteArray = buildString {
        repeat(48) { i ->
            append("[00:0${i / 10}:${i % 10}0.${i % 10}] I/sys boot stage $i ok, heap=${180 - i}k, ble=conn\n")
        }
        append("---- eiotlog2.log (bak) ----\n")
        repeat(24) { i ->
            append("[bak:$i] W/batt vol=41${i % 10}0mV temp=3${i % 3}C\n")
        }
    }.encodeToByteArray()

    /** 处理一次下行写（可含多帧），产出应答。 */
    fun handle(bytes: ByteArray): B2aMockReply {
        val frames = ArrayList<ByteArray>()
        var disconnect = false
        for (msg in decoder.feed(bytes)) {
            val reply = dispatch(msg) ?: continue
            frames.addAll(reply.frames)
            disconnect = disconnect || reply.disconnectAfter
        }
        return B2aMockReply(frames, disconnect)
    }

    /** 生成一条心跳帧（MockBleClient 周期调用）。 */
    fun heartbeatFrame(): ByteArray {
        heartbeatSeq++
        val utcSec = clock.nowMs().plus(clockDriftMs) / 1000
        val p = ByteArray(8)
        p[0] = (utcSec and 0xFF).toByte()
        p[1] = ((utcSec shr 8) and 0xFF).toByte()
        p[2] = ((utcSec shr 16) and 0xFF).toByte()
        p[3] = ((utcSec shr 24) and 0xFF).toByte()
        B2aFrameCodec.writeLe16(p, 4, heartbeatSeq and 0xFFFF)
        p[6] = batteryPercent.toByte()
        return B2aFrameCodec.encodeResponse(B2a.CMD_IND, B2a.IND_HEARTBEAT, p)
    }

    private fun dispatch(msg: B2aMessage): B2aMockReply? = when (msg.cmd) {
        B2a.CMD_GET -> B2aMockReply(listOf(get(msg.key)))
        B2a.CMD_SET -> set(msg)
        B2a.CMD_DEV_CTRL -> devCtrl(msg)
        B2a.CMD_FILE_TRANS -> fileTrans(msg)
        else -> B2aMockReply(listOf(commAck(msg.cmd, msg.key, 0x05))) // NOT_SUPPORT
    }

    /**
     * FILE_TRANS/OTA 会话（reassemble 后逐子命令处理；TRANS 的 param 已是整切片）。
     * 应答 cmd/key 回显 0x0F/子键（Mock 约定，真机 ack 帧口径待抓包核，见 implementation-notes）。
     */
    private fun fileTrans(msg: B2aMessage): B2aMockReply = when (msg.key) {
        B2aFileTrans.KEY_REQ -> {
            val p = msg.param
            otaFileCount = if (p.size > 2) p[2].toInt() and 0xFF else 0
            otaCurrFileIdx = 0
            otaComplete = false
            otaFiles.clear()
            otaCurName = null
            otaCurBuf = ArrayList()
            otaSliceSizes.clear()
            if (otaShortReqReply) {
                // 8B 回显短应答（无 sliceMaxSize/status）：moduleId/isOffset/fileCount(LE16)/rsv。
                // 仿 golden 日志 `01 00 0e 00 ...`；会话应 parse=null → 本地算 sliceMax、不 abort。
                val echo = ByteArray(8)
                echo[0] = (if (p.isNotEmpty()) p[0].toInt() and 0xFF else B2aFileTrans.MODULE_OTA).toByte()
                B2aFrameCodec.writeLe16(echo, 2, otaFileCount)
                B2aMockReply(listOf(B2aFrameCodec.encodeResponse(B2a.CMD_FILE_TRANS, B2aFileTrans.KEY_REQ, echo)))
            } else {
                val status = otaRejectReq ?: B2aFileTrans.REQ_OK
                val reply = OtaReqReply(
                    status = status,
                    moduleId = if (p.isNotEmpty()) p[0].toInt() and 0xFF else B2aFileTrans.MODULE_OTA,
                    fileCount = otaFileCount,
                    currFileIdx = 0,
                    sliceMaxSize = otaSliceMax,
                    offset = 0,
                )
                B2aMockReply(listOf(B2aFileTrans.encodeReqReply(reply)))
            }
        }
        B2aFileTrans.KEY_START -> {
            val p = msg.param
            val name = if (p.size >= 16) {
                val nameLen = B2aFrameCodec.readLe16(p, 14)
                if (nameLen in 1..(p.size - 16)) p.decodeToString(16, 16 + nameLen) else "?"
            } else "?"
            otaCurName = name
            otaCurBuf = ArrayList()
            B2aMockReply(listOf(B2aFileTrans.encodeCommAck(B2aFileTrans.KEY_START, 0x00)))
        }
        B2aFileTrans.KEY_TRANS -> {
            val slice = msg.param
            val nak = otaCorruptSlices > 0 || otaFailAtOffset == otaCurBuf.size.toLong()
            if (nak) {
                if (otaCorruptSlices > 0) otaCorruptSlices-- // 模拟 NAK：坏校验和 + 不落盘（offset 不进），逼 App 重传
                val bad = OtaDataAck(recvLen = slice.size.toLong(), checkSum = B2aFileTrans.additiveChecksum(slice) xor 0xFFFF, status = B2aStatus.SUCC)
                B2aMockReply(listOf(B2aFileTrans.encodeDataAck(bad)))
            } else {
                for (b in slice) otaCurBuf.add(b)
                otaSliceSizes.add(slice.size)
                val ack = OtaDataAck(recvLen = slice.size.toLong(), checkSum = B2aFileTrans.additiveChecksum(slice), status = B2aStatus.SUCC)
                B2aMockReply(listOf(B2aFileTrans.encodeDataAck(ack)))
            }
        }
        B2aFileTrans.KEY_STOP -> {
            otaCurName?.let { otaFiles[it] = otaCurBuf.toByteArray() }
            otaCurrFileIdx++
            val complete = otaCurrFileIdx >= otaFileCount
            if (complete) otaComplete = true
            B2aMockReply(
                listOf(B2aFileTrans.encodeCommAck(B2aFileTrans.KEY_STOP, 0x00)),
                disconnectAfter = complete && otaRebootAfterComplete,
            )
        }
        B2aFileTrans.KEY_OFFSET -> B2aMockReply(listOf(B2aFileTrans.encodeOffsetReply(otaCurBuf.size.toLong())))
        else -> B2aMockReply(listOf(commAck(B2a.CMD_FILE_TRANS, msg.key, 0x05)))
    }

    /** 被 SET 后记住的设备时间（冻结回读，便于测试自定义对时/跨时区）；null=用内部时钟。 */
    private var overrideDateTime: B2aDateTime? = null

    private fun get(key: Int): ByteArray = when (key) {
        B2a.KEY_DATE_TIME -> {
            val dt = overrideDateTime ?: run {
                val parts = epochMsToLocalParts(clock.nowMs() + clockDriftMs, zoneOffsetSeconds)
                B2aDateTime(
                    parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second,
                    week = 1, timezone = zoneOffsetSeconds / 3600,
                )
            }
            B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, dt.encode())
        }
        B2a.KEY_PERSON_DATA -> B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, person.encodeSet().copyOfRange(0, 7))
        B2a.KEY_DEV_INFO -> B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, deviceInfo.encode())
        B2a.KEY_DEV_FUNC -> B2aFrameCodec.encodeResponse(
            B2a.CMD_GET, key,
            byteArrayOf(0x5F, 0x03, 0x07, 0x00), // 0x0007035F：掩码逐位含义文档缺失，仅展示
        )
        B2a.KEY_DEV_BLE_MAC -> B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, snBytes.copyOfRange(17, 23))
        B2a.KEY_DEV_VOL -> {
            val p = ByteArray(10)
            B2aFrameCodec.writeLe16(p, 0, 280) // capacity mAh
            B2aFrameCodec.writeLe16(p, 2, 4130) // mV
            p[4] = batteryPercent.toByte()
            p[5] = 0 // 未充电
            B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, p)
        }
        B2a.KEY_SN_INFO -> B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, snBytes)
        B2a.KEY_BOND_STATE -> B2aFrameCodec.encodeResponse(B2a.CMD_GET, key, byteArrayOf(0x01))
        else -> commAck(B2a.CMD_GET, key, 0x05) // NOT_SUPPORT
    }

    private fun set(msg: B2aMessage): B2aMockReply {
        val status = when (msg.key) {
            B2a.KEY_DATE_TIME -> {
                val dt = B2aDateTime.parse(msg.param)
                if (dt == null) 0x06 else {
                    overrideDateTime = dt // 记住被设置的时间（冻结回读，测自定义对时/跨时区/过零点）
                    clockDriftMs = 0
                    0x00
                }
            }
            B2a.KEY_PERSON_DATA -> {
                val p = B2aPerson.parse(msg.param)
                if (p == null) 0x06 else {
                    person = p
                    0x00
                }
            }
            else -> 0x05
        }
        return B2aMockReply(listOf(commAck(B2a.CMD_SET, msg.key, status)))
    }

    private fun devCtrl(msg: B2aMessage): B2aMockReply = when (msg.key) {
        // 关机/重启/恢复出厂：固件强制不回包 → 仅断链
        B2a.CTRL_POWER_OFF, B2a.CTRL_RESET, B2a.CTRL_RESTORE -> B2aMockReply(emptyList(), disconnectAfter = true)
        B2a.CTRL_FIND -> {
            finding = true
            B2aMockReply(listOf(commAck(B2a.CMD_DEV_CTRL, msg.key, 0x00)))
        }
        B2a.CTRL_FIND_END -> {
            finding = false
            B2aMockReply(listOf(commAck(B2a.CMD_DEV_CTRL, msg.key, 0x00)))
        }
        B2a.CTRL_FILE_LOG -> logChunks(msg.param)
        else -> B2aMockReply(listOf(commAck(B2a.CMD_DEV_CTRL, msg.key, 0x05)))
    }

    /** 日志回传：块 = 请求 payload 逐字节回显（szReqPrefix）+ 裸日志片；无 EOF 帧（协议如此）。 */
    private fun logChunks(reqPayload: ByteArray): B2aMockReply {
        if (reqPayload.isEmpty() || reqPayload[0].toInt() != 1) {
            return B2aMockReply(listOf(commAck(B2a.CMD_DEV_CTRL, B2a.CTRL_FILE_LOG, 0x06)))
        }
        val chunkSize = 200
        val frames = ArrayList<ByteArray>()
        var off = 0
        while (off < logContent.size) {
            val n = (logContent.size - off).coerceAtMost(chunkSize)
            val param = ByteArray(reqPayload.size + n)
            reqPayload.copyInto(param, 0)
            logContent.copyInto(param, reqPayload.size, off, off + n)
            frames.add(B2aFrameCodec.encodeResponse(B2a.CMD_DEV_CTRL, B2a.CTRL_ACK_FILE_LOG, param))
            off += n
        }
        return B2aMockReply(frames)
    }

    private fun commAck(cmd: Int, key: Int, code: Int): ByteArray =
        B2aFrameCodec.encodeResponse(cmd, key, byteArrayOf(code.toByte()))
}
