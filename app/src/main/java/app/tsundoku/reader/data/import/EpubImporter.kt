package app.tsundoku.reader.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 导入：把整本解包到私有目录，章节直接以本地 XHTML 文件路径保存，
 * 这样图片、CSS、脚注等资源都能被 WebView 正常加载。
 *
 * 安全约束（都是硬上限，超限直接失败并清理目标目录）：
 * - 条目名必须是相对路径，且规范化后仍位于目标目录内（防 zip-slip）
 * - 条目数 ≤ [MAX_ENTRIES]
 * - 单个条目 ≤ [MAX_ENTRY_BYTES]
 * - 解压总量 ≤ [MAX_TOTAL_BYTES]（防压缩炸弹）
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
        try {
            extractInternal(epubFile, destDir)
        } catch (error: Throwable) {
            // 失败时不留半成品，避免下次导入撞到脏目录
            destDir.deleteRecursively()
            throw error
        }
    }

    private fun extractInternal(epubFile: File, destDir: File): Result {
        destDir.mkdirs()
        unzip(epubFile, destDir)

        val container = File(destDir, "META-INF/container.xml")
        if (!container.exists()) throw IOException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val containerDoc = Jsoup.parse(container.readText(), "", Parser.xmlParser())
        val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")
        if (opfPath.isNullOrBlank()) throw IOException("不是有效的 EPUB：container.xml 缺少 rootfile")

        val opfFile = resolveInside(destDir, opfPath)
        if (!opfFile.exists()) throw IOException("EPUB 缺少 OPF：$opfPath")
        val opf = Jsoup.parse(opfFile.readText(), "", Parser.xmlParser())
        val opfDir = opfPath.substringBeforeLast('/', "")

        val rawTitle = firstText(opf, "dc:title", "title")
        val title = sanitizeTitle(rawTitle, epubFile)
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
            ?: opf.select("manifest > item")
                .firstOrNull { it.attr("media-type").startsWith("image/") }
                ?.attr("href")
        val coverFile = coverHref?.let { href ->
            runCatching { resolveInside(destDir, opfDir, href) }.getOrNull()?.takeIf { it.exists() }
        }

        // linear="no" 的条目（脚注、版权页）默认不进阅读顺序；若全书都是 no，则全部保留。
        val spineItems = opf.select("spine > itemref")
        val linearItems = spineItems.filter { !it.attr("linear").equals("no", ignoreCase = true) }
        val ordered = linearItems.ifEmpty { spineItems }

        val chapters = ordered.mapIndexedNotNull { index, itemRef ->
            val idRef = itemRef.attr("idref")
            val href = manifest[idRef] ?: return@mapIndexedNotNull null
            val file = runCatching { resolveInside(destDir, opfDir, href) }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (!file.exists()) return@mapIndexedNotNull null
            val label = chapterTitle(file, index + 1) ?: file.nameWithoutExtension
            ChapterFile(title = label, path = file.absolutePath)
        }

        return Result(
            title = title,
            author = author,
            description = description,
            coverFile = coverFile,
            chapters = chapters,
        )
    }

    /**
     * 章节标题：`<title>` 可用就用；纯数字/单字符/占位的，退到正文标题标签，
     * 都不可用时给「第 N 章」——比把正文首句当标题稳定得多。
     */
    private fun chapterTitle(file: File, ordinal: Int): String? = runCatching {
        val doc = Jsoup.parse(file.readText())
        val fromTitle = doc.selectFirst("title")?.text()?.trim().orEmpty()
        if (isMeaningfulTitle(fromTitle)) return@runCatching fromTitle

        val fromHeading = doc.selectFirst("h1, h2, h3, h4")?.text()?.trim().orEmpty()
        if (isMeaningfulTitle(fromHeading)) return@runCatching fromHeading

        "第 $ordinal 章"
    }.getOrNull()

    /** 纯数字、纯分隔符、单字符都算「不像标题」。注意不能用 \W，Java 里它对中文恒为真。 */
    private fun isMeaningfulTitle(value: String): Boolean =
        value.length >= 2 && !JUNK_TITLE.matches(value)

    /**
     * 书名兜底：`dc:title` 是纯数字/与文件名相同/明显占位时，用文件名（去掉扩展名与分隔符）。
     */
    private fun sanitizeTitle(raw: String?, epubFile: File): String {
        val fromFile = epubFile.nameWithoutExtension
            .replace(Regex("""[+_\s]+"""), " ")
            .trim()
        val title = raw?.trim().orEmpty()
        val looksJunk = title.isEmpty() ||
            title.length < 2 ||
            JUNK_TITLE.matches(title) ||
            title.equals(fromFile, ignoreCase = true) ||
            title.contains("untitled", ignoreCase = true) ||
            title.contains("未命名") ||
            title.contains("无标题")
        return if (looksJunk) fromFile.ifBlank { "未命名书籍" } else title
    }

    /** OPF 中的元数据标签带命名空间前缀（dc:title），按标签名逐个尝试。 */
    private fun firstText(root: Element, vararg tags: String): String? {
        for (tag in tags) {
            val text = runCatching {
                root.getElementsByTag(tag).firstOrNull()?.text()?.trim()
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun unzip(archive: File, destDir: File) {
        val root: Path = destDir.canonicalFile.toPath()
        var totalBytes = 0L
        var entryCount = 0

        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (++entryCount > MAX_ENTRIES) {
                    throw IOException("EPUB 条目过多（> $MAX_ENTRIES），已中止导入")
                }
                if (entry.isDirectory) continue

                val name = entry.name.replace('\\', '/')
                if (isAbsoluteEntry(name)) throw IOException("非法的 EPUB 条目：${entry.name}")

                val target = root.resolve(name).normalize()
                if (!target.startsWith(root)) throw IOException("非法的 EPUB 条目：${entry.name}")
                if (entry.size > MAX_ENTRY_BYTES) {
                    throw IOException("EPUB 条目过大：${entry.name}")
                }

                val file = target.toFile()
                file.parentFile?.mkdirs()
                val written = zip.getInputStream(entry).use { input ->
                    file.outputStream().use { output -> copyLimited(input, output, entry.name) }
                }
                totalBytes += written
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw IOException("EPUB 解压后体积超限（> ${MAX_TOTAL_BYTES / 1024 / 1024} MB），已中止导入")
                }
            }
        }
    }

    /** 流式拷贝并限制单条大小，防止 zip 头里谎报 size 的炸弹。 */
    private fun copyLimited(input: InputStream, output: OutputStream, name: String): Long {
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            written += read
            if (written > MAX_ENTRY_BYTES) throw IOException("EPUB 条目过大：$name")
            output.write(buffer, 0, read)
        }
        return written
    }

    private fun isAbsoluteEntry(name: String): Boolean =
        name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)

    private fun resolveInside(root: File, href: String): File = resolveInside(root, "", href)

    private fun resolveInside(root: File, baseDir: String, href: String): File {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val rootPath: Path = root.canonicalFile.toPath()
        val candidate = rootPath.resolve(baseDir).resolve(clean).normalize()
        if (!candidate.startsWith(rootPath)) throw IOException("非法的 EPUB 路径：$href")
        return candidate.toFile()
    }

    companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

        /** 只有数字 + 常见分隔符（不含字母/汉字）才算垃圾标题。 */
        private val JUNK_TITLE = Regex("""^[\d\s+_.\-]+$""")
    }
}
