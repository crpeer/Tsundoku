package app.tsundoku.reader.ui.reader

/**
 * 「等文档就绪后只执行一次」的待办队列。
 *
 * 全书搜索跳转时会先切章、再让 WebView 定位关键字，而切章后 WebView 往往还没加载完，
 * 内嵌脚本（`window.__tsu`）也还没建立。原实现是「试着调用一次然后立刻消费请求」，
 * 于是请求被静默丢弃。这里把请求存下来，等脚本就绪（[take] 传入 ready = true）时才取走，
 * 取走即清空，保证同一个请求不会重复执行。
 */
internal class PendingFindQueue {

    private var pending: String? = null

    /** 已真正交给脚本的次数，便于测试与排查。 */
    var deliveredCount: Int = 0
        private set

    val hasPending: Boolean get() = pending != null

    /** 新请求覆盖旧请求（用户连续搜索时以最后一次为准）。 */
    fun enqueue(text: String) {
        if (text.isBlank()) return
        pending = text
    }

    /**
     * 就绪时取走待执行的关键字；未就绪返回 null 且**保留**队列内容。
     * 取走后立刻清空，因此重复调用不会重复执行。
     */
    fun take(ready: Boolean): String? {
        if (!ready) return null
        val text = pending ?: return null
        pending = null
        deliveredCount++
        return text
    }

    fun clear() {
        pending = null
    }
}
