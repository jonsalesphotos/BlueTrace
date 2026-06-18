package io.bluetrace.shared.session

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.LinkState
import io.bluetrace.shared.domain.StopReason

/** 采集运行三态（精简，§5.4 / D-V4-5）：就绪 / 采集中 / 已停止。断联是设备卡内联态，会话仍 COLLECTING。 */
enum class RunStatus { READY, COLLECTING, STOPPED }

/** 运行设备卡状态（含实时 HR + 内联"重连中"，§5.4）。 */
data class RunDeviceState(
    val deviceId: String,
    val name: String,
    val kind: DeviceKind,
    val link: LinkState,
    val hr: Int? = null,
    val activeSensors: List<String> = emptyList(),
)

/** 运行页 UI 状态快照（节流推送，非逐包）。 */
data class RunState(
    val status: RunStatus = RunStatus.READY,
    val startEpochMs: Long = 0,
    val elapsedMs: Long = 0,
    val datasCount: Long = 0,
    val devices: List<RunDeviceState> = emptyList(),
    /** Start/Stop 区间标签是否处于「已开区间」（按钮显示 Stop Label，§5.5）。 */
    val labelIntervalOpen: Boolean = false,
    /** 暂停 = 仅停数据框滚动显示（D-V4-11），不停接收/落盘。 */
    val displayPaused: Boolean = false,
    val stopReason: StopReason? = null,
    val reconnectCount: Int = 0,
)

/** 实时流一行（§5.6）。HEX 行等宽，标签行绿色。 */
data class RunLogLine(
    val kind: Kind,
    val timeLabel: String, // 相对起点 "[MM:SS.cc]"
    val text: String,
) {
    enum class Kind { HEX, LABEL }
}

/** 相对起点的展示时间戳 `MM:SS.cc`（实时流，§5.6；落盘仍是 unix epoch）。 */
fun formatRelativeCenti(elapsedMs: Long): String {
    val e = elapsedMs.coerceAtLeast(0)
    val totalCenti = e / 10
    val minutes = totalCenti / 6000
    val seconds = (totalCenti / 100) % 60
    val centi = totalCenti % 100
    fun p2(v: Long) = if (v < 10) "0$v" else v.toString()
    return "${p2(minutes)}:${p2(seconds)}.${p2(centi)}"
}
