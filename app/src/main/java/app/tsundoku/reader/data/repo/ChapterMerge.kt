package app.tsundoku.reader.data.repo

import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.domain.model.ChapterItem

/**
 * 目录同步的纯逻辑：把「远端最新目录」合并进「本地章节」，尽量保住用户数据。
 *
 * 匹配优先级：章节 URL（稳定）→ 标题 + 位置（老数据兜底）。
 * 保留：已缓存正文（content）、已读状态（read）、原来的行 id。
 * 丢弃：远端已经不存在的章节（否则 idx 会错位）。
 */
object ChapterMerge {

    data class Result(
        val chapters: List<ChapterEntity>,
        /** 远端新增的章节数。 */
        val added: Int,
        /** 远端已删除、本地被移除的章节数。 */
        val removed: Int,
        /** 原来读到第几章，映射到新目录后的下标。 */
        val progressIndex: Int,
        /** 标题发生变化的章节数。 */
        val renamed: Int,
    )

    fun merge(
        bookId: Long,
        local: List<ChapterEntity>,
        remote: List<ChapterItem>,
        previousIndex: Int,
        now: Long = System.currentTimeMillis(),
    ): Result {
        if (remote.isEmpty()) {
            return Result(local, added = 0, removed = 0, progressIndex = previousIndex.coerceAtLeast(0), renamed = 0)
        }

        val byUrl = local.filter { it.url.isNotBlank() }.associateBy { it.url }
        val byTitle = local.groupBy { it.title.trim() }

        val previousUrl = local.getOrNull(previousIndex)?.url

        var added = 0
        var renamed = 0
        val merged = remote.mapIndexed { index, item ->
            val old = byUrl[item.url]
                ?: byTitle[item.title.trim()]?.firstOrNull { candidate ->
                    // 同名的只认位置接近的那条，避免误配
                    kotlin.math.abs(candidate.idx - index) <= 3
                }
            if (old == null) added++
            if (old != null && old.title != item.title && old.title.isNotBlank()) renamed++
            ChapterEntity(
                id = old?.id ?: 0,
                bookId = bookId,
                idx = index,
                title = item.title,
                url = item.url,
                content = old?.content,
                read = old?.read ?: false,
                updatedAt = now,
            )
        }

        val remoteUrls = remote.mapTo(HashSet()) { it.url }
        val removed = local.count { chapter ->
            chapter.url.isNotBlank() && chapter.url !in remoteUrls
        }

        val progressIndex = when {
            previousUrl.isNullOrBlank() -> previousIndex.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
            else -> merged.indexOfFirst { it.url == previousUrl }
                .takeIf { it >= 0 }
                ?: previousIndex.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
        }

        return Result(merged, added, removed, progressIndex, renamed)
    }
}
