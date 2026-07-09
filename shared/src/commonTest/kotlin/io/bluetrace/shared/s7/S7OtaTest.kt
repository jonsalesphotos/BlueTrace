package io.bluetrace.shared.s7

import io.bluetrace.shared.ble.BleClient
import io.bluetrace.shared.ble.BleNotification
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.virtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class S7OtaTest {

    // ---- 纯编解码（无协程） ----

    @Test
    fun fragmenter_roundTrips_through_decoder() {
        val dec = S7FrameDecoder()
        // 各尺寸：空 / 单帧 / 恰边界 / 跨多帧 / 满 17 帧
        for (n in listOf(0, 1, 4, 5, 8, 9, 40, 41, 68)) {
            val param = ByteArray(n) { (it * 7 + 3).toByte() }
            val frames = S7FrameCodec.encodeMultiPacket(0x0F, S7FileTrans.KEY_TRANS, param, maxParamPerFrame = 4, multiPktId = 0)
            val msgs = frames.flatMap { dec.feed(it) }
            assertEquals(1, msgs.size, "n=$n 应重组出 1 条消息, 实得 ${msgs.size}")
            val m = msgs[0]
            assertEquals(0x0F, m.cmd)
            assertEquals(S7FileTrans.KEY_TRANS, m.key)
            assertTrue(param.contentEquals(m.param), "n=$n param 重组不一致")
        }
    }

    @Test
    fun fragmenter_frameCount_matches_17_at_boundary() {
        // maxParamPerFrame=4, 17 帧上限 → 68 字节切片正好 17 帧
        val frames = S7FrameCodec.encodeMultiPacket(0x0F, 0x02, ByteArray(68), maxParamPerFrame = 4)
        assertEquals(17, frames.size)
    }

    @Test
    fun additiveChecksum_isU32ByteSum() {
        assertEquals(0L, S7FileTrans.additiveChecksum(ByteArray(0)))
        assertEquals(6L, S7FileTrans.additiveChecksum(byteArrayOf(1, 2, 3)))
        // 0xFF*4 = 1020, 不溢出 32 位
        assertEquals(1020L, S7FileTrans.additiveChecksum(ByteArray(4) { 0xFF.toByte() }))
    }

    /** BUG-1（golden 日志 2026-07-08 ota.log）：每帧 param = MTU−15，满切片 = (MTU−15)×17，上链帧不越 MTU−3。 */
    @Test
    fun sliceFragmentation_respectsMtuMinus15_perGoldenLog() {
        val mtu = 247
        assertEquals(232, S7FileTrans.maxParamPerFrame(mtu), "每帧 param 应 = MTU−15 = 232（真机 ParamPktLen:232）")
        assertEquals(3944, S7FileTrans.defaultSliceMaxSize(mtu), "满切片应 = 232×17 = 3944（真机 SliceMaxSize:3944）")
        // 一个满切片(3944B)分片：恰 17 帧（固件 MAC_SLICE_CNT 硬限），每帧上链(含 3B ATT 写头)不越 MTU
        val frames = S7FileTrans.encodeSlice(ByteArray(S7FileTrans.defaultSliceMaxSize(mtu)), mtu)
        assertEquals(17, frames.size, "满切片应恰 17 帧")
        val attPayloadMax = mtu - S7FileTrans.ATT_HEADER // 244 = 单次 GATT 写可用载荷
        for (f in frames) assertTrue(f.size <= attPayloadMax, "帧上链 ${f.size}B 越过 ATT 载荷上限 ${attPayloadMax}B")
        assertEquals(attPayloadMax, frames.first().size, "首帧应恰用满 MTU−3 载荷（旧 MTU−12 会到 247 → 越界）")
    }

    @Test
    fun protocol_encode_parse_roundTrips() {
        // REQ
        val reqFrames = S7FileTrans.encodeReq(S7FileTrans.MODULE_OTA, fileCount = 4, totalSize = 19_760_000L)
        val reqMsg = S7FrameDecoder().feed(reqFrames).single()
        assertEquals(0x0F, reqMsg.cmd); assertEquals(S7FileTrans.KEY_REQ, reqMsg.key)
        assertEquals(S7FileTrans.MODULE_OTA, reqMsg.param[0].toInt())
        assertEquals(4, reqMsg.param[2].toInt())

        // REQ 应答 12B
        val rr = OtaReqReply(S7FileTrans.REQ_OK, S7FileTrans.MODULE_OTA, 4, 1, 3944, 512)
        val parsed = S7FileTrans.parseReqReply(S7FrameDecoder().feed(S7FileTrans.encodeReqReply(rr)).single().param)
        assertEquals(rr, parsed)

        // START
        val startMsg = S7FrameDecoder().feed(
            S7FileTrans.encodeStart("ResCheck.dat", fileSize = 4440, sliceSize = 3944, fileType = S7FileTrans.FT_RES),
        ).single()
        assertEquals(S7FileTrans.KEY_START, startMsg.key)
        assertEquals(4440L, readLe32(startMsg.param, 0))
        assertEquals(S7FileTrans.FT_RES, startMsg.param[12].toInt())
        val nameLen = S7FrameCodec.readLe16(startMsg.param, 14)
        assertEquals("ResCheck.dat", startMsg.param.decodeToString(16, 16 + nameLen))

        // 9B DATA ack
        val ack = OtaDataAck(recvLen = 3944, checkSum = 123456, status = S7Status.SUCC)
        val ackParsed = S7FileTrans.parseDataAck(S7FrameDecoder().feed(S7FileTrans.encodeDataAck(ack)).single().param)
        assertEquals(ack, ackParsed)

        // OFFSET
        val offParsed = S7FileTrans.parseOffset(S7FrameDecoder().feed(S7FileTrans.encodeOffsetReply(8192)).single().param)
        assertEquals(8192L, offParsed)
    }

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    // ---- 端到端（Mock 手表 fileTrans 状态机） ----

    /** 直连 [S7MockWatch] 的最小 BleClient：write→watch.handle→异步回 notify。 */
    private class FakeOtaBle(
        val watch: S7MockWatch,
        private val clock: EpochClock,
        private val scope: CoroutineScope,
        private val mtu: Int = 247,
    ) : BleClient {
        val link = MutableStateFlow(LinkState.CONNECTED)

        /** 注入：REQ 应答额外延迟（模拟设备端 MMI 人工授权时延，测 EC-1 超时分层）。 */
        var reqReplyDelayMs: Long = 0
        private val inbound = MutableSharedFlow<BleNotification>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override fun scan(): Flow<List<ScannedDevice>> = emptyFlow()
        override suspend fun connect(device: ScannedDevice) {}
        override suspend fun disconnect(deviceId: String) { link.value = LinkState.DISCONNECTED }
        override fun linkState(deviceId: String): StateFlow<LinkState> = link
        override fun negotiatedMtu(deviceId: String): Int = mtu
        override fun notifications(deviceId: String): Flow<BleNotification> = inbound
        override suspend fun write(deviceId: String, bytes: ByteArray) {
            if (link.value != LinkState.CONNECTED) return
            // 单帧 REQ：payload 起于偏移 8，cmd@8/key@9
            val isReq = bytes.size >= 10 && (bytes[8].toInt() and 0xFF) == S7.CMD_FILE_TRANS &&
                (bytes[9].toInt() and 0xFF) == S7FileTrans.KEY_REQ
            val reply = watch.handle(bytes)
            scope.launch {
                if (isReq && reqReplyDelayMs > 0) delay(reqReplyDelayMs)
                for (f in reply.frames) {
                    delay(1) // 保证会话 collector 先订阅再收 ack（虚拟时间零成本）
                    inbound.emit(BleNotification(deviceId, clock.nowMs(), f))
                }
            }
        }
    }

    private fun pkg(vararg files: Pair<String, Int>) = OtaPackage(
        files = files.map { (name, size) ->
            OtaFile(name, ByteArray(size) { (it * 31 + name.length).toByte() }, S7FileTrans.FT_RES)
        },
    )

    private fun kotlinx.coroutines.test.TestScope.session(ble: FakeOtaBle): S7OtaSession =
        S7OtaSession(ble, "s7-fcc4", backgroundScope, virtualClock { testScheduler.currentTime })

    @Test
    fun provision_twoFiles_deliversAllBytes_andCompletes() = runTest {
        val ble = FakeOtaBle(S7MockWatch(virtualClock { testScheduler.currentTime }), virtualClock { testScheduler.currentTime }, backgroundScope)
        val p = pkg("fw.dat" to 5000, "ResCheck.dat" to 300) // fw 跨多切片
        var lastProgress: OtaProgress? = null

        val result = session(ble).provision(p) { lastProgress = it }

        assertIs<OtaResult.DoneDownload>(result)
        assertTrue(ble.watch.otaComplete, "整包应收讫")
        assertEquals(2, ble.watch.otaReceivedFiles.size)
        for (f in p.files) {
            assertTrue(f.bytes.contentEquals(ble.watch.otaReceivedFiles[f.name]), "${f.name} 字节不一致")
        }
        assertEquals(p.totalBytes, lastProgress?.sentBytes)
    }

    @Test
    fun provision_multiSlice_singleBigFile() = runTest {
        val ble = FakeOtaBle(S7MockWatch(virtualClock { testScheduler.currentTime }), virtualClock { testScheduler.currentTime }, backgroundScope)
        // sliceMax=3944（默认 MTU 247）→ 10000 字节 = 3 切片（3944+3944+2112）
        val p = pkg("ResData.dat" to 10000)
        val result = session(ble).provision(p)
        assertIs<OtaResult.DoneDownload>(result)
        assertTrue(p.files[0].bytes.contentEquals(ble.watch.otaReceivedFiles["ResData.dat"]))
    }

    @Test
    fun provision_retriesOnBadChecksum_thenSucceeds() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaCorruptSlices = 1 // 首切片回坏校验和一次 → 触发一次重传
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val p = pkg("fw.dat" to 2000)
        val result = session(ble).provision(p)
        assertIs<OtaResult.DoneDownload>(result)
        assertTrue(p.files[0].bytes.contentEquals(watch.otaReceivedFiles["fw.dat"]), "重传后字节应正确且不重复")
    }

    @Test
    fun provision_failsWhenReqRejectedBusy() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaRejectReq = S7FileTrans.REQ_BUSY
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val result = session(ble).provision(pkg("fw.dat" to 100))
        val failed = assertIs<OtaResult.Failed>(result)
        assertEquals(OtaFailure.ReqRejected(S7FileTrans.REQ_BUSY), failed.reason)
    }

    @Test
    fun provision_failsWhenNotConnected() = runTest {
        val ble = FakeOtaBle(S7MockWatch(virtualClock { testScheduler.currentTime }), virtualClock { testScheduler.currentTime }, backgroundScope)
        ble.link.value = LinkState.DISCONNECTED
        val result = session(ble).provision(pkg("fw.dat" to 100))
        val failed = assertIs<OtaResult.Failed>(result)
        assertIs<OtaFailure.NotConnected>(failed.reason)
    }

    /** EC-7：会话按设备 REQ 回的 sliceMaxSize 分片（1000 < 本地默认 3944 → 采信小值），而非本地 MTU 猜值。 */
    @Test
    fun provision_honorsDeviceReportedSliceMax_notLocalGuess() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaSliceMax = 1000 // 设备回 1000（< 本地默认 3944）
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val result = session(ble).provision(pkg("ResData.dat" to 3000))
        assertIs<OtaResult.DoneDownload>(result)
        // 若会话忽略设备值改用本地 3944，则 3000B = 单切片 [3000]；honors 1000 → [1000,1000,1000]
        assertEquals(listOf(1000, 1000, 1000), watch.otaSliceLog)
    }

    /**
     * DV-6/BUG-2 分支(1)：设备回 sliceMax > 本地分帧容量（本地 MTU 低报场景）→ 会话向下钳到 localCap，
     * 防一切片超固件 17 包硬限。删掉 `.coerceAtMost(localCap)` 此测即挂（EC-7 的 dev<local 挡不住此向）。
     */
    @Test
    fun provision_clampsDeviceSliceMax_downToLocalCap() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaSliceMax = 8000 // 设备回 8000 > 本地默认 localCap 3944（MTU 247）
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val result = session(ble).provision(pkg("ResData.dat" to 9000))
        assertIs<OtaResult.DoneDownload>(result)
        // 采信 8000 会切成 [8000,1000]（且 8000B 需 ⌈8000/232⌉=35 帧 > 17 硬限）；钳到 3944 → [3944,3944,1112]
        assertEquals(listOf(3944, 3944, 1112), watch.otaSliceLog)
    }

    /** BUG-2 防御：REQ 应答为非 12B 短帧（真机疑似 8B 回显）→ 会话不 abort，按本地 sliceMax(3944) 完成。 */
    @Test
    fun provision_survivesShortReqReply_usesLocalSliceMax() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaShortReqReply = true // 回 8B 回显 → parseReqReply=null
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val p = pkg("ResData.dat" to 9000)
        val result = session(ble).provision(p)
        assertIs<OtaResult.DoneDownload>(result)
        assertTrue(p.files[0].bytes.contentEquals(watch.otaReceivedFiles["ResData.dat"]), "短应答下字节仍应完整")
        // 无设备 sliceMax → 本地 3944 作权威：9000 = [3944,3944,1112]
        assertEquals(listOf(3944, 3944, 1112), watch.otaSliceLog)
    }

    /** 切片重传耗尽 → SliceFailed，offset 指向失败切片起点（非 0，用多切片文件）。 */
    @Test
    fun provision_failsWithSliceFailed_whenRetriesExhausted() = runTest {
        val watch = S7MockWatch(virtualClock { testScheduler.currentTime })
        watch.otaSliceMax = 1000
        watch.otaFailAtOffset = 1000 // 第二切片(offset 1000)恒 NAK → 3 次重传全败
        val ble = FakeOtaBle(watch, virtualClock { testScheduler.currentTime }, backgroundScope)
        val result = session(ble).provision(pkg("fw.dat" to 2500))
        val failed = assertIs<OtaResult.Failed>(result)
        assertEquals(OtaFailure.SliceFailed("fw.dat", 1000), failed.reason)
    }

    /** EC-1：REQ 授权时延（10s，> cmdAck 5s）仍成功 → 证明 REQ 走 60s 授权窗口而非 5s 命令超时。 */
    @Test
    fun provision_reqSurvivesAuthorizationDelayBeyondCmdTimeout() = runTest {
        val ble = FakeOtaBle(S7MockWatch(virtualClock { testScheduler.currentTime }), virtualClock { testScheduler.currentTime }, backgroundScope)
        ble.reqReplyDelayMs = 10_000 // 10s > cmdAckTimeoutMs(5s)，< authorizeTimeoutMs(60s)
        val result = session(ble).provision(pkg("fw.dat" to 300))
        assertIs<OtaResult.DoneDownload>(result)
    }

    /** EC-1：授权超 authorizeTimeoutMs → OtaFailure.Timeout("REQ")。 */
    @Test
    fun provision_reqTimesOutWhenAuthorizationExceedsWindow() = runTest {
        val ble = FakeOtaBle(S7MockWatch(virtualClock { testScheduler.currentTime }), virtualClock { testScheduler.currentTime }, backgroundScope)
        ble.reqReplyDelayMs = 5_000
        val session = S7OtaSession(
            ble, "s7-fcc4", backgroundScope, virtualClock { testScheduler.currentTime },
            authorizeTimeoutMs = 2_000, // 授权窗口 2s < 应答时延 5s
        )
        val result = session.provision(pkg("fw.dat" to 300))
        val failed = assertIs<OtaResult.Failed>(result)
        assertEquals(OtaFailure.Timeout("REQ"), failed.reason)
    }
}
