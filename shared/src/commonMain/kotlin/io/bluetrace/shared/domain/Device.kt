package io.bluetrace.shared.domain

/**
 * 设备种类（D-V4-3 扁平列表 + 限额）：
 * - [DUT] 自研多传感器设备，走私有协议（v1 Mock），上限 3 台。
 * - [REFERENCE] 标准 SIG 心率带（HRS 0x180D），仅参考心率源，上限 1 台。
 *
 * 注意：**不是** `Map<Role,Device>` 的角色槽模型；是扁平列表按 kind 限额。
 */
enum class DeviceKind {
    DUT,
    REFERENCE,
}

object DeviceLimits {
    const val MAX_DUT = 3
    const val MAX_REFERENCE = 1

    /** 第 3 台起链路标黄（不稳定提示）。 */
    const val DUT_UNSTABLE_THRESHOLD = 3
}

/** 标准心率带 profile id，写入 manifest device.profileId（§6.2）。 */
const val PROFILE_HRS = "HeartRate.SIG.0x180D"

/**
 * **B2A 协议** profile id（不是某一款设备的 id）——写入 manifest device.profileId，规格见 `Docs/S7B2A/`。
 *
 * 值形制对齐 [PROFILE_HRS]（协议.UUID 锚），**不含厂商与设备名**（2026-07-16 硬约束：
 * 协议标识只表示协议能力，SKG/S7 不进入架构标识；旧值 `"SKG.S7.B2A"` 已废，历史会话
 * manifest 中的旧值只读展示不回比，demo 阶段直接替换不迁移）。
 *
 * **它不是识别判据**：识别锚点是 GATT UUID（广播含 `FFE0` → [io.bluetrace.shared.b2a.B2aDetect]），
 * 产品名/广播名只用于 UI 展示，会随产品改名。本常量是识别**结果**的稳定标识，跨设备型号复用。
 */
const val PROFILE_B2A = "B2A.0xFFE0"

/**
 * **测试夹具**：手边那台 B2A 真机（SKG WATCH S7-FCC4）的 MAC，Mock 设备与真机联调的目标对象。
 * **不是白名单、不是判据**——协议识别一律走广播 `FFE0`（见 [io.bluetrace.shared.b2a.B2aDetect]），
 * 支持任意 B2A 设备；本常量仅测试期用来定位/校验这一台具体设备。
 * 该表广播名后缀 = MAC[1]MAC[0] 的 4 位 hex（spec §1）→ `SKG WATCH S7-FCC4`。
 */
const val TEST_DUT_MAC = "71:61:48:19:FC:C4"

/**
 * 扫描发现的设备（一次广播快照）。
 *
 * `id` = 稳定身份键（**真实端契约 = 规范化 MAC，大写 12 位 hex 无分隔**，见 [normalizeMac]；
 * Mock = 合成固定串，测试夹具豁免）。`address` = 平台原始冒号串，只用于**展示**与平台 API
 * （`getRemoteDevice`/`getPeripheralById` 要求冒号格式）。名称只用于展示，不得作身份/识别判据。
 */
data class ScannedDevice(
    val id: String,
    val name: String,
    val address: String, // 展示用 MAC（iOS 后期换 identifier）
    val rssi: Int,
    val kind: DeviceKind,
    val profileId: String? = null,
    /**
     * 广播携带的 Service UUID 表（16-bit 用 4 位 hex 如 "FFE0"，128-bit 全串）——
     * 协议识别的首选依据（nRF Connect 式广播特征匹配）；名称/MAC 只作用户过滤辅助。
     */
    val advertisedServices: List<String> = emptyList(),
) {
    /** 文件名用的设备短标识（MAC 末 4 位 hex，§6.1 `<deviceShort>`）。 */
    fun deviceShort(): String {
        val hex = address.filter { it.isLetterOrDigit() }
        return if (hex.length >= 4) hex.takeLast(4).lowercase() else hex.lowercase()
    }
}

/** BLE 链路状态（扫描/连接屏 + 运行设备卡）。 */
enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** 断联后由协议栈自动重连中（设备卡内联"重连中"琥珀点，§5.4）。 */
    RECONNECTING,
}
