package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.ReaderScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批注相关脚本接口（选区上报 / 高亮渲染 / 定位）必须在注入脚本里存在。 */
class ReaderAnnotationScriptTest {

    private val script = ReaderScript.build(
        paged = true,
        padPx = 20,
        vwDp = 411,
        vhDp = 624,
        density = 1.75f,
    )

    @Test
    fun exposesAnnotationApi() {
        listOf("highlights:", "find:", "clearSelection:").forEach {
            assertTrue("缺少 $it", script.contains(it))
        }
    }

    @Test
    fun usesNonMutatingHighlightApi() {
        // 高亮用 CSS Custom Highlight API 渲染，不能改 DOM（否则保存的文本节点路径会失效）
        assertTrue(script.contains("CSS.highlights"))
        assertTrue(script.contains("new Highlight("))
    }

    @Test
    fun reportsSelectionWithNodePaths() {
        assertTrue(script.contains("B.selection("))
        assertTrue(script.contains("pathOf("))
        assertTrue(script.contains("nodeAt("))
        assertTrue(script.contains("selectionCleared"))
    }

    @Test
    fun clickCarriesCaretPathAndOffsetForHitTesting() {
        // 命中判定在 Kotlin 侧，脚本必须把落点的 (节点路径, 字符偏移) 一起传上来
        assertTrue(script.contains("document.caretRangeFromPoint"))
        assertTrue(script.contains("pathOf(node)"))
        assertTrue(script.contains("var offset = caret ? caret.startOffset : -1"))
        assertFalse(script.contains("intersectsNode"))
    }
}
