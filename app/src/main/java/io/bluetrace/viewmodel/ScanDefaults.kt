package io.bluetrace.viewmodel

/**
 * 扫描过滤全局默认（两个连接页统一）。
 *
 * RSSI 阈值 = 只显示强于该值的设备。默认 -48 dBm 偏强, 适合"多台手表"环境下只挑眼前那台;
 * 若参考心率带等较远设备被过滤掉, 在滑条上调低(往 -99 方向)即可。
 */
object ScanDefaults {
    const val RSSI_THRESHOLD = -48
}
