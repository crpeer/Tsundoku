package app.tsundoku.reader.data.source.web

import java.net.URI

/**
 * 章节内的「正文分页」抓取。
 *
 * 章节（Chapter）≠ 正文分页（Content Page）：一个章节在站点上可能被拆成
 * `第1页 → 第2页 → 第3页`，必须全部合并后再交给阅读器，翻到末尾才进下一章。
 *
 * 防护：
 * - 只跟随 http/https，且默认要求与起始页同主机（不跨域）
 * - URL 去重（防 A→B→A 死循环）
 * - 最大页数上限
 * - 单页失败不影响已抓到的内容（记一条错误后停止）
 */
object ContentPaginator {

    const val DEFAULT_MAX_PAGES = 8

    data class ParsedPage(val html: String, val nextUrl: String?)

    data class Result(
        val html: String,
        val pages: Int,
        val lastUrl: String,
        val errors: List<String> = emptyList(),
        val truncated: Boolean = false,
    )

    /**
     * @param firstUrl 章节首页地址
     * @param fetch 真正的网络请求（便于单测替换）
     * @param parse 把页面 HTML 解析成「正文 HTML + 下一页地址」
     */
    suspend fun collect(
        firstUrl: String,
        maxPages: Int = DEFAULT_MAX_PAGES,
        fetch: suspend (url: String) -> String,
        parse: (html: String, url: String) -> ParsedPage,
        sameHostOnly: Boolean = true,
    ): Result {
        val html = StringBuilder()
        val visited = LinkedHashSet<String>()
        val errors = mutableListOf<String>()
        var current = firstUrl
        var pages = 0
        var truncated = false

        while (true) {
            if (pages >= maxPages) {
                truncated = true
                break
            }
            if (!visited.add(current)) break // 循环
            if (!isSupportedScheme(current)) {
                errors += "下一页地址不受支持：$current"
                break
            }
            if (sameHostOnly && pages > 0 && !sameHost(firstUrl, current)) {
                errors += "下一页不在同一站点：$current"
                break
            }

            val pageHtml = try {
                fetch(current)
            } catch (error: Throwable) {
                errors += "第 ${pages + 1} 页加载失败：${error.message ?: error.javaClass.simpleName}"
                break
            }
            if (pageHtml.isBlank()) {
                errors += "第 ${pages + 1} 页内容为空"
                break
            }

            val parsed = runCatching { parse(pageHtml, current) }.getOrElse { error ->
                errors += "第 ${pages + 1} 页解析失败：${error.message}"
                ParsedPage("", null)
            }
            if (parsed.html.isNotBlank()) html.append(parsed.html)
            pages++

            val next = parsed.nextUrl?.trim().orEmpty()
            if (next.isEmpty()) break
            if (next == current) break
            current = next
        }

        return Result(
            html = html.toString(),
            pages = pages,
            lastUrl = current,
            errors = errors,
            truncated = truncated,
        )
    }

    fun sameHost(a: String, b: String): Boolean = runCatching {
        val hostA = URI(a).host?.removePrefix("www.")?.lowercase()
        val hostB = URI(b).host?.removePrefix("www.")?.lowercase()
        hostA != null && hostA == hostB
    }.getOrDefault(false)

    private fun isSupportedScheme(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")
}
