package io.bluetrace.domain

/**
 * 最近一次拉取的设备日志缓存（app 级单例）：控制台拉完写入，日志查看页读出。
 * 跨导航目的地共享（各页 ViewModel 实例不同，用单例转交内容）。
 */
class S7LogHolder {
    var text: String? = null
        private set
    var name: String? = null
        private set

    fun set(text: String, name: String) {
        this.text = text
        this.name = name
    }
}
