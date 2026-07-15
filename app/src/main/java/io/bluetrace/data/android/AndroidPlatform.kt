package io.bluetrace.data.android

import android.content.Context
import io.bluetrace.shared.util.EpochClock
import io.bluetrace.shared.util.TimeZoneProvider
import okio.Path
import okio.Path.Companion.toPath
import java.time.ZoneId
import java.time.ZonedDateTime

/** unix 墙钟（§6.3）。 */
class AndroidEpochClock : EpochClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** 系统时区 / 偏移（manifest 记时区 + 起点，§6.2）。minSdk 29 起 java.time 原生可用。 */
class AndroidTimeZoneProvider : TimeZoneProvider {
    override fun zoneId(): String = ZoneId.systemDefault().id
    override fun offsetSeconds(): Int = ZonedDateTime.now().offset.totalSeconds
}

/** 会话主存储根（app 私有外部目录免权限，§6.4）：`getExternalFilesDir(null)/sessions`。 */
fun sessionsRoot(context: Context): Path {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return base.resolve("sessions").absolutePath.toPath()
}

/**
 * 应用滚动日志目录（v8 目录树统一，与公共侧 `log/app` 同名）：`getExternalFilesDir(null)/log/app`。
 * 首次调用把 v7 遗留 `files/logs/` 的 `app-*.log` 迁入（同卷 rename，幂等；重名保新删旧）。
 */
fun appLogsDir(context: Context): Path {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val dir = base.resolve("log/app")
    val legacy = base.resolve("logs")
    if (legacy.isDirectory) {
        dir.mkdirs()
        legacy.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            runCatching {
                val dst = dir.resolve(f.name)
                if (dst.exists()) f.delete() else f.renameTo(dst)
            }
        }
        runCatching { legacy.delete() } // 搬空才删得掉；有残留就留着下次再试
    }
    return dir.absolutePath.toPath()
}
