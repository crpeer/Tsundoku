package app.tsundoku.reader.data.source.opds

import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.BookSource
import app.tsundoku.reader.data.source.web.RuleParser
import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.OpdsConfig
import app.tsundoku.reader.domain.model.SearchItem
import java.net.URLEncoder

/** OPDS 1.x 目录源。目录项 isFolder=true，点击可下钻。 */
class OpdsSource(
    override val info: BookSourceInfo,
    private val config: OpdsConfig,
    private val network: NetworkClient,
) : BookSource {

    override suspend fun search(query: String, page: Int): List<SearchItem> {
        if (config.searchUrl.isBlank()) {
            return fetch(config.url).filter { it.title.contains(query, ignoreCase = true) }
        }
        val url = RuleParser.resolveUrl(
            config.searchUrl.replace("{{query}}", URLEncoder.encode(query, "UTF-8")),
            config.url,
        )
        return fetch(url)
    }

    override suspend fun browse(url: String?, page: Int): List<SearchItem> = fetch(url ?: config.url)

    private suspend fun fetch(url: String): List<SearchItem> {
        val xml = network.get(url)
        val doc = RuleParser.parseXml(xml, url)
        return doc.select("entry").mapNotNull { entry ->
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val links = entry.select("link")
            val nav = links.firstOrNull { link ->
                val rel = link.attr("rel")
                val type = link.attr("type")
                rel.contains("subsection") || type.startsWith("application/atom+xml")
            }
            val acquisition = links.firstOrNull { it.attr("rel").contains("acquisition") }
            val image = links.firstOrNull {
                it.attr("rel").contains("image") || it.attr("type").startsWith("image/")
            }
            val navHref = nav?.let { it.absUrl("href").ifBlank { it.attr("href") } }
            val acqHref = acquisition?.let { it.absUrl("href").ifBlank { it.attr("href") } }
            val target = navHref ?: acqHref ?: return@mapNotNull null
            SearchItem(
                title = title,
                url = RuleParser.resolveUrl(target, url),
                cover = image?.let {
                    RuleParser.resolveUrl(it.absUrl("href").ifBlank { it.attr("href") }, url)
                },
                author = entry.selectFirst("author > name")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() },
                description = entry.selectFirst("summary")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() },
                isFolder = nav != null,
            )
        }
    }

    override suspend fun details(url: String): BookDetails = BookDetails(
        title = "",
        author = null,
        cover = null,
        description = null,
        url = url,
    )

    override suspend fun chapters(bookUrl: String): List<ChapterItem> = emptyList()

    override suspend fun content(chapterUrl: String): String =
        throw UnsupportedOperationException("OPDS 书籍需要先下载为本地文件")
}
