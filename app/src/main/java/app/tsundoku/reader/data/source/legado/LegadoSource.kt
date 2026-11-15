package app.tsundoku.reader.data.source.legado

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.data.source.BrowseCategory
import app.tsundoku.reader.data.source.SourceException
import app.tsundoku.reader.data.source.web.ContentPaginator
import app.tsundoku.reader.data.source.web.RuleParser
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.LegadoContentRule
import app.tsundoku.reader.domain.model.LegadoSourceConfig
import app.tsundoku.reader.domain.model.SearchItem
import app.tsundoku.reader.domain.model.SourceConfigs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Legado（阅读）书源适配器：把 Legado 的规则 DSL 翻译成 Jsoup 查询。
 *
 * 只覆盖纯 HTML 站点（无登录、无 JS 注入）。`@js:` 类规则会让该字段为空，
 * 这时搜索/目录结果为空，UI 会提示换一个书源。
 */
class LegadoSource(
    override val info: BookSourceInfo,
    private val config: LegadoSourceConfig,
    private val network: NetworkClient,
) : BookSource {

    private val headers: Map<String, String> by lazy { parseHeader(config.header) }

    /** Legado 的 bookSourceUrl 允许带 `#标签` / `##标签` 后缀，这里只取真正的站点地址。 */
    private val origin: String = config.baseUrl.substringBefore("##").substringBefore('#').trim().trimEnd('/')

    override suspend fun search(query: String, page: Int): List<SearchItem> {
        val rule = config.search
        if (config.searchUrl.isBlank() || rule.bookList.isBlank()) {
            throw SourceException("该书源未提供搜索规则")
        }
        val url = buildSearchUrl(query, page)
        val html = network.get(url, headers)
        val doc = RuleParser.parse(html, url)
        val base = doc.baseUri().ifBlank { url }
        val items = LegadoRule.elements(doc, rule.bookList).mapNotNull { element ->
            val title = textOf(element, rule.name, base)
            val link = LegadoRule.value(element, rule.bookUrl, base)?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            SearchItem(
                title = title,
                url = RuleParser.resolveUrl(fill(link), base),
                cover = LegadoRule.value(element, rule.coverUrl, base),
                author = LegadoRule.value(element, rule.author, base),
                description = LegadoRule.value(element, rule.intro, base),
            )
        }.distinctBy { it.url }
        if (items.isEmpty()) throw SourceException("没有解析到搜索结果（规则可能不支持）")
        return items
    }

    override suspend fun browse(url: String?, page: Int): List<SearchItem> {
        val target = url?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (page > 1 && !target.contains("{{page}}") && !target.contains("{{page-1}}")) {
            return emptyList()
        }
        val fetchUrl = RuleParser.resolveUrl(fillPage(target, page), "$origin/")
        val html = network.get(fetchUrl, headers)
        val doc = RuleParser.parse(html, fetchUrl)
        val base = doc.baseUri().ifBlank { fetchUrl }

        // 优先用 explore 规则；没有就用 search 规则凑合（很多源两者结构一致）。
        val explore = config.explore
        val listRule = explore.bookList.ifBlank { config.search.bookList }
        if (listRule.isBlank()) throw SourceException("该书源未提供分类列表规则")
        val items = LegadoRule.elements(doc, listRule).mapNotNull { element ->
            val title = textOfOr(element, explore.name.ifBlank { config.search.name }, base)
            val link = LegadoRule.value(element, explore.bookUrl.ifBlank { config.search.bookUrl }, base)
                ?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            SearchItem(
                title = title,
                url = RuleParser.resolveUrl(fill(link), base),
                cover = LegadoRule.value(
                    element,
                    explore.coverUrl.ifBlank { config.search.coverUrl },
                    base,
                ),
                author = LegadoRule.value(
                    element,
                    explore.author.ifBlank { config.search.author },
                    base,
                ),
                description = LegadoRule.value(
                    element,
                    explore.intro.ifBlank { config.search.intro },
                    base,
                ),
            )
        }.distinctBy { it.url }
        if (items.isEmpty()) throw SourceException("没有解析到分类结果（规则可能不支持）")
        return items
    }

    override fun categories(): List<BrowseCategory> = LegadoExplore.parse(config.exploreUrl)

    private fun textOfOr(element: Element, rule: String, base: String): String =
        if (rule.isBlank()) element.text().trim() else LegadoRule.value(element, rule, base).orEmpty().trim()

    private fun fillPage(raw: String, page: Int): String = pagePattern.replace(raw) { match ->
        val offset = match.groupValues[1].replace(" ", "").toIntOrNull() ?: 0
        (page + offset).toString()
    }

    override suspend fun details(url: String): BookDetails {
        val html = network.get(url, headers)
        val doc = RuleParser.parse(html, url)
        val base = doc.baseUri().ifBlank { url }
        val rule = config.bookInfo
        return BookDetails(
            title = textOf(doc, rule.name, base).ifBlank { doc.title() },
            author = LegadoRule.value(doc, rule.author, base),
            cover = LegadoRule.value(doc, rule.coverUrl, base),
            description = LegadoRule.value(doc, rule.intro, base),
            url = url,
        )
    }

    override suspend fun chapters(bookUrl: String): List<ChapterItem> {
        val rule = config.toc
        if (rule.chapterList.isBlank()) throw SourceException("该书源未提供目录规则")

        var pageUrl = bookUrl
        var doc = RuleParser.parse(network.get(pageUrl, headers), pageUrl)
        val tocUrl = LegadoRule.value(doc, config.bookInfo.tocUrl, pageUrl)?.trim()
        if (!tocUrl.isNullOrBlank()) {
            val target = RuleParser.resolveUrl(fill(tocUrl), pageUrl)
            if (target != pageUrl) {
                pageUrl = target
                doc = RuleParser.parse(network.get(pageUrl, headers), pageUrl)
            }
        }
        val base = doc.baseUri().ifBlank { pageUrl }
        val items = LegadoRule.elements(doc, rule.chapterList).mapNotNull { element ->
            val title = textOf(element, rule.chapterName, base)
            val link = LegadoRule.value(element, rule.chapterUrl, base)?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) null else title to RuleParser.resolveUrl(fill(link), base)
        }.distinctBy { it.second }
        if (items.isEmpty()) throw SourceException("没有解析到目录（规则可能不支持）")
        return items.mapIndexed { index, (title, link) ->
            ChapterItem(title = title, url = link, index = index)
        }
    }

    override suspend fun content(chapterUrl: String): String {
        val rule = config.content
        val result = ContentPaginator.collect(
            firstUrl = chapterUrl,
            maxPages = ContentPaginator.DEFAULT_MAX_PAGES,
            fetch = { url -> network.get(url, headers) },
            parse = { html, url -> parseContentPage(html, url, rule) },
        )
        if (result.html.isBlank()) {
            throw SourceException(result.errors.firstOrNull() ?: "正文为空")
        }
        return result.html
    }

    private fun parseContentPage(
        html: String,
        pageUrl: String,
        rule: LegadoContentRule,
    ): ContentPaginator.ParsedPage {
        val doc = RuleParser.parse(html, pageUrl)
        val container = LegadoRule.node(doc, rule.content) ?: doc.body()
        container.select("script,style,iframe,noscript").remove()
        container.select("img").forEach { image ->
            val raw = image.attr("data-src").ifBlank { image.attr("src") }
            if (raw.isNotBlank()) image.attr("src", RuleParser.resolveUrl(raw, pageUrl))
        }
        container.select("a").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) anchor.attr("href", RuleParser.resolveUrl(href, pageUrl))
        }
        val next = if (rule.nextContentUrl.isBlank()) {
            null
        } else {
            LegadoRule.value(doc, rule.nextContentUrl, pageUrl)
                ?.let { RuleParser.resolveUrl(it, pageUrl) }
        }
        return ContentPaginator.ParsedPage(container.html(), next)
    }

    private fun textOf(element: Element, rule: String, base: String): String =
        if (rule.isBlank()) element.text().trim() else LegadoRule.value(element, rule, base).orEmpty().trim()

    private fun buildSearchUrl(query: String, page: Int): String {
        // Legado 允许在 searchUrl 后面跟 `,{...}` 选项（method/body/charset），本阶段忽略选项。
        val template = config.searchUrl.trim().substringBefore(",{")
        val filled = fillPlaceholders(template, query, page)
        return RuleParser.resolveUrl(filled, "$origin/")
    }

    /** 支持 `{{key}}`、`{{page}}`、`{{page-1}}`、`{{page+1}}`、`{{baseUrl}}`。 */
    private fun fillPlaceholders(raw: String, query: String, page: Int): String {
        var out = raw.replace("{{key}}", URLEncoder.encode(query, "UTF-8"))
        out = pagePattern.replace(out) { match ->
            val offset = match.groupValues[1].replace(" ", "").toIntOrNull() ?: 0
            (page + offset).toString()
        }
        return out.replace("{{baseUrl}}", origin)
    }

    private val pagePattern = Regex("""\{\{\s*page\s*([+-]\s*\d+)?\s*\}\}""")

    private fun fill(raw: String): String = raw.replace("{{baseUrl}}", origin)

    private fun parseHeader(raw: String): Map<String, String> {
        val custom = if (raw.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                SourceConfigs.json.parseToJsonElement(raw).jsonObject.mapValues { entry ->
                    (entry.value as? JsonPrimitive)?.content.orEmpty()
                }.filterValues { it.isNotBlank() }
            }.getOrDefault(emptyMap())
        }
        // 很多站点按 Referer / Accept-Language 拦截裸请求。
        return buildMap {
            put("Referer", "$origin/")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            putAll(custom)
        }
    }
}
