package app.tsundoku.reader

import app.tsundoku.reader.data.import.EpubImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB 解包的安全性：路径穿越、条目/体积上限、失败清理。 */
class EpubImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("book-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun validEpub(extra: (ZipOutputStream) -> Unit = {}): File = zip("mimetype" to "application/epub+zip").let { _ ->
        val file = temp.newFile("valid-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { out ->
            fun put(name: String, body: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container><rootfiles>
                   <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                   </rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package version="2.0"><metadata
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <dc:title>Book Title</dc:title><dc:creator>Author</dc:creator>
                   </metadata><manifest>
                   <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                   </manifest><spine><itemref idref="c1"/></spine></package>""",
            )
            put("OEBPS/chapter1.xhtml", "<html><head><title>Chapter One</title></head><body><p>x</p></body></html>")
            extra(out)
        }
        file
    }

    @Test
    fun validEpubIsExtracted() = runBlocking {
        val dest = temp.newFolder("dest-ok")
        val result = EpubImporter().extract(validEpub(), dest)
        assertEquals("Book Title", result.title)
        assertEquals("Author", result.author)
        assertEquals(1, result.chapters.size)
        assertEquals("Chapter One", result.chapters.first().title)
        assertTrue(File(result.chapters.first().path).exists())
    }

    @Test
    fun zipSlipEntryIsRejected() = runBlocking {
        val dest = temp.newFolder("dest-slip")
        val evil = validEpub { out ->
            out.putNextEntry(ZipEntry("../../escape.txt"))
            out.write("pwned".toByteArray())
            out.closeEntry()
        }
        val escaped = File(dest.parentFile, "escape.txt")
        val sibling = File(dest.canonicalPath + "-evil/escape.txt")

        val error = runCatching { EpubImporter().extract(evil, dest) }.exceptionOrNull()
        assertTrue("应当拒绝 ../ 条目，实际：$error", error is IOException)
        assertFalse("不能写出 dest 之外", escaped.exists())
        assertFalse("不能写到同前缀的兄弟目录", sibling.exists())
        // 失败后不应留下半成品目录
        assertFalse("失败后应清理目标目录", dest.exists())
    }

    @Test
    fun absolutePathEntryIsRejected() = runBlocking {
        val dest = temp.newFolder("dest-abs")
        val evil = validEpub { out ->
            out.putNextEntry(ZipEntry("/tmp/abs-escape.txt"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        val error = runCatching { EpubImporter().extract(evil, dest) }.exceptionOrNull()
        assertTrue("应当拒绝绝对路径条目，实际：$error", error is IOException)
    }

    @Test
    fun tooManyEntriesIsRejected() = runBlocking {
        val dest = temp.newFolder("dest-many")
        val evil = validEpub { out ->
            repeat(EpubImporter.MAX_ENTRIES + 10) { index ->
                out.putNextEntry(ZipEntry("filler/$index.txt"))
                out.write("x".toByteArray())
                out.closeEntry()
            }
        }
        val error = runCatching { EpubImporter().extract(evil, dest) }.exceptionOrNull()
        assertTrue("条目过多应报错，实际：$error", error is IOException)
        assertFalse(dest.exists())
    }

    @Test
    fun oversizedEntryIsRejected() = runBlocking {
        val dest = temp.newFolder("dest-big")
        val evil = validEpub { out ->
            out.putNextEntry(ZipEntry("big.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat((EpubImporter.MAX_TOTAL_BYTES / chunk.size).toInt() + 4) {
                out.write(chunk)
            }
            out.closeEntry()
        }
        val error = runCatching { EpubImporter().extract(evil, dest) }.exceptionOrNull()
        assertTrue("解压总量超限应报错，实际：$error", error is IOException)
        assertFalse(dest.exists())
    }

    @Test
    fun notAnEpubFails() = runBlocking {
        val dest = temp.newFolder("dest-bad")
        val broken = zip("hello.txt" to "not an epub")
        val error = runCatching { EpubImporter().extract(broken, dest) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(dest.exists())
    }

    // --- 元数据兜底 ---

    private fun epubWith(
        title: String,
        spine: String = """<itemref idref="c1"/>""",
        chapters: Map<String, String> = mapOf(
            "OEBPS/chapter1.xhtml" to
                "<html><head><title>Chapter One</title></head><body><p>x</p></body></html>",
        ),
        manifestExtra: String = "",
    ): File {
        val file = temp.newFile("book-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { out ->
            fun put(name: String, body: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container><rootfiles>
                   <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                   </rootfiles></container>""",
            )
            val items = chapters.keys.mapIndexed { index, href ->
                """<item id="c${index + 1}" href="${href.substringAfterLast('/')}" media-type="application/xhtml+xml"/>"""
            }.joinToString("")
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package version="2.0"><metadata
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <dc:title>$title</dc:title>
                   </metadata><manifest>$items$manifestExtra</manifest>
                   <spine>$spine</spine></package>""",
            )
            chapters.forEach { (path, body) -> put(path, body) }
        }
        return file
    }

    @Test
    fun numericTitleFallsBackToFileName() = runBlocking {
        val epub = epubWith(title = "12345")
        val dest = temp.newFolder("dest-title")
        val result = EpubImporter().extract(epub, dest)
        // 文件名是 book-<nanos>.epub，所以标题应该来自文件名而不是 "12345"
        assertFalse("纯数字书名应被替换，实际=${result.title}", result.title == "12345")
        assertTrue(result.title.startsWith("book"))
    }

    @Test
    fun numericChapterTitleFallsBackToHeading() = runBlocking {
        val html = "<html><head><title>3</title></head><body><h2>第六卷 序</h2><p>正文</p></body></html>"
        val epub = epubWith(title = "Real Title", chapters = mapOf("OEBPS/chapter1.xhtml" to html))
        val dest = temp.newFolder("dest-ch-title")
        val result = EpubImporter().extract(epub, dest)
        assertEquals("Real Title", result.title)
        assertEquals("第六卷 序", result.chapters.single().title)
    }

    @Test
    fun junkChapterTitlesGetOrdinalNames() = runBlocking {
        val html = "<html><head><title>1</title></head><body><h1>1</h1><p>正文</p></body></html>"
        val epub = epubWith(
            title = "Real Title",
            spine = """<itemref idref="c1"/><itemref idref="c2"/>""",
            chapters = mapOf("OEBPS/a.xhtml" to html, "OEBPS/b.xhtml" to html),
        )
        val dest = temp.newFolder("dest-junk-title")
        val result = EpubImporter().extract(epub, dest)
        assertEquals(listOf("第 1 章", "第 2 章"), result.chapters.map { it.title })
    }

    @Test
    fun nonLinearSpineItemsAreSkipped() = runBlocking {        val one = "<html><head><title>第一章 开始</title></head><body><p>a</p></body></html>"
        val note = "<html><head><title>注释</title></head><body><p>n</p></body></html>"
        val epub = epubWith(
            title = "Book",
            spine = """<itemref idref="c1"/><itemref idref="c2" linear="no"/>""",
            chapters = mapOf("OEBPS/chapter1.xhtml" to one, "OEBPS/notes.xhtml" to note),
        )
        val dest = temp.newFolder("dest-linear")
        val result = EpubImporter().extract(epub, dest)
        assertEquals(1, result.chapters.size)
        assertEquals("第一章 开始", result.chapters.single().title)
    }
}
