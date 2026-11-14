package app.tsundoku.reader.data.repo

import app.tsundoku.reader.data.local.BookDao
import app.tsundoku.reader.data.local.BookEntity
import app.tsundoku.reader.data.local.ChapterDao
import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.local.toModel
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 书库数据访问。UI 只依赖这一层。 */
class LibraryRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) {

    fun libraryBooks(): Flow<List<Book>> =
        bookDao.observeLibrary().map { list -> list.map { it.toModel() } }

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

    suspend fun dropChaptersFrom(bookId: Long, fromIndex: Int) = chapterDao.deleteFrom(bookId, fromIndex)

    suspend fun updateProgress(bookId: Long, chapterIndex: Int) =
        bookDao.updateProgress(bookId, System.currentTimeMillis(), chapterIndex)

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
