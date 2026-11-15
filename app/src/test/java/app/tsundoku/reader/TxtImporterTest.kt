package app.tsundoku.reader

import app.tsundoku.reader.data.import.TxtImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/** TXT 导入：编码识别、章节切分、流式分批、异常文件。 */
class TxtImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    @Test
    fun splitsChaptersFromUtf8File() = runBlocking {
        val text = buildString {
            appendLine("前言内容".repeat(20))
            appendLine("第一章 起风")
            appendLine("正文一")
            appendLine("第二章 落雨")
            appendLine("正文二")
        }
        val target = file("a.txt", text.toByteArray(Charsets.UTF_8))
        val batches = mutableListOf<List<TxtImporter.Draft>>()
        val result = TxtImporter.import(target) { batches += it }

        assertEquals(Charsets.UTF_8, result.charset)
        assertEquals(3, result.chapterCount) // 前言 + 两章
        val drafts = batches.flatten()
        assertEquals("前言", drafts[0].title)
        assertEquals("第一章 起风", drafts[1].title)
        assertTrue(drafts[1].html.contains("正文一"))
        assertEquals("第二章 落雨", drafts[2].title)
    }

    @Test
    fun detectsGbkFile() = runBlocking {
        val gbk = Charset.forName("GBK")
        val text = "第一章 开始\n这是中文正文，用来验证 GBK 识别。\n第二章 继续\n又一段中文。\n"
        val target = file("gbk.txt", text.toByteArray(gbk))
        val drafts = mutableListOf<TxtImporter.Draft>()
        TxtImporter.import(target) { drafts += it }
        assertEquals(gbk, TxtImporter.detectCharset(target))
        assertEquals("第一章 开始", drafts.first().title)
        assertTrue(drafts.first().html.contains("中文正文"))
    }

    @Test
    fun utf8BomIsHandled() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "第一章 带BOM\n正文".toByteArray(Charsets.UTF_8)
        val target = file("bom.txt", bom + body)
        val drafts = mutableListOf<TxtImporter.Draft>()
        TxtImporter.import(target) { drafts += it }
        // BOM 不应出现在标题里
        assertTrue(!drafts.first().title.startsWith("\uFEFF"))
        assertTrue(drafts.first().title.contains("第一章"))
    }

    @Test
    fun fileWithoutHeadingsBecomesSingleChapter() = runBlocking {
        val target = file("plain.txt", "只有一段普通文字。\n第二行。".toByteArray())
        val drafts = mutableListOf<TxtImporter.Draft>()
        val result = TxtImporter.import(target) { drafts += it }
        assertEquals(1, result.chapterCount)
        assertEquals("正文", drafts.single().title)
    }

    @Test
    fun emptyFileYieldsNothing() = runBlocking {
        val target = file("empty.txt", ByteArray(0))
        val drafts = mutableListOf<TxtImporter.Draft>()
        val result = TxtImporter.import(target) { drafts += it }
        assertEquals(0, result.chapterCount)
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun largeFileIsEmittedInBatches() = runBlocking {
        val builder = StringBuilder()
        repeat(120) { index ->
            builder.append("第${index + 1}章 标题\n")
            repeat(20) { builder.append("这是第 ${index + 1} 章的正文行，用于测试流式读取。\n") }
        }
        val target = file("big.txt", builder.toString().toByteArray(Charsets.UTF_8))
        val batchSizes = mutableListOf<Int>()
        var total = 0
        val result = TxtImporter.import(target) { batch ->
            batchSizes += batch.size
            total += batch.size
            // 每一批都不能超过上限
            assertTrue(batch.size <= TxtImporter.BATCH_SIZE)
        }
        assertEquals(120, result.chapterCount)
        assertEquals(120, total)
        assertTrue("应当分成多批写出", batchSizes.size > 1)
    }

    @Test
    fun windowsLineEndingsAreHandled() = runBlocking {
        val target = file("crlf.txt", "第一章 A\r\n正文A\r\n第二章 B\r\n正文B\r\n".toByteArray())
        val drafts = mutableListOf<TxtImporter.Draft>()
        TxtImporter.import(target) { drafts += it }
        assertEquals(2, drafts.size)
        assertTrue(!drafts[0].title.contains("\r"))
        assertTrue(!drafts[0].html.contains("\r"))
    }

    @Test
    fun blankLinesInsideChapterAreDroppedFromHtml() {
        val html = TxtImporter.toHtml("a\n\n\nb")
        assertEquals("<div class=\"txt-body\"><p>a</p><p>b</p></div>", html)
    }

    @Test
    fun htmlIsEscaped() {
        val html = TxtImporter.toHtml("<script>alert(1)</script>")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    @Test
    fun splitApiStillWorksForSmallText() {
        val drafts = TxtImporter.split("第一章 甲\n内容一\n第二章 乙\n内容二")
        assertEquals(listOf("第一章 甲", "第二章 乙"), drafts.map { it.title })
    }
}
