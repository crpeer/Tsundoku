package app.tsundoku.reader.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tsundoku.reader.data.local.BookEntity
import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.repo.LibraryRepository
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** 把外部文件（SAF / 下载）转化成书库记录。 */
class BookImporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val network: NetworkClient,
) {

    private val booksDir = File(context.filesDir, "books")
    private val coversDir = File(context.filesDir, "covers")

    suspend fun importUri(uri: Uri, displayName: String? = null): Long = withContext(Dispatchers.IO) {
        val name = sanitize(displayName ?: queryDisplayName(uri) ?: "未命名书籍.txt")
        val temp = File(context.cacheDir, "import-${UUID.randomUUID()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选文件")
        importFile(temp, name)
    }

    suspend fun importFile(file: File, name: String): Long = withContext(Dispatchers.IO) {
        val safeName = sanitize(name)
        val title = safeName.substringBeforeLast('.', safeName)
        when (BookFormat.fromName(safeName)) {
            BookFormat.EPUB -> importEpub(file, title)
            BookFormat.PDF -> importPdf(file, title)
            else -> importTxt(file, title)
        }.also {
            if (file.parentFile == context.cacheDir) file.delete()
        }
    }

    /** OPDS 等远端书籍：下载到本地后按格式导入。 */
    suspend fun importRemoteBook(
        url: String,
        title: String,
        author: String?,
        coverUrl: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        val format = BookFormat.fromName(url.substringBefore('?'))
        if (format != BookFormat.EPUB && format != BookFormat.PDF) {
            throw IOException("暂不支持该格式：$url")
        }
        val extension = if (format == BookFormat.EPUB) "epub" else "pdf"
        val temp = File(context.cacheDir, "remote-${UUID.randomUUID()}.$extension")
        network.download(url, temp, onProgress = onProgress)
        val bookId = if (format == BookFormat.EPUB) {
            importEpub(temp, title)
        } else {
            importPdf(temp, title)
        }
        temp.delete()
        library.updateMeta(
            bookId = bookId,
            title = title.ifBlank { "未命名书籍" },
            author = author,
            coverUrl = coverUrl,
            coverPath = null,
            description = null,
        )
        bookId
    }

    private suspend fun importTxt(file: File, title: String): Long {
        val drafts = TxtImporter.split(TxtImporter.readText(file))
        if (drafts.isEmpty()) throw IOException("文件内容为空")
        val now = System.currentTimeMillis()
        val chapters = drafts.mapIndexed { index, draft ->
            ChapterEntity(
                bookId = 0,
                idx = index,
                title = draft.title,
                url = "",
                content = draft.html,
                read = false,
                updatedAt = now,
            )
        }
        return library.insertBook(
            book = BookEntity(
                title = title.ifBlank { "未命名书籍" },
                author = null,
                coverPath = null,
                coverUrl = null,
                sourceId = Book.LOCAL_SOURCE_ID,
                url = file.absolutePath,
                description = null,
                format = BookFormat.TXT.name,
                inLibrary = true,
                lastReadAt = null,
                lastChapterIndex = 0,
                pageCount = 0,
                updatedAt = now,
            ),
            chapters = chapters,
        )
    }

    private suspend fun importEpub(file: File, title: String): Long {
        val bookDir = File(booksDir, "epub-${UUID.randomUUID()}")
        val result = EpubImporter().extract(file, bookDir)
        if (result.chapters.isEmpty()) throw IOException("EPUB 中没有可读章节")
        val cover = result.coverFile?.let { copyCover(it) }
        val now = System.currentTimeMillis()
        val chapters = result.chapters.mapIndexed { index, chapter ->
            ChapterEntity(
                bookId = 0,
                idx = index,
                title = chapter.title,
                url = "",
                content = chapter.path,
                read = false,
                updatedAt = now,
            )
        }
        return library.insertBook(
            book = BookEntity(
                title = result.title.ifBlank { title.ifBlank { "未命名书籍" } },
                author = result.author,
                coverPath = cover?.absolutePath,
                coverUrl = null,
                sourceId = Book.LOCAL_SOURCE_ID,
                url = bookDir.absolutePath,
                description = result.description,
                format = BookFormat.EPUB.name,
                inLibrary = true,
                lastReadAt = null,
                lastChapterIndex = 0,
                pageCount = 0,
                updatedAt = now,
            ),
            chapters = chapters,
        )
    }

    private suspend fun importPdf(file: File, title: String): Long {
        val dest = File(booksDir, "pdf-${UUID.randomUUID()}.pdf")
        dest.parentFile?.mkdirs()
        file.copyTo(dest, overwrite = true)

        val coverOut = File(coversDir, "cover-${UUID.randomUUID()}.jpg")
        val info = PdfImporter.inspect(dest, coverOut)
        val now = System.currentTimeMillis()
        return library.insertBook(
            book = BookEntity(
                title = title.ifBlank { "未命名书籍" },
                author = null,
                coverPath = info.coverFile?.absolutePath,
                coverUrl = null,
                sourceId = Book.LOCAL_SOURCE_ID,
                url = dest.absolutePath,
                description = null,
                format = BookFormat.PDF.name,
                inLibrary = true,
                lastReadAt = null,
                lastChapterIndex = 0,
                pageCount = info.pageCount,
                updatedAt = now,
            ),
            chapters = emptyList(),
        )
    }

    private fun copyCover(source: File): File? = runCatching {
        val extension = source.extension.ifBlank { "jpg" }
        val dest = File(coversDir, "cover-${UUID.randomUUID()}.$extension")
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
        dest
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifBlank { "未命名书籍.txt" }
}
