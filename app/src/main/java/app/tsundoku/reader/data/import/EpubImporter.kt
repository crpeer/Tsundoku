package app.tsundoku.reader.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 导入：把整本解包到私有目录，章节直接以本地 XHTML 文件路径保存，
 * 这样图片、CSS、脚注等资源都能被 WebView 正常加载。
 */
class EpubImporter {

    data class ChapterFile(val title: String, val path: String)

    data class Result(
        val title: String,
        val author: String?,
        val description: String?,
        val coverFile: File?,
        val chapters: List<ChapterFile>,
    )

    suspend fun extract(epubFile: File, destDir: File): Result = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        unzip(epubFile, destDir)

        val container = File(destDir, "META-INF/container.xml")
        val opfPath = if (container.exists()) {
            val doc = org.jsoup.Jsoup.parse(container.readText(), "", org.jsoup.parser.Parser.xmlParser())
            doc.selectFirst("rootfile")?.attr("full-path")
        } else null
        if (opfPath.isNullOrBlank()) throw IOException("不是有效的 EPUB：缺少 container.xml")

        val opfFile = File(destDir, opfPath)
        if (!opfFile.exists()) throw IOException("EPUB 缺少 OPF：$opfPath")
        val opf = org.jsoup.Jsoup.parse(opfFile.readText(), "", org.jsoup.parser.Parser.xmlParser())
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = firstText(opf, "dc:title", "title") ?: epubFile.nameWithoutExtension
        val author = firstText(opf, "dc:creator", "creator")
        val description = firstText(opf, "dc:description", "description")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val manifest = mutableMapOf<String, String>()
        opf.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
        }

        val coverId = opf.selectFirst("metadata > meta[name=cover]")?.attr("content")
        val coverHref = coverId?.let { manifest[it] }
            ?: opf.select("manifest > item")
                .firstOrNull { it.attr("properties").contains("cover-image") }
                ?.attr("href")
        val coverFile = coverHref?.let { href ->
            runCatching { resolveInside(destDir, opfDir, href) }.getOrNull()?.takeIf { it.exists() }
        }

        val chapters = opf.select("spine > itemref").mapNotNull { itemRef ->
            val idRef = itemRef.attr("idref")
            val href = manifest[idRef] ?: return@mapNotNull null
            val file = runCatching { resolveInside(destDir, opfDir, href) }.getOrNull()
                ?: return@mapNotNull null
            if (!file.exists()) return@mapNotNull null
            val label = readChapterTitle(file) ?: file.nameWithoutExtension
            ChapterFile(title = label, path = file.absolutePath)
        }

        Result(
            title = title,
            author = author,
            description = description,
            coverFile = coverFile,
            chapters = chapters,
        )
    }

    private fun readChapterTitle(file: File): String? = runCatching {
        val html = file.readText()
        org.jsoup.Jsoup.parse(html).selectFirst("title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** OPF 中的元数据标签带命名空间前缀（dc:title），按标签名逐个尝试。 */
    private fun firstText(root: org.jsoup.nodes.Element, vararg tags: String): String? {
        for (tag in tags) {
            val text = runCatching {
                root.getElementsByTag(tag).firstOrNull()?.text()?.trim()
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun unzip(archive: File, destDir: File) {
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = File(destDir, entry.name).canonicalFile
                if (!target.path.startsWith(destDir.canonicalPath)) {
                    throw IOException("非法的 EPUB 条目：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun resolveInside(root: File, baseDir: String, href: String): File {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val candidate = File(File(root, baseDir), clean).canonicalFile
        if (!candidate.path.startsWith(root.canonicalPath)) {
            throw IOException("非法的 EPUB 路径：$href")
        }
        return candidate
    }
}
