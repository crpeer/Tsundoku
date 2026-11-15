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
    /** 当前章节内的位置：滚动模式的纵向偏移（CSS px），翻页模式的页码。 */
    val lastPositionOffset: Int = 0,
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

/**
 * 批注（高亮 / 笔记）。
 *
 * 位置用「文本节点路径 + 偏移」记录（与 DOM Range 一一对应），
 * 这样换字号、换主题甚至重新排版后依然能定位到同一段文字。
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"])],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val startPath: String,
    val startOffset: Int,
    val endPath: String,
    val endOffset: Int,
    val text: String,
    /** 0..3，对应四种高亮颜色。 */
    val color: Int,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 书签：章节 + 章内位置（翻页模式存页码，滚动模式存相对比例编码）。 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val offset: Int,
    val ratio: Float,
    val excerpt: String?,
    val note: String?,
    val createdAt: Long,
)

/** 全书搜索的一行结果（章节 + 命中位置由调用方计算摘要）。 */
data class ChapterSearchRow(
    val idx: Int,
    val title: String,
    val content: String?,
)