package app.tsundoku.reader.data.repo

import app.tsundoku.reader.data.local.BookDao
import app.tsundoku.reader.data.local.BookEntity
import app.tsundoku.reader.data.local.ChapterDao
import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.local.toModel
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.domain.model.ChapterItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 书库数据访问。UI 只依赖这一层。 */
class LibraryRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    /** 事务执行器：由 AppContainer 注入 Room 的 withTransaction。 */
    private val transaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {

    fun libraryBooks(): Flow<List<Book>> =
        bookDao.observeLibrary().map { list -> list.map { it.toModel() } }

    suspend fun libraryBooksOnce(): List<Book> = bookDao.getLibrary().map { it.toModel() }

    fun book(id: Long): Flow<Book?> =
        bookDao.observeBook(id).map { it?.toModel() }

    fun chapters(bookId: Long): Flow<List<Chapter>> =
        chapterDao.observeChapters(bookId).map { list -> list.map { it.toModel() } }

    suspend fun bookOnce(id: Long): Book? = bookDao.getBook(id)?.toModel()

    suspend fun chaptersOnce(bookId: Long): List<Chapter> =
        chapterDao.getChapters(bookId).map { it.toModel() }

    suspend fun chapterAt(bookId: Long, index: Int): Chapter? =
        chapterDao.getChapter(bookId, index)?.toModel()

    suspend fun findBook(sourceId: Long, url: String): Book? =
        bookDao.findBySourceUrl(sourceId, url)?.toModel()

    suspend fun insertBook(book: BookEntity, chapters: List<ChapterEntity>): Long {
        val id = bookDao.insert(book)
        if (chapters.isNotEmpty()) {
            chapterDao.insertAll(chapters.mapIndexed { position, chapter ->
                chapter.copy(bookId = id, idx = if (chapter.idx >= 0) chapter.idx else position)
            })
        }
        return id
    }

    suspend fun saveChapters(bookId: Long, chapters: List<ChapterEntity>) {
        chapterDao.insertAll(chapters.mapIndexed { position, chapter ->
            chapter.copy(bookId = bookId, idx = if (chapter.idx >= 0) chapter.idx else position)
        })
    }

    /**
     * 用远端最新目录更新本地章节，**尽量保住**已缓存正文 / 已读状态 / 阅读进度。
     *
     * 整个过程在事务里完成：先读出旧章节到内存，再用合并结果覆盖。
     * 不会简单地把旧章节全部丢掉重来（那会丢正文缓存和已读状态）。
     */
    suspend fun syncChapters(bookId: Long, remote: List<ChapterItem>): ChapterMerge.Result? {
        if (remote.isEmpty()) return null
        val book = bookDao.getBook(bookId) ?: return null
        val local = chapterDao.getChapters(bookId)
        val merged = ChapterMerge.merge(bookId, local, remote, book.lastChapterIndex)

        transaction {
            chapterDao.deleteForBook(bookId)
            chapterDao.insertAll(merged.chapters)
            bookDao.updateProgress(
                id = bookId,
                at = book.lastReadAt ?: System.currentTimeMillis(),
                index = merged.progressIndex,
                offset = book.lastPositionOffset,
            )
        }
        return merged
    }

    suspend fun dropChaptersFrom(bookId: Long, fromIndex: Int) = chapterDao.deleteFrom(bookId, fromIndex)

    suspend fun updateProgress(bookId: Long, chapterIndex: Int, offset: Int = 0) =
        bookDao.updateProgress(bookId, System.currentTimeMillis(), chapterIndex, offset)

    /** 只更新章节内位置，不动章节索引（滚动/翻页时高频调用）。 */
    suspend fun updatePosition(bookId: Long, chapterIndex: Int, offset: Int) =
        bookDao.updateProgress(bookId, System.currentTimeMillis(), chapterIndex, offset)

    suspend fun markRead(bookId: Long, chapterIndex: Int, read: Boolean) =
        chapterDao.setRead(bookId, chapterIndex, read)

    suspend fun cacheContent(bookId: Long, chapterIndex: Int, html: String) =
        chapterDao.updateContent(bookId, chapterIndex, html)

    suspend fun setInLibrary(bookId: Long, inLibrary: Boolean) =
        bookDao.setInLibrary(bookId, inLibrary)

    suspend fun updateMeta(
        bookId: Long,
        title: String,
        author: String?,
        coverUrl: String?,
        coverPath: String?,
        description: String?,
    ) = bookDao.updateMeta(
        id = bookId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        coverPath = coverPath,
        description = description,
        updatedAt = System.currentTimeMillis(),
    )

    suspend fun delete(bookId: Long) = bookDao.delete(bookId)
}
