package app.tsundoku.reader.data.local

import app.tsundoku.reader.domain.model.Book
import app.tsundoku.reader.domain.model.BookFormat
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.Chapter
import app.tsundoku.reader.domain.model.SourceType

fun BookEntity.toModel(chapterCount: Int = 0, readCount: Int = 0): Book = Book(
    id = id,
    title = title,
    author = author,
    coverPath = coverPath,
    coverUrl = coverUrl,
    sourceId = sourceId,
    url = url,
    description = description,
    format = BookFormat.entries.firstOrNull { it.name == format } ?: BookFormat.UNKNOWN,
    inLibrary = inLibrary,
    lastReadAt = lastReadAt,
    lastChapterIndex = lastChapterIndex,
    pageCount = pageCount,
    updatedAt = updatedAt,
    totalChapters = chapterCount,
    readChapters = readCount,
)

fun BookWithStats.toModel(): Book = book.toModel(chapterCount, readCount)

fun ChapterEntity.toModel(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    index = idx,
    title = title,
    url = url,
    content = content,
    read = read,
)

fun SourceEntity.toModel(): BookSourceInfo = BookSourceInfo(
    id = id,
    name = name,
    type = SourceType.fromName(type),
    lang = lang,
    enabled = enabled,
    config = config,
    iconUrl = iconUrl,
    addedAt = addedAt,
)
