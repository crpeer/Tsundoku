package app.tsundoku.reader.data.import

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 纯文本导入：识别编码 + 按「第X章 / Chapter N」切分章节。 */
object TxtImporter {

    data class Draft(val title: String, val html: String)

    private val chapterRegex = Regex(
        """^\s*(第\s*[0-9一二三四五六七八九十百千万零两]+\s*[章节回卷篇集话](?:[^\n]{0,40})|Chapter\s+\d+[^\n]{0,40}|卷\s*[0-9一二三四五六七八九十]+[^\n]{0,40})\s*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    fun readText(file: File): String {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, charset("GBK")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    fun split(text: String): List<Draft> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val matches = chapterRegex.findAll(normalized).toList()

        if (matches.size < 2) {
            val body = normalized.trim()
            if (body.isEmpty()) return emptyList()
            return listOf(Draft("正文", toHtml(body)))
        }

        val drafts = mutableListOf<Draft>()
        val preface = normalized.substring(0, matches.first().range.first).trim()
        if (preface.length > 50) drafts.add(Draft("前言", toHtml(preface)))

        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else normalized.length
            if (start >= end) return@forEachIndexed
            val title = match.value.trim().replace(Regex("\\s+"), " ")
            val body = normalized.substring(start, end).trim()
            if (body.isEmpty()) return@forEachIndexed
            drafts.add(Draft(title, toHtml(body)))
        }
        if (drafts.isEmpty()) return listOf(Draft("正文", toHtml(normalized)))
        return drafts
    }

    fun toHtml(body: String): String = buildString {
        append("<div class=\"txt-body\">")
        body.split('\n').forEach { rawLine ->
            // 中文 TXT 里常见全角空格和不换行空格，只按 isWhitespace 判断会留下「空段落」
            val line = rawLine.trim { it.isWhitespace() || it == '\u00a0' || it == '\uFEFF' }
            if (line.isNotEmpty()) {
                append("<p>")
                append(escape(line))
                append("</p>")
            }
        }
        append("</div>")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
