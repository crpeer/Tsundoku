package app.tsundoku.reader

import app.tsundoku.reader.data.source.legado.LegadoExplore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** exploreUrl 解析（分类入口）。 */
class LegadoExploreTest {

    @Test
    fun parsesGroupedTextFormat() {
        val raw = """
            玄幻
            全部::https://s.com/list/all/{{page}}.html
            排行::https://s.com/rank/{{page}}.html
            都市
            最新::https://s.com/city/new/{{page}}.html
        """.trimIndent()
        val categories = LegadoExplore.parse(raw)
        assertEquals(3, categories.size)
        assertEquals("玄幻 · 全部", categories[0].name)
        assertEquals("https://s.com/list/all/{{page}}.html", categories[0].url)
        assertEquals("都市 · 最新", categories[2].name)
    }

    @Test
    fun parsesJsonFormat() {
        val raw = """[{"title":"玄幻","url":"https://s.com/x/{{page}}.html"},{"title":"跳过","url":"@js:dynamic()"}]"""
        val categories = LegadoExplore.parse(raw)
        assertEquals(1, categories.size)
        assertEquals("玄幻", categories[0].name)
    }

    @Test
    fun skipsJsDrivenEntries() {
        val raw = """
            分组
            js源::@js:java.ajax('https://s.com/a')
            <js>源::https://s.com/b
            正常::https://s.com/c
        """.trimIndent()
        val categories = LegadoExplore.parse(raw)
        assertEquals(1, categories.size)
        assertEquals("https://s.com/c", categories.single().url)
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertTrue(LegadoExplore.parse("").isEmpty())
        assertTrue(LegadoExplore.parse("   ").isEmpty())
        assertFalse(LegadoExplore.parse("只有分组名").isNotEmpty())
    }
}
