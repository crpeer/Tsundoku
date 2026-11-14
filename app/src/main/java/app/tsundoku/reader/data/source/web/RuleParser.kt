package app.tsundoku.reader.data.source.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * 解析 `css选择器@属性` 形式的取值规则。
 *
 * - `h3 a@href`、`h3 a@text`、`@text`、`img@data-src`
 * - 多个候选选择器用 `||` 分隔，取第一个命中的
 */
object RuleParser {

    fun parse(html: String, baseUrl: String?): Document =
        Jsoup.parse(html, baseUrl.orEmpty())

    fun parseXml(xml: String, baseUrl: String?): Document =
        Jsoup.parse(xml, baseUrl.orEmpty(), Parser.xmlParser())

    fun selectFirst(root: Element, selector: String): Element? {
        if (selector.isBlank()) return root
        return selector.split("||").asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { part -> runCatching { root.selectFirst(part) }.getOrNull() }
            .firstOrNull()
    }

    fun select(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return listOf(root)
        return runCatching { root.select(selector) }.getOrDefault(emptyList())
    }

    fun extract(root: Element, spec: String, baseUrl: String?): String? {
        if (spec.isBlank()) return null
        val at = spec.lastIndexOf('@')
        val selector: String
        val attr: String
        if (at < 0) {
            selector = spec
            attr = "text"
        } else {
            selector = spec.substring(0, at)
            attr = spec.substring(at + 1)
        }
        val element = selectFirst(root, selector) ?: return null
        val raw = when (attr.lowercase()) {
            "", "text" -> element.text()
            "owntext" -> element.ownText()
            "html", "innerhtml" -> element.html()
            "outerhtml" -> element.outerHtml()
            "href", "src", "poster", "content", "data-src" ->
                element.absUrl(attr).ifBlank { element.attr(attr) }
            else -> element.attr(attr)
        }.trim()
        if (raw.isEmpty()) return null
        return resolveUrl(raw, baseUrl)
    }

    fun resolveUrl(raw: String, baseUrl: String?): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("file:")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return raw
        // `https://host`（无路径）直接 resolve 相对地址会拼成 `https://hostfoo`，补个斜杠。
        val normalized = if (base.substringAfter("://").contains('/')) base else "$base/"
        // `{`/`}` 是非法 URI 字符，残留的 `{{...}}` 会让 URI 解析抛异常。
        val safe = raw.replace("{", "%7B").replace("}", "%7D")
        return runCatching { URI(normalized).resolve(safe).toString() }.getOrDefault(raw)
    }
}
