package app.tsundoku.reader.data.local

import app.tsundoku.reader.domain.model.BookSourceInfo

fun BookSourceInfo.toEntity(): SourceEntity = SourceEntity(
    id = id,
    name = name,
    type = type.name,
    lang = lang,
    enabled = enabled,
    config = config,
    iconUrl = iconUrl,
    addedAt = addedAt,
)
