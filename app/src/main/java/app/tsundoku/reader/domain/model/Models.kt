package app.tsundoku.reader.domain.model

/** 书籍文件的格式。 */
enum class BookFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    TXT("TXT"),
    HTML("HTML"),
    UNKNOWN("?"),
    ;

    companion object {
        fun fromName(name: String?): BookFormat {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "epub" -> EPUB
                "pdf" -> PDF
                "txt" -> TXT
                "html", "htm", "xhtml" -> HTML
                else -> UNKNOWN
            }
        }
    }
}

/** 来源类型。 */
enum class SourceType(val label: String) {
    WEB("网页源"),
    LEGADO("阅读书源"),
    OPDS("OPDS"),
    RSS("RSS"),
    LOCAL("本地"),
    ;

    companion object {
        fun fromName(name: String?): SourceType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WEB
    }
}

/**
 * 书库中的一本书。
 *
 * @param url 远端书籍为详情页地址；本地导入为文件或解包目录路径。
 */
data class Book(
    val id: Long,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val coverUrl: String?,
    val sourceId: Long,
    val url: String,
    val description: String?,
    val format: BookFormat,
    val inLibrary: Boolean,
    val lastReadAt: Long?,
    val lastChapterIndex: Int,
    val pageCount: Int,
    val updatedAt: Long,
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
) {
    val progress: Float
        get() = when {
            format == BookFormat.PDF && pageCount > 0 -> {
                (lastChapterIndex + 1).toFloat() / pageCount
            }

            totalChapters > 0 -> readChapters.toFloat() / totalChapters
            else -> 0f
        }.coerceIn(0f, 1f)

    val isLocal: Boolean get() = sourceId == LOCAL_SOURCE_ID

    companion object {
        const val LOCAL_SOURCE_ID = 0L
    }
}

data class Chapter(
    val id: Long,
    val bookId: Long,
    val index: Int,
    val title: String,
    val url: String,
    val content: String?,
    val read: Boolean,
)

/** 来源信息（持久化在 sources 表）。 */
data class BookSourceInfo(
    val id: Long,
    val name: String,
    val type: SourceType,
    val lang: String,
    val enabled: Boolean,
    val config: String,
    val iconUrl: String?,
    val addedAt: Long,
)

/** 搜索结果 / 列表项。 */
data class SearchItem(
    val title: String,
    val url: String,
    val cover: String?,
    val author: String?,
    val description: String? = null,
    /** OPDS 目录项为 true，点击进入下一层目录而非书籍详情。 */
    val isFolder: Boolean = false,
)

data class BookDetails(
    val title: String,
    val author: String?,
    val cover: String?,
    val description: String?,
    val url: String,
)

data class ChapterItem(
    val title: String,
    val url: String,
    val index: Int,
)
