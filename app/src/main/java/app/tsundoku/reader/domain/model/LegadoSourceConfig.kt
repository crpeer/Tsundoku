package app.tsundoku.reader.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「阅读 / Legado」书源格式（bookSource）。
 *
 * 只映射本阶段用得到的规则字段；未映射的字段原样保存在 `sources.config` 里，
 * 以后补支持时不用重新导入。
 */
@Serializable
data class LegadoSourceConfig(
    @SerialName("bookSourceName") val name: String = "",
    @SerialName("bookSourceUrl") val baseUrl: String = "",
    @SerialName("bookSourceGroup") val group: String = "",
    @SerialName("bookSourceType") val sourceType: Int = 0,
    @SerialName("bookSourceComment") val comment: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("searchUrl") val searchUrl: String = "",
    @SerialName("exploreUrl") val exploreUrl: String = "",
    @SerialName("header") val header: String = "",
    @SerialName("loginUrl") val loginUrl: String = "",
    @SerialName("ruleSearch") val search: LegadoSearchRule = LegadoSearchRule(),
    @SerialName("ruleExplore") val explore: LegadoExploreRule = LegadoExploreRule(),
    @SerialName("ruleBookInfo") val bookInfo: LegadoBookInfoRule = LegadoBookInfoRule(),
    @SerialName("ruleToc") val toc: LegadoTocRule = LegadoTocRule(),
    @SerialName("ruleContent") val content: LegadoContentRule = LegadoContentRule(),
)

@Serializable
data class LegadoExploreRule(
    val bookList: String = "",
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val wordCount: String = "",
    val coverUrl: String = "",
    val bookUrl: String = "",
)

@Serializable
data class LegadoSearchRule(
    val bookList: String = "",
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val wordCount: String = "",
    val coverUrl: String = "",
    val bookUrl: String = "",
    val checkKeyWord: String = "",
)

@Serializable
data class LegadoBookInfoRule(
    val init: String = "",
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val wordCount: String = "",
    val coverUrl: String = "",
    val tocUrl: String = "",
    val canReName: String = "",
)

@Serializable
data class LegadoTocRule(
    val chapterList: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val formatJs: String = "",
    val isVolume: String = "",
    val updateTime: String = "",
    val nextTocUrl: String = "",
)

@Serializable
data class LegadoContentRule(
    val content: String = "",
    val title: String = "",
    val nextContentUrl: String = "",
    val webJs: String = "",
    val sourceRegex: String = "",
    val replaceRegex: String = "",
    val imageStyle: String = "",
)

/** 导入时展示/筛选用的轻量信息，configJson 保留原始 JSON 不丢字段。 */
data class LegadoSourceCandidate(
    val name: String,
    val group: String,
    val baseUrl: String,
    val sourceType: Int,
    val configJson: String,
) {
    val typeLabel: String
        get() = when (sourceType) {
            1 -> "漫画"
            2 -> "听书"
            3 -> "音乐"
            4 -> "影视"
            else -> "小说"
        }
}
