package io.bluetrace.shared.b2a

/** zip 烧录包内一个条目的元信息（名 + 解压后字节数）。[OtaPackageValidator.validate] 的输入。 */
data class OtaEntryInfo(val name: String, val size: Long)

/**
 * 烧录包"简单内容校验"结果。
 * - [valid]：能否用于 OTA（[errors] 空即 true）。
 * - [errors]：硬错（阻止 OTA）。[warnings]：软提示（可继续，如缺字库）。
 */
data class OtaPackageValidation(
    val valid: Boolean,
    val fileCount: Int,
    val totalSize: Long,
    val missingRequired: List<String>,
    val tooLongNames: List<String>,
    val emptyFiles: List<String>,
    val hasFonts: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)

/**
 * OTA 烧录包内容校验（纯逻辑，commonMain 可测）。App 侧解 zip 拿到条目清单后调此。
 *
 * 固件真相（`OTA_Main.c` 编译版 :197/:601 坐实，2026-07-08 workflow）：`OTA_DoRecvBefore` 是空壳
 * （:222 无条件 return，不拷任何资源；lData 全量/差分设备侧无效——差分拷 Res 的 :788 是死码）；真正的门是
 * `OTA_DoRecvAfter`：Step1 **只校验目标区 Res 三件套存在**（不补拷、不验版本），Step2 字库缺则从旧区补拷。
 * 推论：**只推 `fw.dat`（O-1 提速）能成 ⟺ 设备目标区已有合法 Res**（须先整包刷过一次）；缺则升级
 * 静默不生效（非砖，整包重推即恢复）。文件名 >12 字符会被固件 `szFile[13]` 截断写错名（硬错）。
 * 见 `Docs/OTA/S7采集OTA_指令交互与流程.md` §2.2/§4 与 `implementation-notes.md` O-1 结论。
 */
object OtaPackageValidator {
    /** 资源三件套（DoRecvAfter Step1 校验其在目标区存在；缺则需设备已有）。 */
    val RES_FILES = listOf("ResData.dat", "ResFat.dat", "ResCheck.dat")

    /** 主 MCU 镜像。 */
    const val FW_FILE = "fw.dat"

    /** 文件名字节上限（固件 szFile[13]，>12 截断）。 */
    const val MAX_NAME_LEN = 12

    /** 是否字库文件（fCheck / fCN* / fNum*）。 */
    fun isFont(name: String): Boolean =
        name == "fCheck.dat" || name.startsWith("fCN") || name.startsWith("fNum")

    fun validate(entries: List<OtaEntryInfo>): OtaPackageValidation {
        val names = entries.map { it.name }
        val hasFw = FW_FILE in names
        val missingRes = RES_FILES.filter { it !in names }
        val hasAllRes = missingRes.isEmpty()
        val hasAnyRes = missingRes.size < RES_FILES.size
        val tooLong = names.filter { it.encodeToByteArray().size > MAX_NAME_LEN }
        val empty = entries.filter { it.size <= 0 }.map { it.name }
        val hasFonts = entries.any { isFont(it.name) }

        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        // 硬错：结构/命名问题，或整包无可刷内容
        if (entries.isEmpty()) errors.add("空包（zip 内无文件）")
        if (tooLong.isNotEmpty()) errors.add("文件名超 $MAX_NAME_LEN 字符：${tooLong.joinToString("、")}")
        if (empty.isNotEmpty()) errors.add("含 0 字节文件：${empty.joinToString("、")}")
        if (entries.isNotEmpty() && !hasFw && !hasAnyRes) errors.add("无 fw.dat 也无 Res 资源，不是有效 OTA 包")
        // 软警告：可继续，设备侧靠已有资源兜底
        if (hasFw && !hasAllRes) {
            if (!hasAnyRes) warnings.add("仅 fw.dat（O-1）：当前固件**实测失败**——目标区 OTA 后无 Res，设备末 STOP 回 code=13(FSUM_FAIL)拒绝。需推 ota_part(fw+Res 三件套) 或改固件让目标区补拷 Res")
            else warnings.add("缺部分 Res：${missingRes.joinToString("、")}——当前固件目标区不保留旧 Res，很可能被拒")
        }
        if (!hasFw && hasAnyRes) warnings.add("无 fw.dat：仅刷资源、不换主固件")
        if (entries.isNotEmpty() && hasFw && hasAllRes && !hasFonts) warnings.add("无字库（ota_part）——将沿用旧表字库，视觉可能有差、功能无碍")

        return OtaPackageValidation(
            valid = errors.isEmpty(),
            fileCount = entries.size,
            totalSize = entries.sumOf { it.size },
            missingRequired = missingRes + (if (hasFw) emptyList() else listOf(FW_FILE)),
            tooLongNames = tooLong,
            emptyFiles = empty,
            hasFonts = hasFonts,
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * 推送顺序（对齐真机官方 App golden 顺序）：字库 → fw.dat → 资源三件套。
     * 设备不校验顺序（末 STOP 即完成），此序仅为与已验证的 golden 一致。未知文件按原序追加末尾。
     */
    private val ORDER: List<String> = listOf(
        "fCheck.dat", "fCN26.dat", "fCN34.dat", "fCN40.dat",
        "fNum48.dat", "fNum64.dat", "fNum72.dat", "fNum80.dat", "fNum96.dat", "fNum120.dat",
        "fw.dat", "ResCheck.dat", "ResData.dat", "ResFat.dat",
    )

    /** 按 golden 顺序排序文件名（未列入的排最后、保持相对原序）。 */
    fun <T> sortByPushOrder(items: List<T>, nameOf: (T) -> String): List<T> =
        items.withIndex().sortedWith(
            compareBy(
                { ORDER.indexOf(nameOf(it.value)).let { i -> if (i < 0) ORDER.size else i } },
                { it.index },
            ),
        ).map { it.value }
}
