package app.tsundoku.reader.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 网页源规则（声明式）。用户可粘贴 JSON 添加来源，无需写代码。
 *
 * 规则字符串格式为 `css选择器@取值`：
 *  - `h3 a@href` 取链接（自动补全为绝对地址）
 *  - `h3 a@text` 取文本
 *  - `@text` 表示元素自身文本
 *  - 多个候选选择器用 `||` 分隔
 */
@Serializable
data class WebSourceConfig(
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val lang: String = "zh",
    val headers: Map<String, String> = emptyMap(),
    val search: PageRule? = null,
    val browse: PageRule? = null,
    val details: DetailRule = DetailRule(),
    val chapters: ChapterRule = ChapterRule(),
    val content: ContentRule = ContentRule(),
)

@Serializable
data class PageRule(
    /** 支持 {{query}} 与 {{page}} 占位符，相对路径会拼接到 base_url。 */
    val url: String,
    /** 每条结果的选择器。 */
    val item: String,
    val title: String = "@text",
    val link: String = "a@href",
    val cover: String = "img@src",
    val author: String = "",
    val description: String = "",
)

@Serializable
data class DetailRule(
    val title: String = "h1@text",
    val author: String = "",
    val cover: String = "",
    val description: String = "",
)

@Serializable
data class ChapterRule(
    /** 目录项选择器，例如 `#list dd a`。 */
    val item: String = "",
    val title: String = "@text",
    val link: String = "@href",
    /** 站点目录若为“最新章节在前”，置 true 会自动倒序。 */
    val reverse: Boolean = true,
)

@Serializable
data class ContentRule(
    val selector: String = "",
    val remove: List<String> = listOf("script", "style", "iframe", ".ads", ".ad"),
)

/** OPDS 目录源。 */
@Serializable
data class OpdsConfig(
    val name: String,
    val url: String,
    val lang: String = "zh",
    /** 可选，支持 {{query}} 占位符。 */
    @SerialName("search_url") val searchUrl: String = "",
)

/** RSS / Atom 源，把订阅源当成一本“连载书”。 */
@Serializable
data class RssConfig(
    val name: String,
    @SerialName("feed_url") val feedUrl: String,
    val lang: String = "zh",
    /** 可选，覆盖书籍标题。 */
    val title: String = "",
)

object SourceConfigs {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
}
