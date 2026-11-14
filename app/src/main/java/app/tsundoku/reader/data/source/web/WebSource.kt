package app.tsundoku.reader.data.source.web

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
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
        return loadList(rule, query, page)
    }

    override suspend fun browse(url: String?, page: Int): List<SearchItem> {
        val rule = config.browse ?: return emptyList()
        return loadList(rule, null, page)
    }

    private suspend fun loadList(rule: PageRule, query: String?, page: Int): List<SearchItem> {
        val target = buildUrl(rule.url, query, page)
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
        val html = network.get(chapterUrl, config.headers)
        val doc = RuleParser.parse(html, chapterUrl)
        val container = RuleParser.selectFirst(doc, config.content.selector) ?: doc.body()
        config.content.remove.forEach { selector ->
            runCatching { container.select(selector).remove() }
        }
        container.select("script,style,iframe,noscript").remove()
        container.select("img").forEach { image ->
            val raw = image.attr("data-src").ifBlank { image.attr("src") }
            if (raw.isNotBlank()) image.attr("src", RuleParser.resolveUrl(raw, chapterUrl))
        }
        container.select("a").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) anchor.attr("href", RuleParser.resolveUrl(href, chapterUrl))
        }
        return container.html()
    }

    private fun buildUrl(template: String, query: String?, page: Int): String {
        val encoded = URLEncoder.encode(query.orEmpty(), "UTF-8")
        val path = template
            .replace("{{query}}", encoded)
            .replace("{{page}}", page.toString())
        return RuleParser.resolveUrl(path, config.baseUrl)
    }
}
