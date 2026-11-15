package app.tsundoku.reader

import app.tsundoku.reader.data.source.web.ContentPaginator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 正文分页：合并、去重、限流、失败降级。 */
class ContentPaginatorTest {

    private fun page(title: String, next: String?) =
        ContentPaginator.ParsedPage(html = "<p>$title</p>", nextUrl = next)

    @Test
    fun mergesAllPagesOfOneChapter() = runBlocking {
        val pages = mapOf(
            "https://s.com/1.html" to page("一", "https://s.com/2.html"),
            "https://s.com/2.html" to page("二", "https://s.com/3.html"),
            "https://s.com/3.html" to page("三", null),
        )
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { url -> "html:$url" },
            parse = { _, url -> pages.getValue(url) },
        )
        assertEquals(3, result.pages)
        assertEquals("<p>一</p><p>二</p><p>三</p>", result.html)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun stopsOnLoop() = runBlocking {
        val pages = mapOf(
            "https://s.com/1.html" to page("一", "https://s.com/2.html"),
            "https://s.com/2.html" to page("二", "https://s.com/1.html"),
        )
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { "x" },
            parse = { _, url -> pages.getValue(url) },
        )
        assertEquals(2, result.pages)
        assertEquals("<p>一</p><p>二</p>", result.html)
    }

    @Test
    fun respectsMaxPages() = runBlocking {
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            maxPages = 3,
            fetch = { "x" },
            parse = { _, url ->
                val n = url.substringAfterLast('/').substringBefore(".html").toInt()
                page("p$n", "https://s.com/${n + 1}.html")
            },
        )
        assertEquals(3, result.pages)
        assertTrue(result.truncated)
        assertEquals("<p>p1</p><p>p2</p><p>p3</p>", result.html)
    }

    @Test
    fun refusesCrossHostNextPage() = runBlocking {
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { "x" },
            parse = { _, url ->
                if (url.endsWith("1.html")) page("一", "https://evil.com/2.html") else page("坏", null)
            },
        )
        assertEquals(1, result.pages)
        assertTrue(result.errors.any { it.contains("同一站点") })
    }

    @Test
    fun pageFailureKeepsPreviousContent() = runBlocking {
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { url ->
                if (url.endsWith("2.html")) throw java.io.IOException("HTTP 500") else "x"
            },
            parse = { _, url -> page("一", "https://s.com/2.html") },
        )
        assertEquals(1, result.pages)
        assertEquals("<p>一</p>", result.html)
        assertTrue(result.errors.single().contains("HTTP 500"))
    }

    @Test
    fun emptyPageStopsGracefully() = runBlocking {
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { "" },
            parse = { _, _ -> page("x", null) },
        )
        assertEquals(0, result.pages)
        assertEquals("", result.html)
        assertFalse(result.errors.isEmpty())
    }

    @Test
    fun rejectsNonHttpNextUrl() = runBlocking {
        val result = ContentPaginator.collect(
            firstUrl = "https://s.com/1.html",
            fetch = { "x" },
            parse = { _, _ -> page("一", "javascript:alert(1)") },
        )
        assertEquals(1, result.pages)
        assertTrue(result.errors.any { it.contains("不受支持") })
    }

    @Test
    fun sameHostIgnoresWwwPrefix() {
        assertTrue(ContentPaginator.sameHost("https://www.s.com/a", "https://s.com/b"))
        assertFalse(ContentPaginator.sameHost("https://s.com/a", "https://t.com/b"))
        assertFalse(ContentPaginator.sameHost("not a url", "https://s.com/b"))
    }
}
