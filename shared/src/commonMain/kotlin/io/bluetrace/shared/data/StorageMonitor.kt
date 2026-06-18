package io.bluetrace.shared.data

/** 可用存储空间查询（app 用 StatFs/getUsableSpace 实现，§5.2 / §5.8）。 */
fun interface StorageMonitor {
    fun usableBytes(): Long
}

/** 存储策略阈值（§5.2 共识）。 */
object StoragePolicy {
    /** 开始采集前最低可用空间，不足则拦截不允许开始。 */
    const val MIN_FREE_TO_START: Long = 50L * 1024 * 1024 // 50 MB

    /** 启动/开始前低空间提示阈值（< 1GB 给一次提示，不阻断）。 */
    const val LOW_SPACE_HINT: Long = 1024L * 1024 * 1024 // 1 GB

    /** 采集中可用空间跌破此值 → 自动结束并安全落盘（§5.4）。 */
    const val MIN_FREE_DURING: Long = 20L * 1024 * 1024 // 20 MB

    /** 导出前预检：需 ≥ 待导出大小 × 该系数（zip 略小，留余量）。 */
    const val EXPORT_HEADROOM_FACTOR: Double = 1.2
}
