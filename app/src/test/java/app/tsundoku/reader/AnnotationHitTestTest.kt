package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.AnnotationHitTest
import app.tsundoku.reader.ui.reader.AnnotationRange
import app.tsundoku.reader.ui.reader.toHighlightJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮点击命中判定。
 *
 * 回归的是这个缺陷：原实现用 `Range.intersectsNode(文本节点)`，只回答「这个节点与高亮范围有没有交集」。
 * 于是同一段文字里只有部分被高亮时，点在未高亮的字上也会弹出高亮弹窗。
 * 现在比较「点击处的 (节点路径, 字符偏移)」是否落在 `[start, end)` 内。
 */
class AnnotationHitTestTest {

    /** 一个文本节点（路径 "0/1/0"）里第 3..7 个字被高亮。 */
    private val partial = AnnotationRange(
        id = "a1",
        startPath = "0/1/0",
        startOffset = 3,
        endPath = "0/1/0",
        endOffset = 7,
        color = 0,
    )

    @Test
    fun offsetInsideTheHighlightHits() {
        for (offset in 3..6) {
            assertSame("offset=$offset 应命中", partial, AnnotationHitTest.hit(listOf(partial), "0/1/0", offset))
        }
    }

    @Test
    fun offsetOutsideTheHighlightDoesNotHit() {
        // 同一个文本节点内的其他文字：这正是原实现误判的场景
        for (offset in listOf(0, 1, 2, 7, 8, 50)) {
            assertNull("offset=$offset 不该命中", AnnotationHitTest.hit(listOf(partial), "0/1/0", offset))
        }
    }

    @Test
    fun endOffsetIsExclusiveAndStartIsInclusive() {
        assertEquals(partial, AnnotationHitTest.hit(listOf(partial), "0/1/0", 3))
        assertNull(AnnotationHitTest.hit(listOf(partial), "0/1/0", 7))
    }

    @Test
    fun otherTextNodesDoNotHit() {
        assertNull(AnnotationHitTest.hit(listOf(partial), "0/1/1", 4))
        assertNull(AnnotationHitTest.hit(listOf(partial), "0/2/0", 4))
        assertNull(AnnotationHitTest.hit(listOf(partial), "1/1/0", 4))
    }

    @Test
    fun multipleHighlightsAreIdentifiedIndependently() {
        val second = AnnotationRange("a2", "0/2/0", 10, "0/2/0", 20, color = 1)
        val third = AnnotationRange("a3", "0/3/0", 0, "0/3/1", 5, color = 2) // 跨节点
        val all = listOf(partial, second, third)

        assertEquals("a1", AnnotationHitTest.hit(all, "0/1/0", 5)?.id)
        assertEquals("a2", AnnotationHitTest.hit(all, "0/2/0", 15)?.id)
        assertEquals("a3", AnnotationHitTest.hit(all, "0/3/0", 3)?.id)

        // 落在两条高亮之间（各自文本节点之外）不算命中
        assertNull(AnnotationHitTest.hit(all, "0/1/0", 8))
        assertNull(AnnotationHitTest.hit(all, "0/2/0", 5))
    }

    @Test
    fun crossNodeRangeCoversWholeNodesInBetween() {
        val across = AnnotationRange("x", "0/1/0", 5, "0/3/0", 2, color = 0)
        assertNull(AnnotationHitTest.hit(listOf(across), "0/1/0", 4))
        assertEquals("x", AnnotationHitTest.hit(listOf(across), "0/1/0", 5)?.id)
        // 中间的整个节点都算在范围内（不论偏移）
        assertEquals("x", AnnotationHitTest.hit(listOf(across), "0/2/0", 0)?.id)
        assertEquals("x", AnnotationHitTest.hit(listOf(across), "0/2/0", 999)?.id)
        assertEquals("x", AnnotationHitTest.hit(listOf(across), "0/3/0", 1)?.id)
        assertNull(AnnotationHitTest.hit(listOf(across), "0/3/0", 2))
    }

    @Test
    fun plainTextClickWithNoAnnotationsIsNeverAHit() {
        assertNull(AnnotationHitTest.hit(emptyList(), "0/1/0", 4))
    }

    @Test
    fun missingCaretOrMalformedPathIsRejected() {
        // 拿不到 caret 时脚本传 offset = -1；路径格式非法时 parsePath 返回 null。两者都不算命中。
        assertNull(AnnotationHitTest.hit(listOf(partial), "0/1/0", -1))
        assertNull(AnnotationHitTest.hit(listOf(partial), "", -1))
        assertNull(AnnotationHitTest.hit(listOf(partial), "0/a/0", 4))
        assertNull(AnnotationHitTest.hit(listOf(partial), "0//0", 4))
    }

    // ---------- body 根节点：空路径是合法边界，-1 才是「位置不可用」 ----------

    @Test
    fun emptyPathParsesToTheBodyRoot() {
        assertEquals(emptyList<Int>(), AnnotationHitTest.parsePath(""))
    }

    @Test
    fun bodyRootCanBeBothRangeStartAndEnd() {
        // 范围 = 「body 的第 1 个子节点之前」到「body 的第 3 个子节点之前」
        val whole = AnnotationRange("r1", startPath = "", startOffset = 1, endPath = "", endOffset = 3, color = 0)

        // 起点包含、终点不包含
        assertSame(whole, AnnotationHitTest.hit(listOf(whole), "", 1))
        assertSame(whole, AnnotationHitTest.hit(listOf(whole), "", 2))
        assertNull(AnnotationHitTest.hit(listOf(whole), "", 3))
        assertNull(AnnotationHitTest.hit(listOf(whole), "", 0))

        // 根节点作为祖先参与比较：范围内更深的节点要命中，范围外的不要
        assertSame(whole, AnnotationHitTest.hit(listOf(whole), "1/0", 2))
        assertNull(AnnotationHitTest.hit(listOf(whole), "3/0", 0))
    }

    @Test
    fun bodyRootStartsTheRangeAndTextEndsIt() {
        val range = AnnotationRange("r2", startPath = "", startOffset = 1, endPath = "2/0", endOffset = 3, color = 1)

        assertSame(range, AnnotationHitTest.hit(listOf(range), "", 1))
        assertSame(range, AnnotationHitTest.hit(listOf(range), "1/0", 0))
        assertSame(range, AnnotationHitTest.hit(listOf(range), "2/0", 2))
        assertNull(AnnotationHitTest.hit(listOf(range), "2/0", 3))
        assertNull(AnnotationHitTest.hit(listOf(range), "", 0))
    }

    @Test
    fun bodyRootAsEndStaysExclusive() {
        val range = AnnotationRange("r3", startPath = "0/0", startOffset = 5, endPath = "", endOffset = 2, color = 2)

        assertSame(range, AnnotationHitTest.hit(listOf(range), "0/0", 5))
        assertSame(range, AnnotationHitTest.hit(listOf(range), "1/0", 0))
        // body offset 2 =「第 2 个子节点之前」，正好是终点，不包含
        assertNull(AnnotationHitTest.hit(listOf(range), "", 2))
        assertNull(AnnotationHitTest.hit(listOf(range), "2/0", 0))
    }

    @Test
    fun emptyPathWithMissingCaretOffsetNeverHits() {
        // 哪怕范围整个覆盖根节点，offset = -1 也要判为「位置不可用」
        val all = AnnotationRange("r4", startPath = "", startOffset = 0, endPath = "", endOffset = 99, color = 3)

        assertNull(AnnotationHitTest.hit(listOf(all), "", -1))
        assertSame(all, AnnotationHitTest.hit(listOf(all), "", 0))
    }

    @Test
    fun documentOrderComparison() {
        // 同一节点内比 offset
        assertEquals(0, AnnotationHitTest.compare(listOf(0, 1, 2), 3, listOf(0, 1, 2), 3))
        assertTrue(AnnotationHitTest.compare(listOf(0, 1, 2), 4, listOf(0, 1, 2), 3) > 0)
        assertTrue(AnnotationHitTest.compare(listOf(0, 1, 2), 2, listOf(0, 1, 2), 3) < 0)
        // 不同分支：比该层下标（文档序）
        assertTrue(AnnotationHitTest.compare(listOf(0, 1), 0, listOf(0, 2), 0) < 0)
        assertTrue(AnnotationHitTest.compare(listOf(0, 2), 99, listOf(0, 1), 0) > 0)
        assertTrue(AnnotationHitTest.compare(listOf(0, 0), 7, listOf(1, 0), 0) < 0)
    }

    // ---------- 祖先 / 后代：不能只比路径长度 ----------

    @Test
    fun ancestorOffsetBeforeChildIsBefore() {
        // (父 [0], offset 1) = 「第 1 个子节点之前」，而后代是 [0,3] → 父在前
        assertTrue(AnnotationHitTest.compare(listOf(0), 1, listOf(0, 3), 0) < 0)
        assertTrue(AnnotationHitTest.compare(listOf(0), 0, listOf(0, 3), 5) < 0)
    }

    @Test
    fun ancestorOffsetExactlyAtChildBoundaryIsBefore() {
        // 规范：index(child) == offset 时返回 -1（祖先在前）。
        // 语义上 (parent, k) 与 (child_k, 0) 是同一个位置，落在这个位置上时
        // 「起点包含 / 终点排除」由 hit() 的 >=0 / <0 完成，所以这里断言 before。
        assertTrue(AnnotationHitTest.compare(listOf(0), 3, listOf(0, 3), 0) < 0)
        // 反向：后代相对祖先在后
        assertTrue(AnnotationHitTest.compare(listOf(0, 3), 0, listOf(0), 3) > 0)
    }

    @Test
    fun ancestorOffsetAfterChildIsAfter() {
        // 这正是旧实现的 bug：offset 5 已越过第 3 个子节点，父节点在后，而不是在前
        assertTrue(AnnotationHitTest.compare(listOf(0), 5, listOf(0, 3), 0) > 0)
        // 旧实现会因为 p.size(1) < q.size(2) 判成「父在前」，反向断言把它钉住
        assertTrue(AnnotationHitTest.compare(listOf(0, 3), 0, listOf(0), 5) < 0)
    }

    @Test
    fun deeperAncestorCasesAreHandled() {
        // 三层：祖先 [0,1] 与后代 [0,1,2,0]
        assertTrue(AnnotationHitTest.compare(listOf(0, 1), 1, listOf(0, 1, 2, 0), 0) < 0)
        assertTrue(AnnotationHitTest.compare(listOf(0, 1), 2, listOf(0, 1, 2, 0), 0) < 0) // 相等 → before
        assertTrue(AnnotationHitTest.compare(listOf(0, 1), 3, listOf(0, 1, 2, 0), 0) > 0)
    }

    @Test
    fun differentTextNodesOrder() {
        assertTrue(AnnotationHitTest.compare(listOf(0, 1, 0), 3, listOf(0, 2, 0), 5) < 0)
        assertTrue(AnnotationHitTest.compare(listOf(0, 2, 0), 5, listOf(0, 1, 0), 3) > 0)
        // 同父下相邻文本节点
        assertTrue(AnnotationHitTest.compare(listOf(0, 0), 99, listOf(0, 1), 0) < 0)
    }

    // ---------- 边界点落点：hit() 的实际语义 ----------

    @Test
    fun hitAtAncestorChildBoundary() {
        // 高亮从「父节点第 1 个子节点之前」开始（Element 起点），覆盖到后面
        val range = AnnotationRange("e1", "0/1", 1, "0/9/0", 4, color = 0)
        // 点在子节点 3 的文本里 → 在高亮范围内
        assertEquals("e1", AnnotationHitTest.hit(listOf(range), "0/1/3/0", 2)?.id)
        // 点在子节点 0 的文本里 → 在起点之前，不该命中
        assertNull(AnnotationHitTest.hit(listOf(range), "0/1/0/0", 2))
    }

    @Test
    fun hitAcrossParagraphs() {
        // 起点在第一段文本内，终点在第三段文本内（跨节点）
        val range = AnnotationRange("p1", "0/1/0", 4, "0/5/0", 2, color = 1)
        assertEquals("p1", AnnotationHitTest.hit(listOf(range), "0/1/0", 4)?.id)
        assertEquals("p1", AnnotationHitTest.hit(listOf(range), "0/1/0", 500)?.id)
        // 中间整段（第二段）都算在范围内
        assertEquals("p1", AnnotationHitTest.hit(listOf(range), "0/3/0", 0)?.id)
        assertEquals("p1", AnnotationHitTest.hit(listOf(range), "0/3/0", 999)?.id)
        assertEquals("p1", AnnotationHitTest.hit(listOf(range), "0/5/0", 1)?.id)
        // 终点之后 / 起点之前
        assertNull(AnnotationHitTest.hit(listOf(range), "0/5/0", 2))
        assertNull(AnnotationHitTest.hit(listOf(range), "0/1/0", 3))
        assertNull(AnnotationHitTest.hit(listOf(range), "0/0/0", 10))
        assertNull(AnnotationHitTest.hit(listOf(range), "0/7/0", 0))
    }

    @Test
    fun oldElementBoundaryDataStillHits() {
        // 兼容旧数据：早期保存的批注可能带 Element 边界（offset 是子节点下标），
        // 本次修复不改数据结构，这类记录必须照常命中。
        val legacy = AnnotationRange("legacy", "0/2", 0, "0/2/1", 3, color = 2)
        assertEquals("legacy", AnnotationHitTest.hit(listOf(legacy), "0/2/0", 5)?.id)
        assertEquals("legacy", AnnotationHitTest.hit(listOf(legacy), "0/2/1", 2)?.id)
        assertNull(AnnotationHitTest.hit(listOf(legacy), "0/2/1", 3))
        assertNull(AnnotationHitTest.hit(listOf(legacy), "0/1/0", 0))
    }

    @Test
    fun ancestorOffsetPointingPastEarlierChildren() {
        // 起点是 Element 且 offset 指向靠后的子节点：它前面的子节点都算「在起点之前」。
        // 旧实现按路径长度判断，会把 ([0,2], 3) 与 ([0,2,0], 0) 判成「父在前」，
        // 于是下面第一条断言在旧代码下会错误命中。
        val later = AnnotationRange("later", "0/2", 3, "0/5/0", 1, color = 3)
        assertNull(AnnotationHitTest.hit(listOf(later), "0/2/0", 0))
        assertNull(AnnotationHitTest.hit(listOf(later), "0/2/2", 7))
        // 正好落在第 3 个子节点 → 起点包含
        assertEquals("later", AnnotationHitTest.hit(listOf(later), "0/2/3/0", 0)?.id)
        // 中间的整段都命中
        assertEquals("later", AnnotationHitTest.hit(listOf(later), "0/3/0", 42)?.id)
        // 终点之后不命中
        assertNull(AnnotationHitTest.hit(listOf(later), "0/5/0", 1))
        assertNull(AnnotationHitTest.hit(listOf(later), "0/6/0", 0))
    }

    @Test
    fun highlightJsonKeepsIdsAndOffsets() {
        val json = listOf(partial, AnnotationRange("b\"q", "0/9/0", 1, "0/9/0", 2, 3))
            .toHighlightJson()
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"id\":\"a1\""))
        assertTrue(json.contains("\"sp\":\"0/1/0\""))
        assertTrue(json.contains("\"so\":3"))
        assertTrue(json.contains("\"eo\":7"))
        // 引号必须被转义，否则整段脚本会语法错误
        assertTrue(json.contains("\\\""))
    }
}
