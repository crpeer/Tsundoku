package app.tsundoku.reader

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.legado.LegadoRule
import app.tsundoku.reader.data.source.legado.LegadoSource
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.LegadoSourceConfig
import app.tsundoku.reader.domain.model.SourceConfigs
import app.tsundoku.reader.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Legado 规则翻译的离线用例 + 一个可选的联网书源巡检。
 *
 * 巡检默认跳过；设置环境变量后运行：
 * ```
 * $env:TSUNDOKU_SOURCES="E:\code\Android\tmp\sources.json"
 * $env:TSUNDOKU_KEYWORD="龙族"
 * $env:TSUNDOKU_LIMIT="150"
 * .\gradlew.bat :app:testDebugUnitTest --tests "app.tsundoku.reader.LegadoProbeTest"
 * ```
 * 结果写到 `TSUNDOKU_REPORT` 指定的文件。
 */
class LegadoProbeTest {

    @Test
    fun classAndTagChainSelectsText() {
        val html = """
            <div class="library">
              <ul><li><a class="bookname" href="/b/1">龙族</a><span class="author">江南</span></li></ul>
            </div>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com")
        val items = LegadoRule.elements(doc, "class.library@li")
        assertEquals(1, items.size)
        assertEquals("龙族", LegadoRule.value(items[0], "class.bookname@text", "https://example.com"))
        assertEquals("https://example.com/b/1", LegadoRule.value(items[0], "class.bookname@href", "https://example.com"))
        assertEquals("江南", LegadoRule.value(items[0], "class.author@text", "https://example.com"))
    }

    @Test
    fun indexedTagAndFallbackWork() {
        val html = """
            <ul class="list"><li>a</li><li>b</li><li>c</li></ul>
            <div class="meta"><span>first</span><span>second</span></div>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        assertEquals("a", LegadoRule.value(doc, "class.list@li.0@text", null))
        assertEquals("c", LegadoRule.value(doc, "class.list@li.-1@text", null))
        assertEquals("second", LegadoRule.value(doc, "class.meta@span.1@text", null))
        assertEquals("first", LegadoRule.value(doc, "class.missing@text||class.meta@span.0@text", null))
    }

    @Test
    fun postRegexFiltersAndReplaces() {
        val doc = Jsoup.parse("""<h3 class="title">凡人修仙传(全本)</h3>""")
        assertEquals("凡人修仙传", LegadoRule.value(doc, "class.title@text##\\(.*\\)##", null))
        assertEquals("凡人修仙传(（完）)", LegadoRule.value(doc, "class.title@text##全本##（完）", null))
    }

    @Test
    fun probeSources() = runBlocking {
        val path = System.getenv("TSUNDOKU_SOURCES") ?: return@runBlocking
        val keyword = System.getenv("TSUNDOKU_KEYWORD") ?: "龙族"
        val limit = System.getenv("TSUNDOKU_LIMIT")?.toIntOrNull() ?: 150
        val reportPath = System.getenv("TSUNDOKU_REPORT") ?: "probe-report.txt"

        val text = File(path).readText()
        val array = SourceConfigs.json.parseToJsonElement(text) as JsonArray
        val candidates = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = (obj["bookSourceType"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val searchUrl = (obj["searchUrl"] as? JsonPrimitive)?.content.orEmpty()
            val name = (obj["bookSourceName"] as? JsonPrimitive)?.content.orEmpty()
            if (type != 0 || searchUrl.isBlank() || name.isBlank()) return@mapNotNull null
            val config = runCatching {
                SourceConfigs.json.decodeFromString<LegadoSourceConfig>(element.toString())
            }.getOrNull() ?: return@mapNotNull null
            if (config.search.bookList.isBlank()) return@mapNotNull null
            name to config
        }.take(limit)

        val network = NetworkClient()
        val lines = mutableListOf<String>()
        lines += "probing ${candidates.size} novel sources with keyword=$keyword"
        var ok = 0
        candidates.chunked(10).forEach { chunk ->
            val results = chunk.map { (name, config) ->
                async(Dispatchers.IO) {
                    val info = BookSourceInfo(
                        id = 0,
                        name = name,
                        type = SourceType.LEGADO,
                        lang = "zh",
                        enabled = true,
                        config = SourceConfigs.json.encodeToString(config),
                        iconUrl = null,
                        addedAt = 0,
                    )
                    val outcome = withTimeoutOrNull(20_000) {
                        runCatching { LegadoSource(info, config, network).search(keyword, 1) }
                    }
                    Triple(name, config.baseUrl, outcome)
                }
            }.awaitAll()
            results.forEach { (name, base, outcome) ->
                val line = when {
                    outcome == null -> "TIMEOUT  $name  ($base)"
                    outcome.isFailure -> "FAIL     $name  ${outcome.exceptionOrNull()?.message}"
                    else -> {
                        val items = outcome.getOrThrow()
                        if (items.isNotEmpty()) ok++
                        "OK ${items.size}  $name  ${items.firstOrNull()?.title.orEmpty()}  ${items.firstOrNull()?.url.orEmpty()}"
                    }
                }
                lines += line
            }
            File(reportPath).writeText(lines.joinToString("\n"))
        }
        lines += "--- total with results: $ok / ${candidates.size}"
        File(reportPath).writeText(lines.joinToString("\n"))
        assertTrue("probe finished", true)
    }
}
