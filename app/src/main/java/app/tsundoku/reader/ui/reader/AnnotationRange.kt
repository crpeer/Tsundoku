package app.tsundoku.reader.ui.reader

/**
 * 一条高亮在正文里的范围：文本节点的路径 + 偏移，与 DOM `Range` 一一对应。
 *
 * 边界点沿用 DOM 的 (node, offset) 语义，**节点可能是 Text 也可能是 Element**：
 * - Text 节点：`offset` 是字符下标
 * - Element 节点：`offset` 是子节点下标（`range.startContainer` 在「选中整段 / 跨块选择」时
 *   以及 `caretRangeFromPoint()` 落在文字之外时都会给出 Element 容器）
 *
 * [startOffset] 包含、[endOffset] 不包含（与 DOM Range 语义一致）。
 */
data class AnnotationRange(
    val id: String,
    val startPath: String,
    val startOffset: Int,
    val endPath: String,
    val endOffset: Int,
    val color: Int,
)

/**
 * 判断某次点击落在哪条高亮里。
 *
 * 原来的实现用的是 `Range.intersectsNode(文本节点)`，那只回答「这个节点是否与高亮范围有交集」，
 * 于是同一个文本节点里只有部分文字被高亮时，点未高亮的字也会弹出高亮弹窗。
 * 这里改成比较「点击处的 (节点路径, 偏移)」是否落在 `[start, end)` 区间内，
 * 与高亮范围用的是同一套坐标（都由内嵌脚本的 `pathOf()` 生成）。
 */
object AnnotationHitTest {

    /**
     * @param ranges 当前章节的批注，顺序即优先级（重叠时取第一条）
     * @param path 点击位置所在节点的路径（脚本 `pathOf()` 的结果，可能是 Text 也可能是 Element）；
     *   **空串表示 `document.body` 本身**，也就是根节点边界，是合法位置
     * @param offset 点击位置在该节点内的偏移：Text 是字符下标，Element 是子节点下标；
     *   `< 0` 是脚本「拿不到 caret」的无效标记（`caretRangeFromPoint()` 失败时脚本传 -1），
     *   用它来区分「位置不可用」与「合法的根节点边界」
     */
    fun hit(ranges: List<AnnotationRange>, path: String, offset: Int): AnnotationRange? {
        // path 允许为空：那表示 body 根节点；只有 offset < 0 这个哨兵代表位置不可用
        if (ranges.isEmpty() || offset < 0) return null
        val point = parsePath(path) ?: return null
        for (range in ranges) {
            val start = parsePath(range.startPath) ?: continue
            val end = parsePath(range.endPath) ?: continue
            val afterStart = compare(point, offset, start, range.startOffset)
            val beforeEnd = compare(point, offset, end, range.endOffset)
            if (afterStart >= 0 && beforeEnd < 0) return range
        }
        return null
    }

    /**
     * 比较两个边界点 (p, pOffset) 与 (q, qOffset) 的先后：负数表示 p 在前，正数在后。
     *
     * 必须按 DOM 规范「position of a boundary point」来算，**不能**只比路径长短：
     * 父节点 `[0]` 的 `offset = 5` 表示「第 5 个子节点之前」，
     * 它可能已经排在后代 `[0,0]` 之后；仅凭 `p.size < q.size` 会得出相反结论。
     *
     * 规则（与规范「boundary point is before」一致）：
     * 1. 同一节点 → 比 offset。
     * 2. 两个路径在某一层分叉 → 比该层的子节点下标（文档序）。
     * 3. p 是 q 的祖先 → **before 当且仅当** `pOffset <= q[p.size]`（相等算前）。
     * 4. q 是 p 的祖先 → **before 当且仅当** `p[q.size] < qOffset`（相等算后）。
     *
     * 第 3、4 条的不对称是规范本来的写法：`(parent, k)` 与 `(child_k, 0)` 是同一个位置，
     * 规范在祖先一侧算「前」、在后代一侧算「后」。
     */
    fun compare(p: List<Int>, pOffset: Int, q: List<Int>, qOffset: Int): Int {
        val shared = minOf(p.size, q.size)
        for (i in 0 until shared) {
            if (p[i] != q[i]) return p[i].compareTo(q[i])
        }
        if (p.size != q.size) {
            return if (p.size < q.size) {
                // p 是祖先：p 的位置是「第 pOffset 个子节点之前」
                if (pOffset <= q[p.size]) -1 else 1
            } else {
                // q 是祖先：p 所在的子节点下标是 p[q.size]
                if (p[q.size] < qOffset) -1 else 1
            }
        }
        return pOffset.compareTo(qOffset)
    }

    /**
     * 把 `"0/1/3"` 解析成下标数组；格式非法返回 null。
     *
     * 空串解析成空数组 —— 即 `document.body` 根节点，与 `pathOf(document.body)` 的返回值一致。
     */
    fun parsePath(path: String): List<Int>? {
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        val out = ArrayList<Int>(parts.size)
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value < 0) return null
            out.add(value)
        }
        return out
    }
}

/**
 * 生成交给内嵌脚本渲染高亮用的 JSON。
 *
 * 颜色只取 0..3：脚本按 `color % 4` 分组，对应 `::highlight(tsu-hl-N)`。
 */
fun List<AnnotationRange>.toHighlightJson(): String =
    joinToString(prefix = "[", postfix = "]") { a ->
        buildString {
            append("{\"id\":").append(jsonQuote(a.id))
            append(",\"sp\":").append(jsonQuote(a.startPath))
            append(",\"so\":").append(a.startOffset)
            append(",\"ep\":").append(jsonQuote(a.endPath))
            append(",\"eo\":").append(a.endOffset)
            append(",\"color\":").append(a.color)
            append('}')
        }
    }

/** 双引号 JSON 字符串字面量。 */
internal fun jsonQuote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append(String.format(java.util.Locale.US, "\\u%04x", ch.code)) else append(ch)
        }
    }
    append('"')
}
