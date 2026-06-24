package io.bluetrace.shared.data

import io.bluetrace.shared.domain.DeviceKind
import io.bluetrace.shared.domain.SceneSelection
import io.bluetrace.shared.domain.SessionConfig
import io.bluetrace.shared.util.epochMsToLocalParts
import okio.Path

/**
 * D-6 会话文件夹布局（§6.1）。一会话一文件夹：
 * ```
 * <folder>/
 *   raw/dut.hexlog  raw/reference.hexlog   ← 原始 HEX 行（source of truth）
 *   csv/ppg_g.csv csv/ppg_ir.csv csv/acc.csv csv/hr.csv  ← 每模块解码 CSV
 *   csv/ppg_acc.csv                         ← 组合包兼容 CSV
 *   gps.csv                                 ← 本机 GNSS（开启时）
 *   session_manifest.json
 * ```
 */
class SessionLayout(val sessionDir: Path) {
    val rawDir: Path get() = sessionDir / "raw"
    val csvDir: Path get() = sessionDir / "csv"

    fun rawHex(kind: DeviceKind): Path =
        rawDir / if (kind == DeviceKind.DUT) "dut.hexlog" else "reference.hexlog"

    fun csv(csvName: String): Path = csvDir / "$csvName.csv"
    val comboCsv: Path get() = csvDir / "ppg_acc.csv"
    val gpsCsv: Path get() = sessionDir / "gps.csv"
    val manifest: Path get() = sessionDir / "session_manifest.json"

    companion object {
        const val MANIFEST_NAME = "session_manifest.json"
    }
}

/**
 * 会话文件夹名 `<mainScene>_<subScene>_<alias>_<yyyyMMdd>_<HHmmss>_<deviceShort>`
 * （v6 · 5 段语义：主场景_子场景_用户_日期_MAC后四位；token 恒英文，locale 无关）。
 */
fun sessionFolderName(config: SessionConfig): String {
    val primary = config.devices.firstOrNull { it.kind == DeviceKind.DUT } ?: config.devices.firstOrNull()
    return sessionFolderName(config.scene, config.subject.alias, config.startEpochMs, config.utcOffsetSeconds, primary?.address)
}

/** 由 场景/别名/起点/设备地址 直接拼会话夹名（编辑后重命名 §0.3/D/E 复用）。 */
fun sessionFolderName(
    scene: SceneSelection,
    alias: String,
    startEpochMs: Long,
    utcOffsetSeconds: Int,
    deviceAddress: String?,
): String {
    val parts = epochMsToLocalParts(startEpochMs, utcOffsetSeconds)
    val a = sanitizeToken(alias).ifBlank { "user" }
    val short = deviceAddress?.let { addr ->
        val hex = addr.filter { it.isLetterOrDigit() }
        if (hex.length >= 4) hex.takeLast(4).lowercase() else hex.lowercase()
    }.orEmpty().ifBlank { "0000" }
    return "${scene.mainToken}_${scene.subToken}_${a}_${parts.compact()}_$short"
}

private fun sanitizeToken(s: String): String =
    s.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
