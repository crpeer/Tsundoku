package app.tsundoku.reader.data.source.web

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.data.source.BrowseCategory
import app.tsundoku.reader.data.source.SourceException
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.ContentRule
import app.tsundoku.reader.domain.model.PageRule
import app.tsundoku.reader.domain.model.SearchItem
import app.tsundoku.reader.domain.model.WebSourceConfig
import java.net.URLEncoder

/** 声明式网页源：按 JSON 规则用 Jsoup 抓取站点。 */
class WebSource(
    override val info: BookSourceInfo,
    private val config: WebSourceConfig,
    private val network: NetworkClient,
) : BookSource {

    override suspend fun search(query: String, page: Int): List<SearchItem> {
        val rule = config.search ?: return emptyList()
        return loadList(rule, query, page, template = rule.url)
    }

    /**
     * 浏览：`url` 非空时表示用户点了某个分类，必须用这个地址（原来被忽略，
     * 导致点任何分类都只请求固定首页）。
     */
    override suspend fun browse(url: String?, page: Int): List<SearchItem> {
        val rule = config.browse
        val template = url?.takeIf { it.isNotBlank() } ?: rule?.url
        if (rule == null || template.isNullOrBlank()) return emptyList()
        return loadList(rule, query = null, page = page, template = template)
    }

    override fun categories(): List<BrowseCategory> {
        val fromConfig = config.categories.map { it.toCategory() }
        if (fromConfig.isNotEmpty()) return fromConfig
        val home = config.browse?.url
        return if (home.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(BrowseCategory("全部", RuleParser.resolveUrl(home.substringBefore("{{"), config.baseUrl)))
        }
    }

    private suspend fun loadList(
        rule: PageRule,
        query: String?,
        page: Int,
        template: String,
    ): List<SearchItem> {
        // 没有 {{page}} 占位符时说明站点不支持翻页，第 2 页起直接返回空，避免重复请求同一页。
        if (page > 1 && !template.contains("{{page}}")) return emptyList()
        val target = buildUrl(template, query, page)
        val html = network.get(target, config.headers)
        val doc = RuleParser.parse(html, target)
        val base = doc.baseUri().ifBlank { target }
        return RuleParser.select(doc, rule.item).mapNotNull { element ->
            val title = RuleParser.extract(element, rule.title, base)
            val link = RuleParser.extract(element, rule.link, base)
            if (title.isNullOrBlank() || link.isNullOrBlank()) return@mapNotNull null
            SearchItem(
                title = title,
                url = link,
                cover = RuleParser.extract(element, rule.cover, base),
                author = RuleParser.extract(element, rule.author, base),
                description = RuleParser.extract(element, rule.description, base),
            )
        }.distinctBy { it.url }
    }

    override suspend fun details(url: String): BookDetails {
        val html = network.get(url, config.headers)
        val doc = RuleParser.parse(html, url)
        val base = doc.baseUri().ifBlank { url }
        return BookDetails(
            title = RuleParser.extract(doc, config.details.title, base).orEmpty(),
            author = RuleParser.extract(doc, config.details.author, base),
            cover = RuleParser.extract(doc, config.details.cover, base),
            description = RuleParser.extract(doc, config.details.description, base),
            url = url,
        )
    }

    override suspend fun chapters(bookUrl: String): List<ChapterItem> {
        if (config.chapters.item.isBlank()) return emptyList()
        val html = network.get(bookUrl, config.headers)
        val doc = RuleParser.parse(html, bookUrl)
        val base = doc.baseUri().ifBlank { bookUrl }
        val pairs = RuleParser.select(doc, config.chapters.item).mapNotNull { element ->
            val title = RuleParser.extract(element, config.chapters.title, base)
            val link = RuleParser.extract(element, config.chapters.link, base)
            if (title.isNullOrBlank() || link.isNullOrBlank()) null else title to link
        }.distinctBy { it.second }
        val ordered = if (config.chapters.reverse) pairs.reversed() else pairs
        return ordered.mapIndexed { index, (title, link) ->
            ChapterItem(title = title, url = link, index = index)
        }
    }

    override suspend fun content(chapterUrl: String): String {
        val rule = config.content
        val result = ContentPaginator.collect(
            firstUrl = chapterUrl,
            maxPages = rule.maxPages.coerceIn(1, 50),
            fetch = { url -> network.get(url, config.headers) },
            parse = { html, url -> parseContentPage(html, url, rule) },
        )
        if (result.html.isBlank()) {
            throw SourceException(result.errors.firstOrNull() ?: "正文为空")
        }
        return result.html
    }

    /** 解析单页正文，并取出「下一页」地址。 */
    private fun parseContentPage(
        html: String,
        pageUrl: String,
        rule: ContentRule,
    ): ContentPaginator.ParsedPage {
        val doc = RuleParser.parse(html, pageUrl)
        val container = RuleParser.selectFirst(doc, rule.selector) ?: doc.body()
        rule.remove.forEach { selector ->
            runCatching { container.select(selector).remove() }
        }
        container.select("script,style,iframe,noscript").remove()
        container.select("img").forEach { image ->
            val raw = image.attr("data-src").ifBlank { image.attr("src") }
            if (raw.isNotBlank()) image.attr("src", RuleParser.resolveUrl(raw, pageUrl))
        }
        container.select("a").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) anchor.attr("href", RuleParser.resolveUrl(href, pageUrl))
        }
        val next = if (rule.nextPage.isBlank()) {
            null
        } else {
            RuleParser.extract(doc, rule.nextPage, pageUrl)?.let { RuleParser.resolveUrl(it, pageUrl) }
        }
        return ContentPaginator.ParsedPage(container.html(), next)
    }

    private fun buildUrl(template: String, query: String?, page: Int): String {
        val encoded = URLEncoder.encode(query.orEmpty(), "UTF-8")
        // 分类地址常带 `?page=` 之类的查询串，占位符可能写作 {{page}}；没有占位符就原样返回。
        val path = template
            .replace("{{query}}", encoded)
            .replace("{{page}}", page.toString())
        return RuleParser.resolveUrl(path, config.baseUrl)
    }
}
