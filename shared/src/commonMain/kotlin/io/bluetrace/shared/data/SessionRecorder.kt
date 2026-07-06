package io.bluetrace.shared.data

import io.bluetrace.shared.domain.CollectType
import io.bluetrace.shared.domain.DecodedStream
import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.SessionFile
import io.bluetrace.shared.domain.SessionFileCategory
import io.bluetrace.shared.protocol.DecodedSample
import io.bluetrace.shared.protocol.toHexLine
import okio.FileSystem
import okio.Path

/**
 * 一次会话的落盘编排（D-6）：原始 HEX（按来源 dut/reference 分）+ 每模块解码 CSV + 组合包 CSV
 * + 标签 CSV + GNSS CSV。原始 HEX 是 source of truth；CSV 是派生。
 *
 * 纯 commonMain（注入 okio [FileSystem]），可 JVM 单测（FakeFileSystem）。
 */
class SessionRecorder(
    private val fileSystem: FileSystem,
    private val layout: SessionLayout,
    enabledTypes: Set<CollectType>,
    gnssEnabled: Boolean,
) {
    private var enabledStreams: Set<DecodedStream> =
        enabledTypes.map { DecodedStream.ofCollectType(it) }.toSet()

    private var gnssEnabled: Boolean = gnssEnabled

    /** 采集中重选采集类型（运行C 重开，D-V4-12）：之后启用路才落 CSV。 */
    fun setEnabledTypes(types: Set<CollectType>) {
        enabledStreams = types.map { DecodedStream.ofCollectType(it) }.toSet()
    }

    /** 采集中开关本机 GNSS 落盘（运行C 勾选 GNSS，§6.5）：关→后续 recordGps 跳过；开→续写 gps.csv。 */
    fun setGnssEnabled(on: Boolean) { gnssEnabled = on }

    private val rawWriters = HashMap<DeviceKind, RawHexWriter>()
    private val csvWriters = HashMap<DecodedStream, CsvWriter>()
    private var comboWriter: CsvWriter? = null
    private var labelWriter: CsvWriter? = null
    private var gpsWriter: CsvWriter? = null

    private var lastPpgG: Int? = null
    private var lastPpgIr: Int? = null

    /** 累计原始行数（= 实时流 Datas 计数）。 */
    var rawLineCount: Long = 0L
        private set

    init {
        fileSystem.createDirectories(layout.rawDir)
        fileSystem.createDirectories(layout.csvDir)
    }

    /** 记一条原始 Notify：写 `raw/<dut|reference>.hexlog`，返回实时流展示的 HEX（不含时间）。 */
    fun recordRaw(kind: DeviceKind, epochMs: Long, rawBytes: ByteArray): String {
        val writer = rawWriters.getOrPut(kind) {
            RawHexWriter(fileSystem, layout.rawHex(kind))
        }
        val hex = rawBytes.toHexLine()
        writer.writeLine(epochMs, hex)
        rawLineCount++
        return hex
    }

    /** 记解码样本：HR 始终落 `hr.csv`；其余仅启用路落盘；ACC 触发组合包 CSV 行。 */
    fun recordSamples(samples: List<DecodedSample>) {
        for (s in samples) {
            when (s.stream) {
                DecodedStream.HR -> writeCsv(DecodedStream.HR, s)
                DecodedStream.PPG_G -> {
                    lastPpgG = s.channels.firstOrNull()
                    if (s.stream in enabledStreams) writeCsv(s.stream, s)
                }
                DecodedStream.PPG_IR -> {
                    lastPpgIr = s.channels.firstOrNull()
                    if (s.stream in enabledStreams) writeCsv(s.stream, s)
                }
                DecodedStream.ACC -> {
                    if (s.stream in enabledStreams) writeCsv(s.stream, s)
                    writeCombo(s)
                }
                else -> if (s.stream in enabledStreams) writeCsv(s.stream, s)
            }
        }
    }

    private fun writeCsv(stream: DecodedStream, s: DecodedSample) {
        val writer = csvWriters.getOrPut(stream) {
            CsvWriter(fileSystem, layout.csv(stream.csvName), listOf("epoch_ms", "device_ts_us") + stream.channels)
        }
        writer.writeRow(listOf<Any>(s.receivedAtMs, s.deviceTsUs) + s.channels)
    }

    /** 组合包兼容 CSV（汇顶 PPG+ACC，F-FILE-7）：每个 ACC 样本配最近 ppg 值落一行。 */
    private fun writeCombo(acc: DecodedSample) {
        val writer = comboWriter ?: CsvWriter(
            fileSystem, layout.comboCsv,
            listOf("epoch_ms", "device_ts_us", "ppg_g", "ppg_ir", "acc_x", "acc_y", "acc_z"),
        ).also { comboWriter = it }
        val ch = acc.channels
        writer.writeRow(
            listOf<Any>(
                acc.receivedAtMs, acc.deviceTsUs,
                lastPpgG ?: 0, lastPpgIr ?: 0,
                ch.getOrElse(0) { 0 }, ch.getOrElse(1) { 0 }, ch.getOrElse(2) { 0 },
            ),
        )
    }

    /** 标签（Pin 瞬时 / Start/Stop 区间，§5.5）落 `csv/labels.csv` 并入实时流。 */
    fun recordLabel(epochMs: Long, event: String, text: String) {
        val writer = labelWriter ?: CsvWriter(
            fileSystem, layout.csv("labels"), listOf("epoch_ms", "event", "text"),
        ).also { labelWriter = it }
        writer.writeRow(listOf<Any>(epochMs, event, "\"" + text.replace("\"", "'") + "\""))
    }

    /** 本机 GNSS 一路（F-GPS-1）落 `gps.csv`。 */
    fun recordGps(epochMs: Long, lat: Double, lon: Double, altM: Double, speedMps: Double, accuracyM: Double) {
        if (!gnssEnabled) return
        val writer = gpsWriter ?: CsvWriter(
            fileSystem, layout.gpsCsv,
            listOf("epoch_ms", "lat", "lon", "alt_m", "speed_mps", "accuracy_m"),
        ).also { gpsWriter = it }
        writer.writeRow(listOf<Any>(epochMs, lat, lon, altM, speedMps, accuracyM))
    }

    /** 周期刷盘（编排层每 tick 调一次）：把批量缓冲落盘，崩溃损失窗口 = tick 间隔。 */
    fun flushWriters() {
        rawWriters.values.forEach { it.flush() }
        csvWriters.values.forEach { it.flush() }
        comboWriter?.flush()
        labelWriter?.flush()
        gpsWriter?.flush()
    }

    /** 关闭所有写入器并枚举产物文件（含大小/行数）—— 供 manifest.files 与结束摘要。 */
    fun finalizeFiles(): List<SessionFile> {
        rawWriters.values.forEach { it.close() }
        csvWriters.values.forEach { it.close() }
        comboWriter?.close()
        labelWriter?.close()
        gpsWriter?.close()

        val files = ArrayList<SessionFile>()
        rawWriters.forEach { (kind, w) ->
            files += sessionFile(layout.rawHex(kind), SessionFileCategory.RAW_HEX, w.lineCount)
        }
        csvWriters.forEach { (stream, w) ->
            files += sessionFile(layout.csv(stream.csvName), SessionFileCategory.DECODED_CSV, w.rowCount)
        }
        comboWriter?.let { files += sessionFile(layout.comboCsv, SessionFileCategory.COMBO_CSV, it.rowCount) }
        labelWriter?.let { files += sessionFile(layout.csv("labels"), SessionFileCategory.DECODED_CSV, it.rowCount) }
        gpsWriter?.let { files += sessionFile(layout.gpsCsv, SessionFileCategory.GNSS_CSV, it.rowCount) }
        return files
    }

    private fun sessionFile(path: Path, category: SessionFileCategory, lineCount: Long): SessionFile {
        val size = fileSystem.metadataOrNull(path)?.size ?: 0L
        val rel = path.toString().removePrefix(layout.sessionDir.toString()).trimStart('/', '\\')
        return SessionFile(rel.replace('\\', '/'), category, size, lineCount)
    }
}
