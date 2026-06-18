package com.example.bluetrace.shared.data

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * 原始 HEX 行写入器（§6.1，F-FILE-6）：`<epochMs>: HEX` 每行一条，append-only、行级 flush（崩溃安全）、可重放。
 */
class RawHexWriter(fileSystem: FileSystem, path: Path) {
    private val sink: BufferedSink = fileSystem.appendingSink(path).buffer()
    var lineCount: Long = 0L
        private set

    fun writeLine(epochMs: Long, hex: String) {
        sink.writeUtf8(epochMs.toString())
        sink.writeUtf8(": ")
        sink.writeUtf8(hex)
        sink.writeUtf8("\n")
        sink.flush()
        lineCount++
    }

    fun close() {
        runCatching { sink.flush() }
        runCatching { sink.close() }
    }
}

/**
 * 解码 CSV 写入器（每模块一份，§6.1）。首行 header，之后每行 `epoch_ms,device_ts_us,ch...`。
 * 每行带 `epoch_ms`（unix），不存设备本地字符串时间（§6.3）。
 */
class CsvWriter(fileSystem: FileSystem, path: Path, header: List<String>) {
    private val sink: BufferedSink = fileSystem.sink(path).buffer()
    var rowCount: Long = 0L
        private set

    init {
        sink.writeUtf8(header.joinToString(","))
        sink.writeUtf8("\n")
        sink.flush()
    }

    fun writeRow(values: List<Any>) {
        sink.writeUtf8(values.joinToString(","))
        sink.writeUtf8("\n")
        sink.flush()
        rowCount++
    }

    fun close() {
        runCatching { sink.flush() }
        runCatching { sink.close() }
    }
}
