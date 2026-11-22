package app.tsundoku.reader

import app.tsundoku.reader.data.prefs.ReaderTapZone
import app.tsundoku.reader.ui.reader.AnnotationRange
import app.tsundoku.reader.ui.reader.ReaderTapRouter
import app.tsundoku.reader.ui.reader.TapAction
import app.tsundoku.reader.ui.reader.TapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文点击的完整决策：命中高亮还是执行点击区域。
 *
 * 回归的是「点高亮」这条路原先用 `Range.intersectsNode()` 判定带来的两个问题：
 * 同一文本节点里点未高亮的字也会弹菜单；以及点击区域被禁用时点正文仍会切换工具栏。
 */
class ReaderTapRouterTest {

    private val w = 411f
    private val h = 624f

    /** 段落 0 的第一个文本节点，第 5..11 个字符。 */
    private val insideNode = AnnotationRange(
        id = "a1",
        startPath = "0/0",
        startOffset = 5,
        endPath = "0/0",
        endOffset = 12,
        color = 0,
    )

    private fun route(
        path: String,
        offset: Int,
        zone: ReaderTapZone = ReaderTapZone.DEFAULT,
        x: Float = w / 2f,
        y: Float = h / 2f,
        ranges: List<AnnotationRange> = listOf(insideNode),
    ): TapResult = ReaderTapRouter.route(ranges, path, offset, zone, x, y, w, h)

    private fun highlightId(result: TapResult): String {
        assertTrue("期望命中高亮，实际是 $result", result is TapResult.Highlight)
        return (result as TapResult.Highlight).id
    }

    private fun action(result: TapResult): TapAction {
        assertTrue("期望执行点击区域动作，实际是 $result", result is TapResult.Action)
        return (result as TapResult.Action).action
    }

    // ---------- 同一文本节点 ----------

    @Test
    fun clickOnHighlightedCharacterOpensTheAnnotation() {
        assertEquals("a1", highlightId(route("0/0", 7)))
    }

    @Test
    fun clickOnUnhighlightedCharacterInTheSameNodeDoesNotOpenIt() {
        // 左边第 2 个字符、右边第 13 个字符：都在同一文本节点内，但不在高亮里
        assertEquals(TapAction.BACKWARD, action(route("0/0", 1, x = 10f)))
        assertEquals(TapAction.FORWARD, action(route("0/0", 13, x = w - 10f)))
    }

    @Test
    fun startIsInclusiveAndEndIsExclusive() {
        assertEquals("a1", highlightId(route("0/0", 5)))
        assertEquals("a1", highlightId(route("0/0", 11)))
        assertTrue(route("0/0", 4) !is TapResult.Highlight)
        assertTrue(route("0/0", 12) !is TapResult.Highlight)
    }

    // ---------- 命中优先于点击区域 ----------

    @Test
    fun hitWinsOverPagingZones() {
        // 左三分之一正常是上一页、中间是显隐工具栏；点在高亮上都必须打开批注
        assertEquals("a1", highlightId(route("0/0", 7, x = 5f)))
        assertEquals("a1", highlightId(route("0/0", 7, x = w / 2f)))
    }

    @Test
    fun disabledZoneStillOpensHighlightsButIgnoresPlainText() {
        assertEquals("a1", highlightId(route("0/0", 7, zone = ReaderTapZone.DISABLED)))
        assertEquals(TapAction.NONE, action(route("0/0", 1, zone = ReaderTapZone.DISABLED)))
        assertEquals(TapAction.NONE, action(route("0/0", 7, zone = ReaderTapZone.DISABLED, ranges = emptyList())))
    }

    // ---------- 跨节点 / 跨段落 ----------

    @Test
    fun crossNodeRangeCoversIntermediateNodes() {
        val range = AnnotationRange("a2", "0/0", 4, "2/1", 3, color = 1)
        val ranges = listOf(range)

        assertEquals("a2", highlightId(route("0/0", 4, ranges = ranges)))
        // 中间整个段落都在范围内（整段选择时 startContainer 是 Element，offset 是子节点下标）
        assertEquals("a2", highlightId(route("1", 1, ranges = ranges)))
        assertEquals("a2", highlightId(route("1/0", 0, ranges = ranges)))
        assertEquals("a2", highlightId(route("2/1", 2, ranges = ranges)))
        assertTrue(route("0/0", 3, ranges = ranges) !is TapResult.Highlight)
        assertTrue(route("2/1", 3, ranges = ranges) !is TapResult.Highlight)
    }

    @Test
    fun elementBoundaryInsideTheRangeHits() {
        // caretRangeFromPoint 落在段落内部的空隙时给出 Element 容器：路径 "1"、offset 是子节点下标
        val range = AnnotationRange("a3", "0/1", 2, "2/0", 0, color = 2)
        assertEquals("a3", highlightId(route("1", 0, ranges = listOf(range))))
        assertEquals("a3", highlightId(route("1", 1, ranges = listOf(range))))
    }

    @Test
    fun elementBoundaryCoincidingWithTheStartStaysOutside() {
        // 规范里 (parent, k) 排在 (child_k, 0) 之前 —— 正是 compare() 修的那条规则，
        // 所以「同一个视觉位置的两种写法」在起点处算范围外。
        // 真实点击落在文字上时拿到的是 Text 节点，这种情形只在点行间空隙时出现。
        val range = AnnotationRange("a4", "1/0", 0, "1/0", 8, color = 3)
        assertTrue(route("1", 0, ranges = listOf(range)) !is TapResult.Highlight)
        assertEquals("a4", highlightId(route("1/0", 0, ranges = listOf(range))))
    }

    // ---------- 取不到点击位置 ----------

    @Test
    fun missingCaretPositionNeverOpensTheHighlightMenu() {
        // 脚本拿不到 caret 时上报空路径 / -1：任何批注都不能被命中
        assertTrue(route("", -1) !is TapResult.Highlight)
        assertTrue(route("", 0) !is TapResult.Highlight)
        assertTrue(route("0/0", -1) !is TapResult.Highlight)
        assertEquals(TapAction.MENU, action(route("", -1)))
    }

    // ---------- 原有菜单 / 编辑 / 删除链路 ----------

    @Test
    fun annotationIdIsPassedThroughUnchanged() {
        // 菜单、编辑、删除都靠这个 id 找回批注，不能被改写
        val range = AnnotationRange("1699-3f2a:中", "0/0", 0, "0/0", 4, color = 3)
        assertEquals("1699-3f2a:中", highlightId(route("0/0", 2, ranges = listOf(range))))
    }

    @Test
    fun overlappingHighlightsKeepTheOldPriority() {
        // 与脚本渲染顺序一致：列表里靠前的那条先命中
        val first = AnnotationRange("a1", "0/0", 0, "0/0", 10, color = 0)
        val second = AnnotationRange("a2", "0/0", 4, "0/0", 20, color = 1)
        assertEquals("a1", highlightId(route("0/0", 6, ranges = listOf(first, second))))
        assertEquals("a2", highlightId(route("0/0", 15, ranges = listOf(first, second))))
    }

    @Test
    fun plainTextWithoutAnnotationsStillUsesTapZones() {
        assertEquals(TapAction.MENU, action(route("0/0", 7, ranges = emptyList())))
        assertEquals(TapAction.BACKWARD, action(route("0/0", 7, ranges = emptyList(), x = 5f)))
        assertEquals(TapAction.FORWARD, action(route("0/0", 7, ranges = emptyList(), x = w - 5f)))
    }
}
