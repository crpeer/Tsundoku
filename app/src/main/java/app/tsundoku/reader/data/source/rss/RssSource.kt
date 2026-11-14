package app.tsundoku.reader.data.source.rss

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.data.source.web.RuleParser
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.RssConfig
import app.tsundoku.reader.domain.model.SearchItem
import org.jsoup.nodes.Element

/**
 * RSS / Atom 订阅源：整个订阅被当作一本连载书，每篇文章是一章。
 */
class RssSource(
    override val info: BookSourceInfo,
    private val config: RssConfig,
    private val network: NetworkClient,
) : BookSource {

    private data class Feed(
        val title: String,
        val author: String?,
        val description: String?,
        val cover: String?,
        val entries: List<ChapterItem>,
    )

    override suspend fun search(query: String, page: Int): List<SearchItem> {
        val feed = loadFeed()
        val matched = feed.title.contains(query, ignoreCase = true) ||
            feed.entries.any { it.title.contains(query, ignoreCase = true) }
        if (!matched) return emptyList()
        return listOf(
            SearchItem(
                title = feed.title,
                url = config.feedUrl,
                cover = feed.cover,
                author = feed.author,
                description = feed.description,
            ),
        )
    }

    override suspend fun browse(url: String?, page: Int): List<SearchItem> = search("", page)

    override suspend fun details(url: String): BookDetails {
        val feed = loadFeed()
        return BookDetails(
            title = feed.title,
            author = feed.author,
            cover = feed.cover,
            description = feed.description,
            url = config.feedUrl,
        )
    }

    override suspend fun chapters(bookUrl: String): List<ChapterItem> = loadFeed().entries

    private suspend fun loadFeed(): Feed {
        val xml = network.get(config.feedUrl)
        val doc = RuleParser.parseXml(xml, config.feedUrl)
        val title = config.title.ifBlank {
            doc.selectFirst("channel > title")?.text()
                ?: doc.selectFirst("feed > title")?.text()
                ?: "未命名订阅"
        }
        val author = doc.selectFirst("channel > managingEditor")?.text()?.trim()
            ?: doc.selectFirst("feed > author > name")?.text()?.trim()
        val description = doc.selectFirst("channel > description")?.text()?.trim()
            ?: doc.selectFirst("feed > subtitle")?.text()?.trim()

        val cover = doc.selectFirst("channel > image > url")?.text()
            ?: doc.selectFirst("feed > logo")?.text()
            ?: doc.selectFirst("feed > icon")?.text()

        val items: List<Element> = doc.select("item").ifEmpty { doc.select("entry") }
        val entries = items.mapNotNull { element ->
            val entryTitle = element.selectFirst("title")?.text()?.trim()
            val linkElement = element.selectFirst("link")
            val link = when {
                linkElement == null -> null
                element.tagName() == "entry" ->
                    linkElement.attr("href").ifBlank { linkElement.text() }
                else -> linkElement.text().ifBlank { linkElement.attr("href") }
            }
            if (entryTitle.isNullOrBlank() || link.isNullOrBlank()) null
            else entryTitle to RuleParser.resolveUrl(link, config.feedUrl)
        }
        // 订阅一般最新在前，阅读顺序需要反过来
        val ordered = entries.reversed()
        return Feed(
            title = title.trim(),
            author = author?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            cover = cover?.trim()?.takeIf { it.isNotBlank() }
                ?.let { RuleParser.resolveUrl(it, config.feedUrl) },
            entries = ordered.mapIndexed { index, (entryTitle, link) ->
                ChapterItem(title = entryTitle, url = link, index = index)
            },
        )
    }

    override suspend fun content(chapterUrl: String): String {
        val html = network.get(chapterUrl)
        val doc = RuleParser.parse(html, chapterUrl)
        doc.select("script,style,nav,footer,aside,form,iframe,noscript,header").remove()
        val container = RuleParser.selectFirst(
            doc,
            "article||main||[class*=article-content]||[class*=post-content]||[class*=entry-content]||.content||#content",
        ) ?: doc.body()
        container.select("img").forEach { image ->
            val raw = image.attr("data-src").ifBlank { image.attr("src") }
            if (raw.isNotBlank()) image.attr("src", RuleParser.resolveUrl(raw, chapterUrl))
        }
        return container.html()
    }
}
