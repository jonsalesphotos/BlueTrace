package com.example.bluetrace.shared.protocol

import com.example.bluetrace.shared.domain.DecodedStream

/**
 * 一个已解码样本（协议层输出 → 喂给解码 CSV 写入器 + 设备卡 HR）。
 * 时间双轨：[deviceTsUs] 设备单调时钟（us，对齐分析用）+ [receivedAtMs] 本机 unix 接收时刻（落盘 epoch_ms，§6.3）。
 */
data class DecodedSample(
    val stream: DecodedStream,
    val deviceTsUs: Long,
    val receivedAtMs: Long,
    val channels: List<Int>,
)
