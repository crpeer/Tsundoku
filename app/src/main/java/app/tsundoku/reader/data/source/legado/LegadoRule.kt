package app.tsundoku.reader.data.source.legado

import app.tsundoku.reader.data.source.web.RuleParser
import org.jsoup.nodes.Element

/**
 * Legado（阅读 3.0）书源规则的最小可用子集。
 *
 * 支持：
 *  - 选择器：`class.xxx` / `id.xxx` / `tag.xxx` / 原生 CSS
 *  - 链式下钻：`class.list@tag.a@href`，带索引的 `tr.0` / `a.1` / `td.-1`
 *  - 取值：`text` / `ownText` / `html` / `all` / `href` / `src` / 任意属性名
 *  - 兜底：`||`（取第一个非空）
 *  - 后处理：`##正则`（筛选）与 `##正则##替换`
 *
 * 不支持（遇到时返回 null，由上层提示用户换源）：`@js:`、`@get:` 变量、`&&` 合并、`%%`。
 */
object LegadoRule {

    private val getters = setOf(
        "text", "owntext", "html", "all", "outerhtml", "href", "src", "content",
        "poster", "data-src", "title", "alt", "value",
    )

    private val indexedTag = Regex("^([A-Za-z][A-Za-z0-9]*)\\.(-?\\d+)$")

    /** 列表规则（bookList / chapterList），返回命中的元素。 */
    fun elements(root: Element, rule: String): List<Element> {
        val first = primary(rule)
        if (first.isEmpty()) return emptyList()
        val parts = first.split("@")
        var nodes = selectToken(root, parts.first())
        for (index in 1 until parts.size) {
            val token = parts[index].trim()
            if (token.isEmpty()) continue
            if (isGetter(token)) return nodes
            nodes = nodes.flatMap { selectToken(it, token) }
        }
        return nodes
    }

    /** 元素规则（详情/目录/正文），返回最后一个命中元素。 */
    fun node(root: Element, rule: String): Element? {
        val first = primary(rule)
        if (first.isEmpty()) return null
        val parts = first.split("@")
        var nodes = selectToken(root, parts.first())
        for (index in 1 until parts.size) {
            val token = parts[index].trim()
            if (token.isEmpty() || isGetter(token)) continue
            nodes = nodes.flatMap { selectToken(it, token) }
        }
        return nodes.firstOrNull()
    }

    /** 取值规则，返回字符串结果（先做 `##` 后处理，再补全 URL）。 */
    fun value(root: Element, rule: String, baseUrl: String?): String? {
        if (rule.isBlank()) return null
        for (candidate in rule.split("||")) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("@js:")) continue
            val body = trimmed.substringBefore("##").trim()
            val post = trimmed.substringAfter("##", "")
            val extracted = rawValue(root, body) ?: continue
            val withPost = if (post.isEmpty()) extracted.first else applyPost(extracted.first, post)
            val out = withPost.trim()
            if (out.isEmpty()) continue
            return if (extracted.second) RuleParser.resolveUrl(out, baseUrl) else out
        }
        return null
    }

    private fun primary(rule: String): String =
        rule.split("||")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("@js:") }
            ?.substringBefore("##")
            ?.trim()
            .orEmpty()

    /** 返回 (原值, 是否 URL 属性)。 */
    private fun rawValue(root: Element, spec: String): Pair<String, Boolean>? {
        if (spec.isEmpty()) return null
        val parts = spec.split("@")
        var nodes = selectToken(root, parts.first())
        if (nodes.isEmpty()) return null
        for (index in 1 until parts.size) {
            val token = parts[index].trim()
            if (token.isEmpty()) continue
            if (isGetter(token)) {
                val element = nodes.firstOrNull() ?: return null
                return read(element, token)
            }
            nodes = nodes.flatMap { selectToken(it, token) }
            if (nodes.isEmpty()) return null
        }
        return nodes.firstOrNull()?.text()?.let { it to false }
    }

    private fun selectToken(element: Element, token: String): List<Element> {
        val text = token.trim()
        if (text.isEmpty()) return listOf(element)
        if (text == "children") return element.children()
        if (text.startsWith("class.")) {
            return runCatching { element.select("." + text.removePrefix("class.")) }
                .getOrDefault(emptyList())
        }
        if (text.startsWith("id.")) {
            return runCatching { element.select("#" + text.removePrefix("id.")) }
                .getOrDefault(emptyList())
        }
        if (text.startsWith("tag.")) {
            return runCatching { element.select(text.removePrefix("tag.")) }
                .getOrDefault(emptyList())
        }
        indexedTag.find(text)?.let { match ->
            val list = runCatching { element.select(match.groupValues[1]) }
                .getOrDefault(emptyList())
            val raw = match.groupValues[2].toInt()
            val index = if (raw < 0) list.size + raw else raw
            return listOfNotNull(list.getOrNull(index))
        }
        return runCatching { element.select(text) }.getOrDefault(emptyList())
    }

    private fun isGetter(token: String): Boolean = token.lowercase() in getters

    private val urlAttributes = setOf("href", "src", "content", "poster", "data-src", "action")

    private fun read(element: Element, getter: String): Pair<String, Boolean> {
        val name = getter.lowercase()
        val isUrl = name in urlAttributes
        val value = when (name) {
            "text" -> element.text()
            "owntext" -> element.ownText()
            "html" -> element.html()
            "all", "outerhtml" -> element.outerHtml()
            else -> if (isUrl) {
                element.absUrl(getter).ifBlank { element.attr(getter) }
            } else {
                element.attr(getter)
            }
        }.trim()
        return value to isUrl
    }

    private fun applyPost(raw: String, post: String): String {
        val pieces = post.split("##")
        val pattern = pieces.getOrNull(0).orEmpty()
        if (pattern.isEmpty()) return raw
        return runCatching {
            val regex = Regex(pattern)
            if (pieces.size >= 2) regex.replace(raw, pieces[1]) else regex.findAll(raw).joinToString("") { it.value }
        }.getOrDefault(raw)
    }
}
