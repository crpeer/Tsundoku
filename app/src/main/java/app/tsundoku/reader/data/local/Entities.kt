package app.tsundoku.reader.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val coverUrl: String?,
    val sourceId: Long,
    val url: String,
    val description: String?,
    val format: String,
    val inLibrary: Boolean,
    val lastReadAt: Long?,
    val lastChapterIndex: Int,
    val pageCount: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "idx"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val idx: Int,
    val title: String,
    val url: String,
    val content: String?,
    val read: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val lang: String,
    val enabled: Boolean,
    val config: String,
    val iconUrl: String?,
    val addedAt: Long,
)

/** 书库列表查询：书籍 + 章节统计。 */
data class BookWithStats(
    @Embedded val book: BookEntity,
    val chapterCount: Int,
    val readCount: Int,
)
