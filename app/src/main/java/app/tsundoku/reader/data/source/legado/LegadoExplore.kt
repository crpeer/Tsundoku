package app.tsundoku.reader.data.source.legado

import app.tsundoku.reader.data.source.BrowseCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 解析 Legado 的 `exploreUrl`（发现页分类入口）。
 *
 * 两种常见写法：
 * 1. 纯文本，分组标题单独一行，条目为 `名称::地址`：
 *    ```
 *    玄幻
 *    全部::https://site/list/all/{{page}}.html
 *    排行::https://site/rank/{{page}}.html
 *    ```
 * 2. JSON 数组：`[{"title":"玄幻","url":"...","style":{...}}]`
 *
 * 含 `{{...}}` 以外 JS 表达式或 `@js` 的条目会被跳过——本阶段不支持动态规则。
 */
object LegadoExplore {

    fun parse(raw: String): List<BrowseCategory> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) parseJson(trimmed) else parseText(trimmed)
    }

    private fun parseJson(raw: String): List<BrowseCategory> = runCatching {
        val array = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonArray
            ?: return@runCatching emptyList()
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val url = (obj["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (title.isEmpty() || !isUsable(url)) null else BrowseCategory(title, url)
        }
    }.getOrDefault(emptyList())

    private fun parseText(raw: String): List<BrowseCategory> {
        val result = mutableListOf<BrowseCategory>()
        var group = ""
        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("//")) continue
            if (!text.contains("::")) {
                group = text
                continue
            }
            // 名称或地址里出现 JS 标记的条目本阶段不支持，直接跳过
            if (text.contains("<js>") || text.contains("@js")) continue
            val name = text.substringBefore("::").trim()
            val url = text.substringAfter("::").trim()
            if (name.isEmpty() || !isUsable(url)) continue
            result += BrowseCategory(if (group.isEmpty()) name else "$group · $name", url)
        }
        return result
    }

    /** 只接受 http(s) 地址。 */
    private fun isUsable(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/")
}
