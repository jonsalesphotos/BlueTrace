package io.bluetrace.data.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * OTA 执行日志落盘(app 级单例): 每次运行(单设备单次/循环/多设备批量)一个文件,
 * 公共 `Download/BlueTrace/log/ota/ota_<kind>_<时间戳>.log`, **运行中逐行追加 + flush**——
 * 循环 OTA 可跑数小时/内存终端只留 300 行, 落盘才是全量历史; 进程中途被杀也只丢最后一行.
 *
 * 线程模型: [append] 只 trySend 进无界 Channel(主线程零 IO), 单消费协程串行写流(保序).
 * MediaStore 建行/开流失败 → 静默降级为丢弃(不影响 OTA 本身), 文件名仍可供 UI 展示意图.
 */
class OtaRunLogStore(private val context: Context) {

    // 写线程与 app 同寿(不随 VM 销毁): 关闭动作靠 OtaRunLog.close() 关 Channel 收敛
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** 开启一次运行日志. kind ∈ {single, loop, multi}(文件名 token, 恒英文).  */
    fun begin(kind: String, ts: String): OtaRunLog = OtaRunLog(context, writer, "ota_${kind}_$ts.log")
}

/** 一次 OTA 运行的日志文件句柄. [append] 可多线程调用; [close]/[discard] 幂等.  */
class OtaRunLog internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    val fileName: String,
) {
    val displayPath: String = "${PublicTree.display(PublicTree.LOG_OTA)}/$fileName"

    private val lines = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var discarded = false

    init {
        scope.launch {
            val store = PublicDownloadStore(context)
            val uri = store.insert(PublicTree.LOG_OTA, fileName)
            val os = uri?.let {
                try {
                    context.contentResolver.openOutputStream(it)
                } catch (e: Exception) {
                    null
                }
            }
            try {
                for (line in lines) { // os=null 时仍消费到 close(降级为丢弃)
                    if (os != null) {
                        try {
                            os.write(line.toByteArray())
                            os.write('\n'.code)
                            os.flush()
                        } catch (e: Exception) {
                            // 单行写失败不中断后续行(磁盘满等瞬态)
                        }
                    }
                }
            } finally {
                try {
                    os?.close()
                } catch (e: Exception) {
                    // 关流失败无可挽救
                }
                // 丢弃语义: 运行根本没发生(如启动被编排核拒绝)——建行/追加是异步的, close 只关流
                // 不删文件, 会留下一个"假运行"日志; 由写协程自己收尾删行, 时序天然在关流之后.
                if (discarded && uri != null) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        // 删不掉留个空文件, 无害
                    }
                }
            }
        }
    }

    fun append(line: String) {
        lines.trySend(line)
    }

    /** 关闭(把已入队的行写完后关流). 幂等; close 后 append 静默丢弃.  */
    fun close() {
        lines.close()
    }

    /** 丢弃: 关流并删除已建的 MediaStore 文件(运行未真正发生时用, 不留假运行日志). 幂等.  */
    fun discard() {
        discarded = true
        lines.close()
    }
}
