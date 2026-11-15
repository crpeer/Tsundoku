package app.tsundoku.reader.data.storage

import app.tsundoku.reader.data.prefs.SettingsRepository
import app.tsundoku.reader.data.repo.AnnotationRepository
import app.tsundoku.reader.data.repo.LibraryRepository
import app.tsundoku.reader.domain.model.Book

/** 把书库里的进度/批注同步成 meta.json。 */
class MetadataSyncer(
    private val storage: BookStorage,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val annotations: AnnotationRepository,
) {

    suspend fun sync(bookId: Long) {
        val book = library.bookOnce(bookId) ?: return
        val chapters = library.chaptersOnce(bookId)
        storage.writeMeta(
            book = book,
            chapters = chapters,
            inBookDir = settings.metadataInBookDir(),
            annotations = annotations.annotationMetas(bookId),
            bookmarks = annotations.bookmarkMetas(bookId),
        )
    }

    /** 删除前把当前状态落盘，之后再删库记录。 */
    suspend fun keepFor(book: Book) {
        val chapters = library.chaptersOnce(book.id)
        storage.writeMeta(
            book = book,
            chapters = chapters,
            inBookDir = settings.metadataInBookDir(),
            annotations = annotations.annotationMetas(book.id),
            bookmarks = annotations.bookmarkMetas(book.id),
        )
    }

    suspend fun discardFor(book: Book) {
        storage.deleteMeta(book, settings.metadataInBookDir())
    }
}
