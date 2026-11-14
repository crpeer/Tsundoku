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

        val local = library.chaptersOnce(book.id)
        val lastLocalUrl = local.lastOrNull()?.url
        val matched = if (lastLocalUrl == null) -1 else remote.indexOfLast { it.url == lastLocalUrl }
        val fresh = if (matched >= 0) {
            remote.drop(matched + 1)
        } else {
            val known = local.map { it.url }.toSet()
            remote.filter { it.url !in known }
        }
        if (fresh.isEmpty()) {
            return UpdateResult(book.id, book.title, 0, -1, remote.lastOrNull()?.title)
        }

        val start = local.size
        val now = System.currentTimeMillis()
        library.saveChapters(
            book.id,
            fresh.mapIndexed { offset, item ->
                ChapterEntity(
                    bookId = book.id,
                    idx = start + offset,
                    title = item.title,
                    url = item.url,
                    content = null,
                    read = false,
                    updatedAt = now,
                )
            },
        )
        return UpdateResult(book.id, book.title, fresh.size, start, fresh.lastOrNull()?.title)
    }
}
