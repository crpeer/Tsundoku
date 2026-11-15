package app.tsundoku.reader.data.repo

import app.tsundoku.reader.data.local.AnnotationDao
import app.tsundoku.reader.data.local.AnnotationEntity
import app.tsundoku.reader.data.local.BookmarkDao
import app.tsundoku.reader.data.local.BookmarkEntity
import app.tsundoku.reader.data.storage.AnnotationMeta
import app.tsundoku.reader.data.storage.BookmarkMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * 批注与书签。
 *
 * 位置用「文本节点路径 + 偏移」保存（与 DOM Range 对应），
 * 所以换字号、换主题、甚至重新排版后仍能定位到同一段文字。
 */
class AnnotationRepository(
    private val annotations: AnnotationDao,
    private val bookmarks: BookmarkDao,
) {

    fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> =
        annotations.observeForBook(bookId)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> =
        bookmarks.observeForBook(bookId)

    fun observeCounts(bookId: Long): Flow<Pair<Int, Int>> =
        combine(annotations.observeCount(bookId), bookmarks.observeCount(bookId)) { a, b -> a to b }

    suspend fun annotationsForChapter(bookId: Long, chapter: Int): List<AnnotationEntity> =
        annotations.forChapter(bookId, chapter)

    suspend fun addAnnotation(
        bookId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        startPath: String,
        startOffset: Int,
        endPath: String,
        endOffset: Int,
        text: String,
        color: Int,
        note: String?,
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        annotations.insert(
            AnnotationEntity(
                id = id,
                bookId = bookId,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                startPath = startPath,
                startOffset = startOffset,
                endPath = endPath,
                endOffset = endOffset,
                text = text,
                color = color,
                note = note,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun updateAnnotation(id: String, note: String?, color: Int) {
        annotations.updateNote(id, note, color, System.currentTimeMillis())
    }

    suspend fun deleteAnnotation(id: String) = annotations.delete(id)

    suspend fun addBookmark(
        bookId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        offset: Int,
        ratio: Float,
        excerpt: String?,
        note: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        bookmarks.insert(
            BookmarkEntity(
                id = id,
                bookId = bookId,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                offset = offset,
                ratio = ratio,
                excerpt = excerpt,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun deleteBookmark(id: String) = bookmarks.delete(id)

    suspend fun annotations(bookId: Long): List<AnnotationEntity> = annotations.getForBook(bookId)

    suspend fun bookmarks(bookId: Long): List<BookmarkEntity> = bookmarks.getForBook(bookId)

    suspend fun annotationMetas(bookId: Long): List<AnnotationMeta> =
        annotations.getForBook(bookId).map { it.toMeta() }

    suspend fun bookmarkMetas(bookId: Long): List<BookmarkMeta> =
        bookmarks.getForBook(bookId).map { it.toMeta() }
}

private fun AnnotationEntity.toMeta(): AnnotationMeta = AnnotationMeta(
    chapterIndex = chapterIndex,
    chapterTitle = chapterTitle,
    quote = text,
    note = note,
    color = COLOR_NAMES[color.coerceIn(0, COLOR_NAMES.lastIndex)],
    createdAt = createdAt,
)

private fun BookmarkEntity.toMeta(): BookmarkMeta = BookmarkMeta(
    chapterIndex = chapterIndex,
    chapterTitle = chapterTitle,
    offset = offset,
    ratio = ratio,
    excerpt = excerpt,
    note = note,
    createdAt = createdAt,
)

/** 与 UI 共用：四种高亮色。 */
val HIGHLIGHT_COLORS = listOf("#FFE082", "#A5D6A7", "#90CAF9", "#F48FB1")
private val COLOR_NAMES = HIGHLIGHT_COLORS


