package app.tsundoku.reader.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.domain.model.SourceConfigs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream

/** 一本书的元数据（进度 + 批注），存成 meta.json，便于随书备份/迁移。 */
@Serializable
data class BookMeta(
    val id: Long,
    val title: String,
    val author: String? = null,
    val format: String = "",
    val sourceUrl: String = "",
    val updatedAt: Long = 0,
    val progress: ProgressMeta = ProgressMeta(),
    val annotations: List<AnnotationMeta> = emptyList(),
    val bookmarks: List<BookmarkMeta> = emptyList(),
)

@Serializable
data class ProgressMeta(
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
    val lastReadAt: Long = 0,
    /** 章节内位置：滚动偏移（CSS px）或翻页页码。 */
    val positionOffset: Int = 0,
)

@Serializable
data class BookmarkMeta(
    val chapterIndex: Int,
    val chapterTitle: String? = null,
    val offset: Int = 0,
    val ratio: Float = 0f,
    val excerpt: String? = null,
    val note: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class AnnotationMeta(
    val chapterIndex: Int,
    val chapterTitle: String? = null,
    val quote: String,
    val note: String? = null,
    val color: String = "#FFE082",
    val createdAt: Long = 0,
)

/**
 * Mihon 风格的书籍目录：
 *
 * ```
 * files/books/<书名>/
 *   book.epub|book.txt|book.pdf   原始文件
 *   cover.png                     封面（可替换）
 *   meta.json                     进度 + 批注（按设置决定是否放这里）
 *   pages/…                       EPUB 解包出的章节
 * ```
 */
class BookStorage(private val context: Context) {

    val booksRoot: File
        get() = File(context.filesDir, "books").apply { mkdirs() }

    private val metaRoot: File
        get() = File(context.filesDir, "meta").apply { mkdirs() }

    /** 书名 -> 安全目录名（同名会追加 -2、-3）。 */
    fun bookFolder(title: String): File {
        val base = sanitize(title).ifBlank { "book" }
        var candidate = File(booksRoot, base)
        var index = 2
        while (candidate.exists()) {
            candidate = File(booksRoot, "$base-$index")
            index++
        }
        candidate.mkdirs()
        return candidate
    }

    /** 已有书籍所在目录：EPUB 的 url 就是目录；其它格式取其父目录。 */
    fun folderOf(book: Book): File? {
        val url = book.url
        if (url.isBlank()) return null
        val file = File(url)
        val dir = if (file.isDirectory) file else file.parentFile
        return dir?.takeIf { it.exists() }
    }

    fun metaFile(book: Book, inBookDir: Boolean): File? {
        val dir = if (inBookDir) folderOf(book) else metaRoot
        return dir?.let { File(it, if (inBookDir) "meta.json" else "${book.id}.json") }
    }

    fun writeMeta(
        book: Book,
        chapters: List<Chapter>,
        inBookDir: Boolean,
        annotations: List<AnnotationMeta> = emptyList(),
        bookmarks: List<BookmarkMeta> = emptyList(),
    ): File? {
        val target = metaFile(book, inBookDir) ?: return null
        val meta = BookMeta(
            id = book.id,
            title = book.title,
            author = book.author,
            format = book.format.name,
            sourceUrl = book.url,
            updatedAt = System.currentTimeMillis(),
            progress = ProgressMeta(
                chapterIndex = book.lastChapterIndex,
                chapterTitle = chapters.getOrNull(book.lastChapterIndex)?.title,
                totalChapters = chapters.size,
                readChapters = chapters.count { it.read },
                lastReadAt = book.lastReadAt ?: 0,
                positionOffset = book.lastPositionOffset,
            ),
            annotations = annotations,
            bookmarks = bookmarks,
        )
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(SourceConfigs.json.encodeToString(meta))
            target
        }.getOrNull()
    }

    fun readMeta(book: Book, inBookDir: Boolean): BookMeta? {
        val target = metaFile(book, inBookDir) ?: return null
        if (!target.exists()) return null
        return runCatching {
            SourceConfigs.json.decodeFromString<BookMeta>(target.readText())
        }.getOrNull()
    }

    fun deleteMeta(book: Book, inBookDir: Boolean) {
        runCatching { metaFile(book, inBookDir)?.delete() }
    }

    /** 任意图片（jpg/png/webp…）转成 `cover.png`，最长边不超过 1200px。 */
    fun writeCover(folder: File, input: InputStream): File? = runCatching {
        val bitmap = BitmapFactory.decodeStream(input) ?: return@runCatching null
        val scaled = scaleDown(bitmap, 1200)
        val target = File(folder, "cover.png")
        target.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        target
    }.getOrNull()

    /** 后续接批注功能时改这里。 */

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60)
}
