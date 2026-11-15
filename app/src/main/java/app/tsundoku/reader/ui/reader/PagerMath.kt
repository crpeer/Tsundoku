package app.tsundoku.reader.ui.reader

/**
 * 翻页用的纯数学。
 *
 * 两个关键事实决定了这里的接口：
 * 1. CSS 像素不等于物理像素（实测 WebView 宽 720 物理像素时 `innerWidth` 是 1216 CSS 像素），
 *    所以**不能**用 CSS 列宽/`contentHeight` 当翻页步长，否则页码与正文位置会错位。
 * 2. `WebView.contentHeight` 在 `onPageFinished` 时可能是 0，
 *    所以最大滚动量改用「滚到底读 scrollY」实测得到（见 `ReaderWebView.measureMaxScroll`）。
 *
 * 于是所有函数都只吃物理像素：`scroll`、`maxScroll`、`pageHeight`。
 * 最后一页通常不足一屏，它的合法偏移就是 `maxScroll`。
 */
object PagerMath {

    /**
     * 总页数；内容不足一屏时算 1 页。
     *
     * 必须向上取整：最后一屏常常只露出几十像素，若向下取整就会永远翻不到，
     * 结尾那段文字也就再也看不到了。
     */
    fun pageCount(maxScroll: Int, pageHeight: Int): Int {
        if (pageHeight <= 0 || maxScroll <= 0) return 1
        return (maxScroll + pageHeight - 1) / pageHeight + 1
    }

    /** 当前是第几页（0 基）。 */
    fun pageOf(scroll: Int, maxScroll: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        val last = pageCount(maxScroll, pageHeight) - 1
        // 滚到底时一定算最后一页（最后一页偏移不一定是页高的整数倍）
        if (scroll >= maxScroll) return last
        return (scroll.coerceAtLeast(0) / pageHeight).coerceIn(0, last)
    }

    /** 第 page 页对应的滚动偏移（最后一页对齐到 maxScroll）。 */
    fun pageOffset(page: Int, maxScroll: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        val last = pageCount(maxScroll, pageHeight) - 1
        return (page.coerceIn(0, last) * pageHeight).coerceAtMost(maxScroll.coerceAtLeast(0))
    }

    /** 是否已经读到本章末尾（留一点容差，避免亚像素误差）。 */
    fun isAtEnd(scroll: Int, maxScroll: Int, tolerance: Int = 2): Boolean =
        scroll >= maxScroll - tolerance

    /** 当前相对位置 0..1，用于换字号/行距后按比例还原。 */
    fun ratio(scroll: Int, maxScroll: Int): Float {
        if (maxScroll <= 0) return 0f
        return (scroll.toFloat() / maxScroll).coerceIn(0f, 1f)
    }

    /** 按比例换算回滚动偏移。 */
    fun offsetForRatio(ratio: Float, maxScroll: Int): Int =
        (ratio.coerceIn(0f, 1f) * maxScroll.coerceAtLeast(0)).toInt()

    /**
     * 翻页目标偏移：对齐到相邻页边界。
     * 已经在首/末页时返回当前页的合法偏移（等于入参），调用方据此判断「翻不动了」。
     */
    fun targetPageScroll(current: Int, forward: Boolean, maxScroll: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        // 先对齐到页边界，避免半页状态下连点导致累积漂移
        val page = pageOf(current, maxScroll, pageHeight)
        val targetPage = if (forward) page + 1 else page - 1
        return pageOffset(targetPage, maxScroll, pageHeight)
    }
}
