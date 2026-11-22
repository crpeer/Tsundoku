package app.tsundoku.reader

import app.tsundoku.reader.ui.reader.ReaderScript
import app.tsundoku.reader.ui.reader.sanitizeForScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页脚本与正文净化。
 *
 * 脚本一旦有语法错误就会静默失效（阅读器退回视口分页），所以这里把关键结构钉住。
 * 点击区域与高亮命中的**判定**已移到 Kotlin（见 ReaderTapZoneMathTest / AnnotationHitTestTest），
 * 这里只保证脚本把它们需要的输入上报上来。
 */
class ReaderScriptTest {

    private val paged = ReaderScript.build(
        paged = true,
        padPx = 20,
        vwDp = 411,
        vhDp = 624,
        density = 1.75f,
    )

    @Test
    fun placeholdersAreReplaced() {
        assertFalse(paged.contains("__PAGED__"))
        assertFalse(paged.contains("__PAD__"))
        assertFalse(paged.contains("__VW__"))
        assertFalse(paged.contains("__VH__"))
        assertFalse(paged.contains("__DPR__"))
        assertTrue(paged.contains("var PAGED = true"))
        assertTrue(paged.contains("var PAD = 20"))
        assertTrue(paged.contains("var CFG_VW = 411"))
        assertTrue(paged.contains("var CFG_VH = 624"))
        assertTrue(paged.contains("var DPR = 1.750"))
    }

    @Test
    fun scrollBuildDisablesPaging() {
        val scroll = ReaderScript.build(
            paged = false,
            padPx = 12,
            vwDp = 411,
            vhDp = 624,
            density = 1.75f,
        )
        assertTrue(scroll.contains("var PAGED = false"))
        assertTrue(scroll.contains("var PAD = 12"))
    }

    @Test
    fun scriptExposesTheApiTheReaderCalls() {
        listOf("turn:", "page:", "ratio:", "measure:", "info:", "highlights:", "find:").forEach {
            assertTrue("缺少 $it", paged.contains(it))
        }
        assertTrue(paged.contains("TsundokuBridge"))
    }

    @Test
    fun clickReportsPointInsteadOfDecidingZoneInJs() {
        // 点击区域判定在 Kotlin：脚本必须上报坐标 + 文本位置
        assertTrue(paged.contains("B.point(e.clientX, e.clientY, path, offset)"))
        // 不能再出现脚本侧的区域判定（否则「禁用」会被当成「中间区域」）
        assertFalse(paged.contains("function zone("))
        assertFalse(paged.contains("var ZONE"))
        assertFalse(paged.contains("intersectsNode"))
    }

    @Test
    fun sanitizeDropsScriptsAndInlineHandlers() {
        val raw = """
            <html><body onload="evil()">
            <p onclick="steal()">正文</p>
            <script>window.hack=1</script>
            <script src="x.js"></script>
            </body></html>
        """.trimIndent()
        val cleaned = sanitizeForScript(raw)
        assertFalse(cleaned.contains("<script"))
        assertFalse(cleaned.contains("onload"))
        assertFalse(cleaned.contains("onclick"))
        assertTrue(cleaned.contains("正文"))
    }
}
