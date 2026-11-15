package app.tsundoku.reader.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tsundoku.reader.data.local.BookEntity
import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.repo.LibraryRepository
import app.tsundoku.reader.data.storage.BookStorage
import app.tsundoku.reader.data.storage.MetadataSyncer
import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 把外部文件（SAF / 下载）转化成书库记录。
 *
 * 目录结构走 Mihon 风格：一本书一个文件夹，里面放原文件、cover.png、meta.json。
 */
class BookImporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val network: NetworkClient,
    private val storage: BookStorage,
    private val syncer: MetadataSyncer,
) {

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
        val folder = storage.bookFolder(title)
        val format = BookFormat.fromName(safeName)
        val local = File(folder, "book.${safeName.substringAfterLast('.', "txt")}")
        if (file.absolutePath != local.absolutePath) file.copyTo(local, overwrite = true)
        val bookId = try {
            when (format) {
                BookFormat.EPUB -> importEpub(local, folder, title)
                BookFormat.PDF -> importPdf(local, folder, title)
                else -> importTxt(local, title)
            }
        } catch (error: Throwable) {
            // 导入失败不留空书目录
            folder.deleteRecursively()
            throw error
        }
        if (file.parentFile == context.cacheDir) file.delete()
        syncer.sync(bookId)
        bookId
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
        val folder = storage.bookFolder(title)
        val local = File(folder, "book.$extension")
        network.download(url, local, onProgress = onProgress)
        val bookId = if (format == BookFormat.EPUB) {
            importEpub(local, folder, title)
        } else {
            importPdf(local, folder, title)
        }
        library.updateMeta(
            bookId = bookId,
            title = title.ifBlank { "未命名书籍" },
            author = author,
            coverUrl = coverUrl,
            coverPath = File(folder, "cover.png").takeIf { it.exists() }?.absolutePath,
            description = null,
        )
        syncer.sync(bookId)
        bookId
    }

    private suspend fun importTxt(file: File, title: String): Long {
        val now = System.currentTimeMillis()
        // 先建书，再边解析边分批写章节，避免把整本书的章节同时放进内存。
        val bookId = library.insertBook(
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
            chapters = emptyList(),
        )
        var index = 0
        val result = try {
            TxtImporter.import(file) { batch ->
                val entities = batch.map { draft ->
                    ChapterEntity(
                        bookId = bookId,
                        idx = index++,
                        title = draft.title,
                        url = "",
                        content = draft.html,
                        read = false,
                        updatedAt = now,
                    )
                }
                library.saveChapters(bookId, entities)
            }
        } catch (error: Throwable) {
            library.delete(bookId)
            throw error
        }
        if (result.chapterCount == 0) {
            library.delete(bookId)
            throw IOException("文件内容为空")
        }
        return bookId
    }

    private suspend fun importEpub(file: File, folder: File, fallbackTitle: String): Long {
        val pagesDir = File(folder, "pages")
        val result = EpubImporter().extract(file, pagesDir)
        if (result.chapters.isEmpty()) throw IOException("EPUB 中没有可读章节")
        val cover = toCoverPng(folder, result.coverFile)
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
                title = result.title.ifBlank { fallbackTitle.ifBlank { "未命名书籍" } },
                author = result.author,
                coverPath = cover?.absolutePath,
                coverUrl = null,
                sourceId = Book.LOCAL_SOURCE_ID,
                url = folder.absolutePath,
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

    private suspend fun importPdf(file: File, folder: File, title: String): Long {
        val coverTmp = File(folder, "cover-page.jpg")
        val info = PdfImporter.inspect(file, coverTmp)
        // 解析不出页数说明 PDF 已损坏或不是有效 PDF，不能当成导入成功。
        if (info.pageCount <= 0) {
            throw IOException("PDF 无法解析（可能已损坏或加密），已取消导入")
        }
        val cover = toCoverPng(folder, coverTmp)
        val now = System.currentTimeMillis()
        return library.insertBook(
            book = BookEntity(
                title = title.ifBlank { "未命名书籍" },
                author = null,
                coverPath = cover?.absolutePath,
                coverUrl = null,
                sourceId = Book.LOCAL_SOURCE_ID,
                url = file.absolutePath,
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

    /** 导入时拿到的封面统一转成 `<书目录>/cover.png`。 */
    private fun toCoverPng(folder: File, source: File?): File? {
        if (source == null || !source.exists()) return null
        val target = source.inputStream().use { storage.writeCover(folder, it) }
        if (target != null && source.absolutePath != target.absolutePath && source.parentFile == folder) {
            source.delete()
        }
        return target ?: source
    }

    /** 用户手动换封面：写进 `<书目录>/cover.png`（远端书先建目录）。 */
    suspend fun replaceCover(book: Book, uri: Uri): File? = withContext(Dispatchers.IO) {
        val folder = storage.folderOf(book) ?: storage.bookFolder(book.title)
        context.contentResolver.openInputStream(uri)?.use { input ->
            storage.writeCover(folder, input)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifBlank { "未命名书籍.txt" }
}
