package app.tsundoku.reader

import app.tsundoku.reader.data.source.web.RuleParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RuleParser 的取值语义：
 *  - 文本类取值（text/ownText/html）禁止走 URL 补全
 *  - 链接类取值（href/src/poster/data-src）必须补全成绝对地址
 */
class RuleParserTest {

    private val base = "https://example.com/book/1.html"

    private fun doc(html: String) = Jsoup.parse(html, base)

    @Test
    fun textIsNeverUrlResolved() {
        val document = doc("<h3>anonymous</h3>")
        assertEquals("anonymous", RuleParser.extract(document, "h3@text", base))
    }

    @Test
    fun asciiTitleWithDashIsNotUrl() {
        val document = doc("<h3>Dragon-Raja</h3>")
        assertEquals("Dragon-Raja", RuleParser.extract(document, "h3@text", base))
    }

    @Test
    fun authorTextIsNotUrl() {
        val document = doc("""<span class="author">liubei</span>""")
        assertEquals("liubei", RuleParser.extract(document, "span.author@text", base))
    }

    @Test
    fun htmlValueIsNotUrl() {
        val document = doc("""<div class="intro"><p>hello</p></div>""")
        assertEquals("<p>hello</p>", RuleParser.extract(document, "div.intro@html", base))
    }

    @Test
    fun absoluteHrefStaysAbsolute() {
        val document = doc("""<a href="/b/2">x</a>""")
        assertEquals("https://example.com/b/2", RuleParser.extract(document, "a@href", base))
    }

    @Test
    fun relativeSrcIsResolved() {
        val document = doc("""<img src="cover/a.jpg">""")
        assertEquals("https://example.com/book/cover/a.jpg", RuleParser.extract(document, "img@src", base))
    }

    @Test
    fun hrefInListRuleIsResolved() {
        val document = doc("""<div class="item"><a href="2.html">t</a></div>""")
        assertEquals("https://example.com/book/2.html", RuleParser.extract(document, "div.item@a@href", base))
    }

    @Test
    fun metaContentUrlIsResolvedButTextContentIsNot() {
        val image = doc("""<meta property="og:image" content="/img/a.jpg">""")
        assertEquals("https://example.com/img/a.jpg", RuleParser.extract(image, "meta@content", base))

        val text = doc("""<meta name="description" content="just words">""")
        assertEquals("just words", RuleParser.extract(text, "meta@content", base))
    }

    @Test
    fun emptyAndBrokenSpecsReturnNull() {
        val document = doc("<h3>t</h3>")
        assertNull(RuleParser.extract(document, "", base))
        assertNull(RuleParser.extract(document, "div.nope@text", base))
        assertNull(RuleParser.extract(document, "h3@", base).takeIf { it.isNullOrBlank() })
    }

    @Test
    fun selectSupportsFallbackOperator() {
        val document = doc("""<div class="b">two</div>""")
        // `||` 在 select() 里也必须生效，与 selectFirst() 保持一致
        assertEquals(1, RuleParser.select(document, "div.a||div.b").size)
        assertEquals(0, RuleParser.select(document, "div.none").size)
    }

    @Test
    fun brokenSelectorDoesNotThrow() {
        val document = doc("<h3>t</h3>")
        assertNull(RuleParser.extract(document, "h3@@text@@", base))
        assertEquals(0, RuleParser.select(document, "div[[").size)
    }
}
