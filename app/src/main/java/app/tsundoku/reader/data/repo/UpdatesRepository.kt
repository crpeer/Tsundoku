package app.tsundoku.reader.data.repo

import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.source.SourceManager
import app.tsundoku.reader.domain.model.Book

/**
 * 追更扫描：拿书架里每本远端书的目录和本地章节对比，把新增章节追加进来。
 *
 * 只做「追加」：本地已有的章节不会被删改，避免把用户进度/缓存弄丢。
 */
class UpdatesRepository(
    private val library: LibraryRepository,
    private val sources: SourceManager,
) {

    data class UpdateResult(
        val bookId: Long,
        val title: String,
        val newChapters: Int,
        val firstNewIndex: Int,
        val latestChapter: String?,
        val error: String? = null,
    )

    suspend fun scan(onProgress: (String) -> Unit = {}): List<UpdateResult> {
        val books = library.libraryBooksOnce().filter { !it.isLocal }
        val results = ArrayList<UpdateResult>(books.size)
        for (book in books) {
            onProgress(book.title)
            results += check(book)
        }
        return results
    }

    private suspend fun check(book: Book): UpdateResult {
        val source = runCatching { sources.sourceById(book.sourceId) }.getOrNull()
            ?: return UpdateResult(book.id, book.title, 0, -1, null, "来源不可用")

        val remote = runCatching { source.chapters(book.url) }.getOrElse { error ->
            return UpdateResult(book.id, book.title, 0, -1, null, error.message ?: "目录获取失败")
        }
        if (remote.isEmpty()) {
            return UpdateResult(book.id, book.title, 0, -1, null, "目录为空")
        }

        // 合并（而不是追加）才能真正处理「远端插入/改名/删除」，且保住已缓存正文与已读状态。
        val merged = library.syncChapters(book.id, remote)
            ?: return UpdateResult(book.id, book.title, 0, -1, null, "书籍不存在")
        val firstNew = if (merged.added > 0) {
            merged.chapters.indexOfFirst { it.content.isNullOrBlank() && !it.read }.coerceAtLeast(0)
        } else {
            -1
        }
        return UpdateResult(
            bookId = book.id,
            title = book.title,
            newChapters = merged.added,
            firstNewIndex = firstNew,
            latestChapter = remote.lastOrNull()?.title,
        )
    }
}
