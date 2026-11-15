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
 *
 * 取值语义（重要）：
 * - 文本类（`text`/`ownText`/`html`/`outerHtml`）**绝不**做 URL 补全，
 *   否则 `anonymous` 这类纯 ASCII 文本会被拼成 `https://site/book/anonymous`。
 * - 链接类（`href`/`src`/`poster`/`data-src`/`action`）一定补全成绝对地址。
 * - `content` 属性因元素而异（`<meta content="文字">` 与 `<meta content="/a.jpg">`），
 *   只有看起来像路径时才补全。
 */
object RuleParser {

    /** 一定按 URL 处理的属性。 */
    private val urlAttributes = setOf("href", "src", "poster", "data-src", "action", "data-original")

    /** 一定按纯文本处理的属性。 */
    private val textAttributes = setOf("text", "owntext", "html", "innerhtml", "outerhtml", "all")

    fun parse(html: String, baseUrl: String?): Document =
        Jsoup.parse(html, baseUrl.orEmpty())

    fun parseXml(xml: String, baseUrl: String?): Document =
        Jsoup.parse(xml, baseUrl.orEmpty(), Parser.xmlParser())

    /** 取第一个命中的元素；支持 `||` 候选。 */
    fun selectFirst(root: Element, selector: String): Element? {
        if (selector.isBlank()) return root
        for (part in selector.split("||")) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val hit = runCatching { root.selectFirst(trimmed) }.getOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /** 取全部命中元素；同样支持 `||`（命中第一个非空候选就返回）。 */
    fun select(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return listOf(root)
        for (part in selector.split("||")) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val hits = runCatching { root.select(trimmed) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    fun extract(root: Element, spec: String, baseUrl: String?): String? {
        if (spec.isBlank()) return null
        val at = spec.lastIndexOf('@')
        val chain: String
        val attr: String
        if (at < 0) {
            chain = spec
            attr = "text"
        } else {
            chain = spec.substring(0, at)
            attr = spec.substring(at + 1).ifBlank { "text" }
        }
        val element = walk(root, chain) ?: return null
        val raw = rawValue(element, attr) ?: return null
        return if (shouldResolveUrl(attr, raw)) resolveUrl(raw, baseUrl) else raw
    }

    /** 支持 `div.item@a@href` 这类链式下钻，每段都是 CSS 选择器。 */
    private fun walk(root: Element, chain: String): Element? {
        if (chain.isBlank()) return root
        var current: Element = root
        for (part in chain.split("@")) {
            val selector = part.trim()
            if (selector.isEmpty()) continue
            current = selectFirst(current, selector) ?: return null
        }
        return current
    }

    private fun rawValue(element: Element, attr: String): String? {
        val name = attr.lowercase()
        val value = when (name) {
            "text" -> element.text()
            "owntext" -> element.ownText()
            "html", "innerhtml" -> element.html()
            "outerhtml", "all" -> element.outerHtml()
            // href/src 走 absUrl 以兼容文档里的 <base>；其余属性取原值，避免被 Jsoup 提前补成 URL。
            else -> if (name in urlAttributes) {
                element.absUrl(attr).ifBlank { element.attr(attr) }
            } else {
                element.attr(attr)
            }
        }.trim()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun shouldResolveUrl(attr: String, value: String): Boolean {
        val name = attr.lowercase()
        if (name in textAttributes) return false
        if (name in urlAttributes) return !value.startsWith("javascript:", ignoreCase = true)
        if (name == "content") return looksLikePath(value)
        return false
    }

    private fun looksLikePath(value: String): Boolean =
        value.startsWith("http://") ||
            value.startsWith("https://") ||
            value.startsWith("//") ||
            value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../")

    fun resolveUrl(raw: String, baseUrl: String?): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("file:")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return raw
        // `https://host`（无路径）直接 resolve 相对地址会拼成 `https://hostfoo`，补个斜杠。
        val normalized = if (base.substringAfter("://").contains('/')) base else "$base/"
        // `{`/`}` 是非法 URI 字符，残留的 `{{...}}` 会让 URI 解析抛异常。
        val safe = raw.replace("{", "%7B").replace("}", "%7D")
        val resolved = runCatching { URI(normalized).resolve(safe).toString() }.getOrDefault(raw)
        // 只允许 http/https 结果，避免 javascript:/file: 等被当成链接。
        return if (resolved.startsWith("http://") || resolved.startsWith("https://") || resolved == raw) {
            resolved
        } else {
            raw
        }
    }
}
