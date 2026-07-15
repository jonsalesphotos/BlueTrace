package io.bluetrace.data.android

import android.content.Context
import io.bluetrace.shared.config.EngineeringConfig
import io.bluetrace.shared.config.parseEngineeringConfig
import io.bluetrace.shared.config.toJsonText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工程配置存储。真源 = app 私有 `files/config/bluetrace_config.json`（adb 可改：
 * `adb push config.json /sdcard/Android/data/io.bluetrace/files/config/bluetrace_config.json`，重启 App 生效）；
 * 公共 `Download/BlueTrace/config/` 放**只读镜像**供文件管理器翻看——不从公共侧回读
 * （MTP/他 App 改写会换 MediaStore owner，本 app 将失读，读回不可靠）。
 *
 * [load] 同步、构造后调一次：文件缺失 → 写默认完整清单（落地即可改的字段列表）；
 * 文件存在但解析失败 → 内存用默认值、**保留原文件**供人工修（不覆盖手改内容）。
 */
class ConfigStore(private val context: Context) {

    private val file: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "config/$FILE_NAME")

    @Volatile
    var current: EngineeringConfig = EngineeringConfig()
        private set

    fun load(): EngineeringConfig {
        val text = try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
        val parsed = text?.let { parseEngineeringConfig(it) }
        current = parsed ?: EngineeringConfig()
        if (text == null) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(current.toJsonText())
            } catch (e: Exception) {
                // 写默认清单失败不阻塞启动，下次再试
            }
        }
        return current
    }

    /** 镜像到公共 `Download/BlueTrace/config/`（启动后台调；尽力而为）。 */
    suspend fun mirrorToPublic() = withContext(Dispatchers.IO) {
        val bytes = try {
            if (file.exists()) file.readBytes() else current.toJsonText().toByteArray()
        } catch (e: Exception) {
            current.toJsonText().toByteArray()
        }
        PublicDownloadStore(context).upsertBytes(PublicTree.CONFIG, FILE_NAME, bytes)
    }

    companion object {
        const val FILE_NAME = "bluetrace_config.json"
    }
}
