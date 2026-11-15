package app.tsundoku.reader

import app.tsundoku.reader.data.local.ChapterEntity
import app.tsundoku.reader.data.repo.ChapterMerge
import app.tsundoku.reader.domain.model.ChapterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录更新必须保住缓存正文、已读状态和阅读进度。 */
class ChapterMergeTest {

    private fun local(idx: Int, title: String, url: String, content: String? = "cached-$idx", read: Boolean = idx < 2) =
        ChapterEntity(
            id = (idx + 1).toLong(),
            bookId = 1,
            idx = idx,
            title = title,
            url = url,
            content = content,
            read = read,
            updatedAt = 0,
        )

    private fun remote(vararg titles: String) = titles.mapIndexed { index, title ->
        ChapterItem(title = title, url = "https://s/$index", index = index)
    }

    @Test
    fun keepsCachedContentAndReadFlagsOnRefresh() {
        val local = listOf(
            local(0, "第一章", "https://s/0"),
            local(1, "第二章", "https://s/1"),
        )
        val result = ChapterMerge.merge(1, local, remote("第一章", "第二章"), previousIndex = 1)

        assertEquals(2, result.chapters.size)
        assertEquals("cached-0", result.chapters[0].content)
        assertEquals("cached-1", result.chapters[1].content)
        assertTrue(result.chapters[0].read)
        assertTrue(result.chapters[1].read)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun appendsNewChaptersWithoutTouchingOld() {
        val local = listOf(local(0, "第一章", "https://s/0"), local(1, "第二章", "https://s/1"))
        val result = ChapterMerge.merge(1, local, remote("第一章", "第二章", "第三章"), previousIndex = 0)

        assertEquals(3, result.chapters.size)
        assertEquals(1, result.added)
        assertEquals("cached-0", result.chapters[0].content)
        assertEquals(null, result.chapters[2].content)
        assertFalse(result.chapters[2].read)
    }

    @Test
    fun reorderedRemoteKeepsDataByUrl() {
        val local = listOf(
            local(0, "A", "https://s/0"),
            local(1, "B", "https://s/1"),
            local(2, "C", "https://s/2"),
        )
        // 远端把顺序换成 C, A, B
        val reordered = listOf(
            ChapterItem("C", "https://s/2", 0),
            ChapterItem("A", "https://s/0", 1),
            ChapterItem("B", "https://s/1", 2),
        )
        val result = ChapterMerge.merge(1, local, reordered, previousIndex = 0)

        assertEquals(listOf("C", "A", "B"), result.chapters.map { it.title })
        assertEquals("cached-2", result.chapters[0].content)
        assertEquals("cached-0", result.chapters[1].content)
        // 原来在第 0 章（A），合并后 A 在第 1 位
        assertEquals(1, result.progressIndex)
    }

    @Test
    fun removedRemoteChaptersAreDropped() {
        val local = listOf(
            local(0, "A", "https://s/0"),
            local(1, "B", "https://s/1"),
            local(2, "C", "https://s/2"),
        )
        val result = ChapterMerge.merge(1, local, remote("A", "C"), previousIndex = 2)
        // remote() 生成 https://s/0 / https://s/1，这里手工构造更直观
        val explicit = ChapterMerge.merge(
            1,
            local,
            listOf(ChapterItem("A", "https://s/0", 0), ChapterItem("C", "https://s/2", 1)),
            previousIndex = 2,
        )
        assertEquals(2, explicit.chapters.size)
        assertEquals(1, explicit.removed)
        assertEquals("cached-2", explicit.chapters[1].content)
        assertEquals(1, explicit.progressIndex)

        assertEquals(2, result.chapters.size)
    }

    @Test
    fun renamedChapterKeepsContent() {
        val local = listOf(local(0, "第1章 旧名", "https://s/0"))
        val result = ChapterMerge.merge(1, local, remote("第1章 新名"), previousIndex = 0)
        assertEquals("第1章 新名", result.chapters[0].title)
        assertEquals("cached-0", result.chapters[0].content)
        assertEquals(1, result.renamed)
    }

    @Test
    fun emptyRemoteDoesNotWipeLocal() {
        val local = listOf(local(0, "A", "https://s/0"))
        val result = ChapterMerge.merge(1, local, emptyList(), previousIndex = 0)
        assertEquals(1, result.chapters.size)
        assertEquals("cached-0", result.chapters[0].content)
    }

    @Test
    fun chaptersWithoutUrlMatchByTitleNearby() {
        val local = listOf(local(0, "第一章", ""), local(1, "第二章", ""))
        val result = ChapterMerge.merge(
            1,
            local,
            listOf(ChapterItem("第一章", "https://s/0", 0), ChapterItem("第二章", "https://s/1", 1)),
            previousIndex = 0,
        )
        assertEquals("cached-0", result.chapters[0].content)
        assertEquals(0, result.added)
    }

    @Test
    fun progressIndexIsClampedWhenChapterDisappears() {
        val local = listOf(local(0, "A", "https://s/0"), local(1, "B", "https://s/1"))
        val result = ChapterMerge.merge(
            1,
            local,
            listOf(ChapterItem("A", "https://s/0", 0)),
            previousIndex = 1,
        )
        assertEquals(0, result.progressIndex)
    }
}
