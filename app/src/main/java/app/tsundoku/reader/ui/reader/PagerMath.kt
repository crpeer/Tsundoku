package app.tsundoku.reader.ui.reader

/**
 * 翻页用的纯数学。
 *
 * 翻页模式不再用 CSS 多栏 + 横向滚动（列宽依赖 CSS px 与 dp 的换算，实测两者不成 1:1，
 * 导致「页码在涨但内容不动 / 末尾滚出空白页」），改成**按视口高度整页纵向滚动**：
 * 直接使用 WebView 自己的 `scrollY` 与 `contentHeight`（都是物理像素），换算完全确定，
 * 而且 WebView 会自动把 scrollY 钳制在内容范围内，不会翻出空白页。
 *
 * 最后一页往往不足一屏，它的合法滚动位置是 `maxScroll`（不是 `page * pageHeight`），
 * 否则最后一页会被判成「还有下一页」，从而出现空白页。
 */
object PagerMath {

    /** 总页数；内容为空时也算 1 页。 */
    fun pageCount(contentHeight: Int, pageHeight: Int): Int {
        if (pageHeight <= 0 || contentHeight <= 0) return 1
        return ((contentHeight + pageHeight - 1) / pageHeight).coerceAtLeast(1)
    }

    /** 可滚动的最大偏移。 */
    fun maxScroll(contentHeight: Int, pageHeight: Int): Int =
        (contentHeight - pageHeight).coerceAtLeast(0)

    /** 当前是第几页（0 基）：滚到最底部时一定算最后一页。 */
    fun pageOf(scroll: Int, contentHeight: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        val last = pageCount(contentHeight, pageHeight) - 1
        if (scroll >= maxScroll(contentHeight, pageHeight)) return last
        return (scroll / pageHeight).coerceIn(0, last)
    }

    /** 第 page 页对应的滚动偏移（最后一页对齐到 maxScroll）。 */
    fun pageOffset(page: Int, contentHeight: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        val last = pageCount(contentHeight, pageHeight) - 1
        val clamped = page.coerceIn(0, last)
        return (clamped * pageHeight).coerceAtMost(maxScroll(contentHeight, pageHeight))
    }

    /** 是否已经读到本章末尾（留一点容差，避免亚像素误差）。 */
    fun isAtEnd(scroll: Int, contentHeight: Int, pageHeight: Int, tolerance: Int = 2): Boolean {
        if (contentHeight <= 0) return false
        return scroll + pageHeight >= contentHeight - tolerance
    }

    /** 当前相对位置 0..1，用于换字号/行距后按比例还原。 */
    fun ratio(scroll: Int, contentHeight: Int, pageHeight: Int): Float {
        val max = maxScroll(contentHeight, pageHeight)
        if (max <= 0) return 0f
        return (scroll.toFloat() / max).coerceIn(0f, 1f)
    }

    /** 按比例换算回滚动偏移。 */
    fun offsetForRatio(ratio: Float, contentHeight: Int, pageHeight: Int): Int =
        (ratio.coerceIn(0f, 1f) * maxScroll(contentHeight, pageHeight)).toInt()

    /**
     * 翻页目标偏移：对齐到相邻页边界。
     * 已经在首/末页时返回当前页的合法偏移（等于入参），调用方据此判断「翻不动了」。
     */
    fun targetPageScroll(current: Int, forward: Boolean, contentHeight: Int, pageHeight: Int): Int {
        if (pageHeight <= 0) return 0
        // 先对齐到页边界，避免半页状态下连点导致累积漂移
        val page = pageOf(current, contentHeight, pageHeight)
        val targetPage = if (forward) page + 1 else page - 1
        return pageOffset(targetPage, contentHeight, pageHeight)
    }
}
