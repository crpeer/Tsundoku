package app.tsundoku.reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query(
        """
        SELECT b.*,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id) AS chapterCount,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.read = 1) AS readCount
        FROM books b
        WHERE b.inLibrary = 1
        ORDER BY COALESCE(b.lastReadAt, 0) DESC, b.updatedAt DESC
        """,
    )
    fun observeLibrary(): Flow<List<BookWithStats>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun findBySourceUrl(sourceId: Long, url: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceId = :sourceId ORDER BY id DESC")
    suspend fun getBySource(sourceId: Long): List<BookEntity>

    @Query(
        """
        SELECT b.*,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id) AS chapterCount,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.read = 1) AS readCount
        FROM books b
        WHERE b.inLibrary = 1
        ORDER BY COALESCE(b.lastReadAt, 0) DESC, b.updatedAt DESC
        """,
    )
    suspend fun getLibrary(): List<BookWithStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastReadAt = :at, lastChapterIndex = :index, lastPositionOffset = :offset WHERE id = :id")
    suspend fun updateProgress(id: Long, at: Long, index: Int, offset: Int)

    @Query("UPDATE books SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: Long, inLibrary: Boolean)

    @Query(
        """
        UPDATE books SET
            title = :title,
            author = COALESCE(:author, author),
            coverUrl = COALESCE(:coverUrl, coverUrl),
            coverPath = COALESCE(:coverPath, coverPath),
            description = COALESCE(:description, description),
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMeta(
        id: Long,
        title: String,
        author: String?,
        coverUrl: String?,
        coverPath: String?,
        description: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx ASC")
    fun observeChapters(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx ASC")
    suspend fun getChapters(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND idx = :index LIMIT 1")
    suspend fun getChapter(bookId: Long, index: Int): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun count(bookId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET read = :read WHERE bookId = :bookId AND idx = :index")
    suspend fun setRead(bookId: Long, index: Int, read: Boolean)

    @Query("UPDATE chapters SET content = :content WHERE bookId = :bookId AND idx = :index")
    suspend fun updateContent(bookId: Long, index: Int, content: String)

    @Query("DELETE FROM chapters WHERE bookId = :bookId AND idx >= :fromIndex")
    suspend fun deleteFrom(bookId: Long, fromIndex: Int)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY addedAt ASC")
    suspend fun getEnabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY addedAt ASC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}
