package app.tsundoku.reader.data.source.legado

import app.tsundoku.reader.data.local.SourceDao
import app.tsundoku.reader.data.local.SourceEntity
import app.tsundoku.reader.domain.model.LegadoSourceCandidate
import app.tsundoku.reader.domain.model.SourceConfigs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 导入 Legado 书源 JSON（单个对象或数组）。
 *
 * 原始 JSON 原样存进 `sources.config`，所以以后补规则支持不用重新导入。
 */
class LegadoImporter(private val sourceDao: SourceDao) {

    fun parse(json: String): List<LegadoSourceCandidate> {
        val root = runCatching { SourceConfigs.json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("bookSourceName") ?: return@mapNotNull null
            LegadoSourceCandidate(
                name = name,
                group = obj.string("bookSourceGroup").orEmpty(),
                baseUrl = obj.string("bookSourceUrl").orEmpty(),
                sourceType = obj.string("bookSourceType")?.toIntOrNull() ?: 0,
                configJson = obj.toString(),
            )
        }.filter { it.baseUrl.isNotBlank() }
    }

    /** 已存在同名的源会跳过。 */
    suspend fun install(candidates: List<LegadoSourceCandidate>): Int {
        if (candidates.isEmpty()) return 0
        val existing = sourceDao.getAll().map { it.name }.toMutableSet()
        var added = 0
        for (candidate in candidates) {
            if (!existing.add(candidate.name)) continue
            sourceDao.insert(
                SourceEntity(
                    name = candidate.name,
                    type = "LEGADO",
                    lang = "zh",
                    enabled = true,
                    config = candidate.configJson,
                    iconUrl = null,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            added++
        }
        return added
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
