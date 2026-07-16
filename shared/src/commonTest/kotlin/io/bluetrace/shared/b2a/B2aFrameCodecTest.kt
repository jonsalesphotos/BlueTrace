package io.bluetrace.shared.b2a

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class B2aFrameCodecTest {

    /** 全套材料唯一完整 golden 帧：产测握手（spec §6）。短帧特例：uiLen=3 < 4B 命令头。 */
    private val goldenFactoryHandshake = byteArrayOf(
        0xBB.toByte(), 0x02, 0x03, 0x00, 0x2D, 0x46, 0x00, 0x00, 0x08, 0x01, 0x01,
    )

    @Test
    fun crc16CcittFalse_matchesGoldenFrame() {
        // CRC16-CCITT-FALSE("08 01 01") = 0x462D（审计已独立复算）
        assertEquals(0x462D, B2aCrc.crc16CcittFalse(byteArrayOf(0x08, 0x01, 0x01)))
        // 标准校验向量："123456789" → 0x29B1
        assertEquals(0x29B1, B2aCrc.crc16CcittFalse("123456789".encodeToByteArray()))
    }

    @Test
    fun decode_goldenShortFrame() {
        val decoder = B2aFrameDecoder()
        val msgs = decoder.feed(goldenFactoryHandshake)
        assertEquals(1, msgs.size)
        assertEquals(0x08, msgs[0].cmd) // BCMD_TEST
        assertEquals(0x01, msgs[0].key) // ENTER_FACTORY
        assertContentEquals(byteArrayOf(0x01), msgs[0].param) // 选择子 0x01=BLE
        assertEquals(0, decoder.crcErrors)
        assertEquals(0, decoder.frameErrors)
    }

    @Test
    fun encodeRequest_roundtrip() {
        val param = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val frame = B2aFrameCodec.encodeRequest(B2a.CMD_SET, B2a.KEY_DATE_TIME, param)
        // 帧头自检
        assertEquals(0xBB, frame[0].toInt() and 0xFF)
        assertEquals(B2aStatus.ACK, frame[1].toInt() and 0xFF)
        assertEquals(4 + 9, B2aFrameCodec.readLe16(frame, 2)) // uiLen = 命令头 + param
        val msgs = B2aFrameDecoder().feed(frame)
        assertEquals(1, msgs.size)
        assertEquals(B2a.CMD_SET, msgs[0].cmd)
        assertEquals(B2a.KEY_DATE_TIME, msgs[0].key)
        assertContentEquals(param, msgs[0].param)
    }

    @Test
    fun decode_multipleFramesInOneNotification() {
        val f1 = B2aFrameCodec.encodeResponse(B2a.CMD_GET, B2a.KEY_DEV_VOL, ByteArray(10) { it.toByte() })
        val f2 = B2aFrameCodec.encodeResponse(B2a.CMD_GET, B2a.KEY_BOND_STATE, byteArrayOf(1))
        val msgs = B2aFrameDecoder().feed(f1 + f2)
        assertEquals(2, msgs.size)
        assertEquals(B2a.KEY_DEV_VOL, msgs[0].key)
        assertEquals(B2a.KEY_BOND_STATE, msgs[1].key)
    }

    @Test
    fun decode_crcCorruption_dropsFrameKeepsNext() {
        val bad = B2aFrameCodec.encodeResponse(B2a.CMD_GET, B2a.KEY_DEV_VOL, byteArrayOf(9, 9))
        bad[9] = (bad[9] + 1).toByte() // 破坏 payload → CRC 失配
        val good = B2aFrameCodec.encodeResponse(B2a.CMD_GET, B2a.KEY_BOND_STATE, byteArrayOf(1))
        val decoder = B2aFrameDecoder()
        val msgs = decoder.feed(bad + good)
        assertEquals(1, msgs.size)
        assertEquals(B2a.KEY_BOND_STATE, msgs[0].key)
        assertEquals(1, decoder.crcErrors)
    }

    @Test
    fun decode_garbageSof_dropsRest() {
        val decoder = B2aFrameDecoder()
        val msgs = decoder.feed(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08))
        assertTrue(msgs.isEmpty())
        assertEquals(1, decoder.frameErrors)
    }

    @Test
    fun decode_multiPacketReassembly() {
        // 首片：命令头 + 前段；中片裸 payload；末片置 MULTI_PKT_END —— 多包 ID=2（bit[5:4]）
        val id = 2
        val statusFirst = B2aStatus.IS_MULTI_PKT or (id shl 4)
        val statusMid = B2aStatus.IS_MULTI_PKT or (id shl 4)
        val statusEnd = B2aStatus.IS_MULTI_PKT or B2aStatus.MULTI_PKT_END or (id shl 4)

        val part1 = ByteArray(100) { it.toByte() }
        val part2 = ByteArray(100) { (100 + it).toByte() }
        val part3 = ByteArray(50) { (200 + it).toByte() }
        val full = part1 + part2 + part3

        val head = ByteArray(4).also {
            it[0] = B2a.CMD_GET.toByte()
            it[1] = 0x25 // GET_DIAL_INFO（协议中真实的多包命令）
            B2aFrameCodec.writeLe16(it, 2, full.size)
        }
        val f1 = B2aFrameCodec.encodeFrame(statusFirst, 0, head + part1)
        val f2 = B2aFrameCodec.encodeFrame(statusMid, 1, part2)
        val f3 = B2aFrameCodec.encodeFrame(statusEnd, 2, part3)

        val decoder = B2aFrameDecoder()
        assertTrue(decoder.feed(f1).isEmpty())
        assertTrue(decoder.feed(f2).isEmpty())
        val msgs = decoder.feed(f3)
        assertEquals(1, msgs.size)
        assertEquals(0x25, msgs[0].key)
        assertContentEquals(full, msgs[0].param)
    }

    @Test
    fun decode_multiPacket_survivesInterleavedSingleFrame() {
        // 心跳等单帧插入多包序列是协议合法场景：不得清掉重组缓冲（评审修复 #2）
        val id = 1
        val part1 = ByteArray(60) { it.toByte() }
        val part2 = ByteArray(40) { (60 + it).toByte() }
        val head = ByteArray(4).also {
            it[0] = B2a.CMD_GET.toByte(); it[1] = 0x25
            B2aFrameCodec.writeLe16(it, 2, part1.size + part2.size)
        }
        val f1 = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or (id shl 4), 0, head + part1)
        val heartbeat = B2aFrameCodec.encodeResponse(B2a.CMD_IND, B2a.IND_HEARTBEAT, ByteArray(8))
        val fEnd = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or B2aStatus.MULTI_PKT_END or (id shl 4), 1, part2)

        val decoder = B2aFrameDecoder()
        assertTrue(decoder.feed(f1).isEmpty())
        val hb = decoder.feed(heartbeat)
        assertEquals(1, hb.size) // 心跳正常产出
        val msgs = decoder.feed(fEnd)
        assertEquals(1, msgs.size, "插帧后多包应仍完整重组")
        assertContentEquals(part1 + part2, msgs[0].param)
    }

    @Test
    fun decode_multiPacket_indexGap_dropsWhole() {
        // 中间片丢失（index 跳号）→ 整片丢弃，不得产出带洞消息（评审修复 #1）
        val id = 1
        val head = ByteArray(4).also {
            it[0] = B2a.CMD_GET.toByte(); it[1] = 0x25
            B2aFrameCodec.writeLe16(it, 2, 30)
        }
        val f1 = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or (id shl 4), 0, head + ByteArray(10))
        // index=2（跳过 1）的末片
        val fEnd = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or B2aStatus.MULTI_PKT_END or (id shl 4), 2, ByteArray(10))
        val decoder = B2aFrameDecoder()
        decoder.feed(f1)
        assertTrue(decoder.feed(fEnd).isEmpty())
        assertEquals(1, decoder.frameErrors)
    }

    @Test
    fun decode_multiPacket_declaredLenMismatch_dropsWhole() {
        // 首片声明 paramLen 与重组总长不符 → 整片丢弃（评审修复 #1 加固）
        val id = 0
        val head = ByteArray(4).also {
            it[0] = B2a.CMD_GET.toByte(); it[1] = 0x25
            B2aFrameCodec.writeLe16(it, 2, 999) // 谎报长度
        }
        val f1 = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or (id shl 4), 0, head + ByteArray(10))
        val fEnd = B2aFrameCodec.encodeFrame(B2aStatus.IS_MULTI_PKT or B2aStatus.MULTI_PKT_END or (id shl 4), 1, ByteArray(10))
        val decoder = B2aFrameDecoder()
        decoder.feed(f1)
        assertTrue(decoder.feed(fEnd).isEmpty())
        assertEquals(1, decoder.frameErrors)
    }

    @Test
    fun decode_multiPacketIdMismatch_dropsWhole() {
        val f1 = B2aFrameCodec.encodeFrame(
            B2aStatus.IS_MULTI_PKT or (1 shl 4), 0,
            ByteArray(4).also { it[0] = 0x02; it[1] = 0x25; B2aFrameCodec.writeLe16(it, 2, 8) } + ByteArray(4),
        )
        val f2wrongId = B2aFrameCodec.encodeFrame(
            B2aStatus.IS_MULTI_PKT or B2aStatus.MULTI_PKT_END or (3 shl 4), 1, ByteArray(4),
        )
        val decoder = B2aFrameDecoder()
        decoder.feed(f1)
        val msgs = decoder.feed(f2wrongId)
        assertTrue(msgs.isEmpty())
        assertEquals(1, decoder.frameErrors)
    }

    @Test
    fun decode_truncatedFrame_countsError() {
        val frame = B2aFrameCodec.encodeResponse(B2a.CMD_GET, B2a.KEY_DEV_VOL, ByteArray(10))
        val decoder = B2aFrameDecoder()
        val msgs = decoder.feed(frame.copyOfRange(0, frame.size - 3)) // 截尾
        assertTrue(msgs.isEmpty())
        assertEquals(1, decoder.frameErrors)
    }
}
