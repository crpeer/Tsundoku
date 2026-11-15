package app.tsundoku.reader

import app.tsundoku.reader.data.prefs.ReaderTapZone
import app.tsundoku.reader.ui.reader.ReaderScript
import app.tsundoku.reader.ui.reader.sanitizeForScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页脚本与正文净化。
 *
 * 脚本一旦有语法错误就会静默失效（阅读器退回视口分页），所以这里把关键结构钉住。
 */
class ReaderScriptTest {

    private val paged = ReaderScript.build(
        paged = true,
        padPx = 20,
        tapZone = ReaderTapZone.DEFAULT,
        vwDp = 411,
        vhDp = 624,
        density = 1.75f,
    )

    @Test
    fun placeholdersAreReplaced() {
        assertFalse(paged.contains("__PAGED__"))
        assertFalse(paged.contains("__ZONE__"))
        assertFalse(paged.contains("__PAD__"))
        assertTrue(paged.contains("var PAGED = true"))
        assertTrue(paged.contains("var PAD = 20"))
        assertTrue(paged.contains("var CFG_VW = 411"))
        assertTrue(paged.contains("var CFG_VH = 624"))
        assertTrue(paged.contains("var DPR = 1.750"))
        assertTrue(paged.contains("var ZONE = 'default'"))
    }

    @Test
    fun scrollBuildDisablesPaging() {
        val scroll = ReaderScript.build(
            paged = false,
            padPx = 12,
            tapZone = ReaderTapZone.EDGE,
            vwDp = 411,
            vhDp = 624,
            density = 1.75f,
        )
        assertTrue(scroll.contains("var PAGED = false"))
        assertTrue(scroll.contains("var ZONE = 'edge'"))
        assertTrue(scroll.contains("var PAD = 12"))
    }

    @Test
    fun scriptExposesTheApiTheReaderCalls() {
        listOf("turn:", "page:", "ratio:", "setZone:", "measure:", "info:").forEach {
            assertTrue("缺少 $it", paged.contains(it))
        }
        assertTrue(paged.contains("TsundokuBridge"))
    }

    @Test
    fun setZoneCallUsesQuotedKey() {
        assertTrue(ReaderScript.setZoneCall(ReaderTapZone.KINDLE).contains("'kindle'"))
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
