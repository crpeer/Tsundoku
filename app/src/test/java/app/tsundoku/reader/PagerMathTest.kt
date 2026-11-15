package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.PagerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页位置计算。
 *
 * 原来的缺陷就是「页码在涨、正文不动 / 末尾滚出空白页」，所以这里除了具体数字，
 * 还断言两条不变量：最后一页必须正好落在实测的 maxScroll 上，且每次翻页只前进一屏。
 */
class PagerMathTest {

    private val page = 1000

    @Test
    fun pageIndexFollowsScrollExactly() {
        val max = 2000
        assertEquals(0, PagerMath.pageOf(0, max, page))
        assertEquals(0, PagerMath.pageOf(999, max, page))
        assertEquals(1, PagerMath.pageOf(1000, max, page))
        assertEquals(1, PagerMath.pageOf(1999, max, page))
        assertEquals(2, PagerMath.pageOf(2000, max, page))
        // 异常大的滚动值也不能越界
        assertEquals(2, PagerMath.pageOf(9999, max, page))
    }

    @Test
    fun pageCountCoversTheLastPartialScreen() {
        assertEquals(1, PagerMath.pageCount(0, page))
        // 只有一点点溢出时，最后一页会和上一页高度重叠——宁可重叠也不能漏字
        assertEquals(2, PagerMath.pageCount(10, page))
        assertEquals(2, PagerMath.pageCount(1000, page))
        assertEquals(3, PagerMath.pageCount(2000, page))
        // 多出的 1 像素也需要单独一页，否则结尾那句永远看不到
        assertEquals(4, PagerMath.pageCount(2001, page))
        // 实测值：内容 2297px、视口 1092px → 2 页装不下最后 113px，必须 3 页
        assertEquals(3, PagerMath.pageCount(1205, 1092))
    }

    @Test
    fun lastPageOffsetEqualsMeasuredMaxScroll() {
        for (max in intArrayOf(1, 500, 1000, 1001, 1205, 2000, 2001, 2500, 9999)) {
            val pages = PagerMath.pageCount(max, page)
            assertEquals(
                "max=$max 时最后一页必须正好停在 maxScroll",
                max,
                PagerMath.pageOffset(pages - 1, max, page),
            )
            // 最后一页之后的请求被夹回最后一页
            assertEquals(max, PagerMath.pageOffset(pages + 3, max, page))
        }
    }

    @Test
    fun everyPageTurnMovesOneViewportAndStopsAtEnd() {
        for (max in intArrayOf(0, 500, 1000, 1205, 2000, 2001, 2500, 4000)) {
            var scroll = 0
            var guard = 0
            while (guard++ < 20) {
                val next = PagerMath.targetPageScroll(scroll, forward = true, max, page)
                if (next == scroll) break
                // 每次翻页最多前进一屏，且绝不越过内容末尾
                assertTrue("翻页步长过大: $scroll -> $next (max=$max)", next - scroll <= page)
                assertTrue("翻过头: $next > $max", next <= max)
                assertTrue("翻页后位置没有前进", next > scroll)
                scroll = next
            }
            // 一路翻到底，必须正好停在 maxScroll
            assertEquals("max=$max 时应能翻到底", max, scroll)
            // 到底之后再往前翻：不动（交给章节切换）
            assertEquals(max, PagerMath.targetPageScroll(scroll, forward = true, max, page))
        }
    }

    @Test
    fun backwardFromPartialPageSnapsToPreviousBoundary() {
        val max = 2000
        assertEquals(0, PagerMath.targetPageScroll(1700, forward = false, max, page))
        assertEquals(1000, PagerMath.targetPageScroll(2700, forward = false, max, page))
    }

    @Test
    fun shortChapterIsSinglePage() {
        assertEquals(1, PagerMath.pageCount(0, page))
        assertEquals(0, PagerMath.pageOf(0, 0, page))
        assertEquals(0, PagerMath.pageOffset(0, 0, page))
        assertTrue(PagerMath.isAtEnd(0, 0))
        assertEquals(0, PagerMath.targetPageScroll(0, true, 0, page))
    }

    @Test
    fun atEndOnlyNearMeasuredMax() {
        val max = 2000
        assertFalse(PagerMath.isAtEnd(0, max))
        assertFalse(PagerMath.isAtEnd(1000, max))
        assertTrue(PagerMath.isAtEnd(2000, max))
        // 滚动模式留 24px 容差
        assertTrue(PagerMath.isAtEnd(1980, max, 24))
        assertFalse(PagerMath.isAtEnd(1900, max, 24))
    }

    @Test
    fun ratioRoundTripSurvivesReflow() {
        // 原来读到一半（maxScroll 4000 的 2000）
        val ratio = PagerMath.ratio(2000, 4000)
        assertEquals(0.5f, ratio, 0.001f)
        // 换字号后行变多，maxScroll 变成 9000，应该回到同样的相对位置
        assertEquals(4500, PagerMath.offsetForRatio(ratio, 9000))
        assertEquals(0, PagerMath.offsetForRatio(0f, 9000))
        assertEquals(9000, PagerMath.offsetForRatio(1f, 9000))
    }

    @Test
    fun degenerateInputsDoNotCrash() {
        assertEquals(0, PagerMath.pageOf(500, 0, 0))
        assertEquals(1, PagerMath.pageCount(0, 0))
        assertEquals(0f, PagerMath.ratio(500, 0), 0.001f)
        assertEquals(0, PagerMath.targetPageScroll(500, true, 0, 0))
        assertEquals(0, PagerMath.pageOffset(3, 0, 0))
    }
}
