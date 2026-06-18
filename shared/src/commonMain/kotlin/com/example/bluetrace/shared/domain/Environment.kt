package com.example.bluetrace.shared.domain

/** 环境/权限条目（§5.2）。蓝牙开关是系统开关（≠权限），单列。后台省电是系统设置（≠权限）。 */
enum class RequirementId {
    BLUETOOTH_ON,        // 蓝牙开关（系统开关）
    BLE_SCAN_CONNECT,    // 附近设备：扫描/连接（硬性权限）
    LOCATION,            // 定位 / GNSS（建议，while-in-use）
    NOTIFICATIONS,       // 通知（建议）
    BATTERY_UNRESTRICTED // 后台不省电（系统设置，建议）
}

/** 硬性（缺则阻断采集）vs 建议（缺不阻断，§5.2）。 */
enum class RequirementSeverity { HARD, SUGGESTED }

enum class RequirementStatus {
    GRANTED,     // 已满足
    MISSING,     // 缺失（可弹系统请求）
    BLOCKED,     // 永久拒绝（系统不再弹 → 引导系统设置）
    OFF,         // 系统开关关闭（蓝牙/定位总开关）
}

data class Requirement(
    val id: RequirementId,
    val severity: RequirementSeverity,
    val status: RequirementStatus,
)

data class EnvironmentState(
    val requirements: List<Requirement> = emptyList(),
) {
    fun status(id: RequirementId): RequirementStatus? =
        requirements.firstOrNull { it.id == id }?.status

    /** 硬性条件是否全满足（决定能否开始采集 / 扫描，§5.2 / D-V4-6）。 */
    val hardSatisfied: Boolean
        get() = requirements.filter { it.severity == RequirementSeverity.HARD }
            .all { it.status == RequirementStatus.GRANTED }

    val bluetoothOn: Boolean
        get() = status(RequirementId.BLUETOOTH_ON) == RequirementStatus.GRANTED

    /** 缺失的条目（首启门控 / 后续缺权限 Sheet 用）。 */
    fun missing(): List<Requirement> =
        requirements.filter { it.status != RequirementStatus.GRANTED }
}
