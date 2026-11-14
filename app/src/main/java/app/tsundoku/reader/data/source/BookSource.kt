package app.tsundoku.reader.data.source

import app.tsundoku.reader.domain.model.BookDetails
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.ChapterItem
import app.tsundoku.reader.domain.model.SearchItem

/**
 * 书源统一接口。网页源 / OPDS / RSS 都实现它，
 * 这样 UI 层不需要关心来源类型。
 */
interface BookSource {
    val info: BookSourceInfo

    /** 关键字搜索，page 从 1 开始。 */
    suspend fun search(query: String, page: Int = 1): List<SearchItem>

    /** 浏览。url 为 null 时加载首页；OPDS 用它做目录下钻。 */
    suspend fun browse(url: String? = null, page: Int = 1): List<SearchItem>

    /** 获取书籍详情，用于补全作者/简介/封面。 */
    suspend fun details(url: String): BookDetails

    /** 获取章节目录。 */
    suspend fun chapters(bookUrl: String): List<ChapterItem>

    /** 获取章节正文（HTML）。 */
    suspend fun content(chapterUrl: String): String
}

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
