package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.PagerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页位置计算：页码必须严格等于实际滚动位置，且不能越过内容末尾。
 * 这些数字就是原来「页码涨了但正文没变 / 滚出空白页」的核心。
 */
class PagerMathTest {

    private val page = 1000

    @Test
    fun pageIndexFollowsScrollExactly() {
        val content = 3000
        assertEquals(0, PagerMath.pageOf(0, content, page))
        assertEquals(0, PagerMath.pageOf(999, content, page))
        assertEquals(1, PagerMath.pageOf(1000, content, page))
        assertEquals(1, PagerMath.pageOf(1999, content, page))
        // 滚到底 = 最后一页
        assertEquals(2, PagerMath.pageOf(2000, content, page))
        // 异常大的滚动值也不能越界
        assertEquals(2, PagerMath.pageOf(9999, content, page))
    }

    @Test
    fun pageCountRoundsUpAndNeverZero() {
        assertEquals(1, PagerMath.pageCount(0, page))
        assertEquals(1, PagerMath.pageCount(1, page))
        assertEquals(1, PagerMath.pageCount(1000, page))
        assertEquals(2, PagerMath.pageCount(1001, page))
        assertEquals(4, PagerMath.pageCount(3999, page))
    }

    @Test
    fun maxScrollIsClampedToZeroForShortContent() {
        assertEquals(0, PagerMath.maxScroll(500, page))
        assertEquals(0, PagerMath.maxScroll(1000, page))
        assertEquals(500, PagerMath.maxScroll(1500, page))
    }

    @Test
    fun lastPartialPageIsReachableButNotBeyond() {
        // 内容 2500：3 页，最后一页只显示 500，合法偏移是 maxScroll
        val content = 2500
        assertEquals(3, PagerMath.pageCount(content, page))
        assertEquals(1500, PagerMath.maxScroll(content, page))
        assertEquals(0, PagerMath.pageOffset(0, content, page))
        assertEquals(1000, PagerMath.pageOffset(1, content, page))
        assertEquals(1500, PagerMath.pageOffset(2, content, page))
        assertEquals(1500, PagerMath.pageOffset(9, content, page))
        assertEquals(2, PagerMath.pageOf(1500, content, page))
    }

    @Test
    fun targetPageScrollNeverGoesPastContent() {
        val content = 3000
        assertEquals(1000, PagerMath.targetPageScroll(0, forward = true, content, page))
        assertEquals(2000, PagerMath.targetPageScroll(1000, forward = true, content, page))
        // 已在最后一页：目标仍是当前位置，调用方据此判断「翻不动」
        assertEquals(2000, PagerMath.targetPageScroll(2000, forward = true, content, page))
        assertEquals(1000, PagerMath.targetPageScroll(2000, forward = false, content, page))
        assertEquals(0, PagerMath.targetPageScroll(0, forward = false, content, page))
    }

    @Test
    fun forwardFromSecondToLastPageReachesPartialLastPage() {
        val content = 2500
        // 第 2 页 → 第 3 页（最后不足一屏的那页）
        assertEquals(1500, PagerMath.targetPageScroll(1000, forward = true, content, page))
        // 第 3 页再往前翻：不动
        assertEquals(1500, PagerMath.targetPageScroll(1500, forward = true, content, page))
        // 第 3 页往回翻：回到第 2 页
        assertEquals(1000, PagerMath.targetPageScroll(1500, forward = false, content, page))
    }

    @Test
    fun everyPageTurnMovesExactlyOneViewport() {
        // 连续翻页必须真正改变偏移量，否则就是「页码在涨、正文不动」
        var scroll = 0
        val content = 5000
        val seen = mutableListOf(scroll)
        repeat(4) {
            scroll = PagerMath.targetPageScroll(scroll, forward = true, content, page)
            seen.add(scroll)
        }
        assertEquals(listOf(0, 1000, 2000, 3000, 4000), seen)
        // 到最后一页再翻不动
        assertEquals(4000, PagerMath.targetPageScroll(4000, forward = true, content, page))
    }

    @Test
    fun backwardFromPartialPageSnapsToPreviousBoundary() {
        // 用户手动停在第 2 页中间（不是页边界），按「上一页」应回到第 1 页开头
        val content = 3000
        assertEquals(0, PagerMath.targetPageScroll(1700, forward = false, content, page))
        assertEquals(1000, PagerMath.targetPageScroll(2700, forward = false, content, page))
    }

    @Test
    fun atEndOnlyAfterLastPage() {
        val content = 3000
        assertFalse(PagerMath.isAtEnd(0, content, page))
        assertFalse(PagerMath.isAtEnd(1000, content, page))
        assertTrue(PagerMath.isAtEnd(2000, content, page))
        // 内容不足一页：打开就算到底
        assertTrue(PagerMath.isAtEnd(0, 800, page))
    }

    @Test
    fun ratioRoundTripSurvivesReflow() {
        // 原内容 5 页，读到第 3 页
        val ratio = PagerMath.ratio(2000, 5000, page)
        assertEquals(0.5f, ratio, 0.001f)
        // 换字号后内容变成 10 页，应该回到同样的相对位置
        assertEquals(4500, PagerMath.offsetForRatio(ratio, 10000, page))
        assertEquals(0, PagerMath.offsetForRatio(0f, 10000, page))
        assertEquals(9000, PagerMath.offsetForRatio(1f, 10000, page))
    }

    @Test
    fun degenerateInputsDoNotCrash() {
        assertEquals(0, PagerMath.pageOf(500, 500, 0))
        assertEquals(1, PagerMath.pageCount(500, 0))
        assertEquals(0f, PagerMath.ratio(500, 0, 0), 0.001f)
        assertEquals(0, PagerMath.targetPageScroll(500, true, 0, 0))
        assertEquals(0, PagerMath.pageOffset(3, 0, 0))
    }
}
