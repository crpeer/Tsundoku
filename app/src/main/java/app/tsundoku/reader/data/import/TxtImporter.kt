package app.tsundoku.reader.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 纯文本导入：识别编码 + 按「第X章 / Chapter N」切分章节。
 *
 * 为支持大文件，这里不再 `readBytes()` 整本读入：
 * - 编码只用**文件头样本**探测（BOM → UTF-8 严格校验 → GBK）
 * - 正文按行流式读取，一次只在内存里保留当前章节
 * - 章节以批次回调出去，由调用方边收边写库
 */
object TxtImporter {

    data class Draft(val title: String, val html: String)

    data class Result(val chapterCount: Int, val charset: Charset)

    /** 探测编码时读取的样本大小。 */
    private const val SAMPLE_BYTES = 64 * 1024

    /** 每批回调多少个章节（避免每章一次写库）。 */
    const val BATCH_SIZE = 20

    private const val MIN_PREFACE_LENGTH = 50

    /** 单行匹配用的章节标题正则（去掉了 MULTILINE，逐行判断）。 */
    private val chapterLine = Regex(
        """^\s*(第\s*[0-9一二三四五六七八九十百千万零两]+\s*[章节回卷篇集话](?:[^\n]{0,40})|Chapter\s+\d+[^\n]{0,40}|卷\s*[0-9一二三四五六七八九十]+[^\n]{0,40})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 流式导入。[sink] 会收到若干批 [Draft]（每批不超过 [BATCH_SIZE] 个）。
     * 返回章节总数与识别出的编码。
     */
    suspend fun import(file: File, sink: suspend (List<Draft>) -> Unit): Result =
        withContext(Dispatchers.IO) {
            if (file.length() == 0L) return@withContext Result(0, Charsets.UTF_8)
            val charset = detectCharset(file)

            var total = 0
            val batch = ArrayList<Draft>(BATCH_SIZE)
            suspend fun flush() {
                if (batch.isEmpty()) return
                val copy = batch.toList()
                batch.clear()
                total += copy.size
                sink(copy)
            }

            BufferedReader(InputStreamReader(FileInputStream(file), charset), 64 * 1024).use { reader ->
                var currentTitle: String? = null
                var body = StringBuilder()
                val preface = StringBuilder()
                var sawHeading = false

                suspend fun emitCurrent() {
                    val title = currentTitle ?: return
                    val html = toHtml(body.toString())
                    body = StringBuilder()
                    if (html.isNotBlank()) {
                        batch += Draft(title, html)
                        if (batch.size >= BATCH_SIZE) flush()
                    }
                }

                while (true) {
                    val rawLine = (reader.readLine() ?: break).removeBom()
                    val line = rawLine.trim()
                    val isHeading = chapterLine.matches(line)
                    if (isHeading) {
                        if (sawHeading) {
                            emitCurrent()
                        } else if (preface.isNotBlank()) {
                            // 第一个标题之前的内容当「前言」
                            val text = preface.toString().trim()
                            if (text.length > MIN_PREFACE_LENGTH) {
                                batch += Draft("前言", toHtml(text))
                                if (batch.size >= BATCH_SIZE) flush()
                            }
                        }
                        sawHeading = true
                        currentTitle = line.replace(Regex("\\s+"), " ")
                        continue
                    }
                    if (sawHeading) body.append(rawLine).append('\n') else preface.append(rawLine).append('\n')
                }

                if (sawHeading) {
                    emitCurrent()
                } else {
                    // 没有章节标记：整本作为「正文」（此时必然只有一章）
                    val text = preface.toString().trim()
                    if (text.isNotEmpty()) batch += Draft("正文", toHtml(text))
                }
                flush()
            }
            Result(total, charset)
        }

    /** 只在内存里处理一段文本（旧接口，供测试/小文件使用）。 */
    fun split(text: String): List<Draft> {
        if (text.isEmpty()) return emptyList()
        val drafts = mutableListOf<Draft>()
        var title: String? = null
        val body = StringBuilder()
        val preface = StringBuilder()
        var sawHeading = false

        fun emit() {
            val current = title ?: return
            val html = toHtml(body.toString())
            body.setLength(0)
            if (html.isNotBlank()) drafts += Draft(current, html)
        }

        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLineUntrimmed ->
            val rawLine = rawLineUntrimmed.removeBom()
            val line = rawLine.trim()
            if (chapterLine.matches(line)) {
                if (sawHeading) {
                    emit()
                } else {
                    val pre = preface.toString().trim()
                    if (pre.length > MIN_PREFACE_LENGTH) drafts += Draft("前言", toHtml(pre))
                }
                sawHeading = true
                title = line.replace(Regex("\\s+"), " ")
            } else if (sawHeading) {
                body.append(rawLine).append('\n')
            } else {
                preface.append(rawLine).append('\n')
            }
        }
        if (sawHeading) emit() else {
            val only = preface.toString().trim()
            if (only.isNotEmpty()) drafts += Draft("正文", toHtml(only))
        }
        return drafts
    }

    /** 文件头样本探测编码：BOM → 严格 UTF-8 → GBK → Latin-1 兜底。 */
    fun detectCharset(file: File): Charset {
        val sample = readSample(file)
        if (sample.size >= 3 &&
            sample[0] == 0xEF.toByte() &&
            sample[1] == 0xBB.toByte() &&
            sample[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (looksLikeUtf8(sample)) return Charsets.UTF_8
        return runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1)
    }

    private fun readSample(file: File): ByteArray = runCatching {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(SAMPLE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    /**
     * 样本按 UTF-8 严格解码是否成功。
     * 样本若被截断，末尾可能留下不完整的多字节字符——去掉末尾最多 3 字节再试。
     */
    private fun looksLikeUtf8(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        val attempts = if (sample.size >= SAMPLE_BYTES) 4 else 1
        for (trim in 0 until attempts) {
            val length = sample.size - trim
            if (length <= 0) break
            if (runCatching { strictDecode(sample, length) }.isSuccess) return true
        }
        return false
    }

    private fun strictDecode(bytes: ByteArray, length: Int) {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
    }

    fun toHtml(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        return buildString {
            append("<div class=\"txt-body\">")
            trimmed.split('\n').forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty()) {
                    append("<p>")
                    append(escape(line))
                    append("</p>")
                }
            }
            append("</div>")
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 去掉行首 BOM（UTF-8 带 BOM 的文件首行会残留 U+FEFF）。 */
    private fun String.removeBom(): String = trimStart('\uFEFF')
}
