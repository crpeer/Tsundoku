package app.tsundoku.reader.ui.reader

import android.webkit.JavascriptInterface

/**
 * 内嵌脚本 → Kotlin 的桥。
 *
 * 只暴露数值与字符串参数的方法：脚本拿不到文件、网络或任何原生能力。
 * `@JavascriptInterface` 的回调发生在 WebView 的 JavaBridge 线程，调用方负责切回主线程。
 */
internal class PaginationBridge(
    private val onReady: () -> Unit,
    private val onState: (page: Int, pages: Int, ratio: Float, atEnd: Boolean) -> Unit,
    private val onTap: (delta: Int) -> Unit,
    private val onPoint: (x: Float, y: Float, path: String, offset: Int) -> Unit = { _, _, _, _ -> },
    private val onSelection: (String) -> Unit = {},
    private val onSelectionCleared: () -> Unit = {},
    private val onDebug: (String) -> Unit = {},
) {

    @JavascriptInterface
    fun ready() = onReady()

    @JavascriptInterface
    fun state(page: Int, pages: Int, ratio: Double, atEnd: Int) =
        onState(page, pages, ratio.toFloat(), atEnd != 0)

    @JavascriptInterface
    fun tap(delta: Int) = onTap(delta)

    /** 点击正文：坐标是 CSS 像素，[path] / [offset] 是落点所在的文本节点与字符偏移。 */
    @JavascriptInterface
    fun point(x: Double, y: Double, path: String, offset: Int) =
        onPoint(x.toFloat(), y.toFloat(), path, offset)

    @JavascriptInterface
    fun selection(json: String) = onSelection(json)

    @JavascriptInterface
    fun selectionCleared() = onSelectionCleared()

    @JavascriptInterface
    fun debug(message: String) = onDebug(message)
}
