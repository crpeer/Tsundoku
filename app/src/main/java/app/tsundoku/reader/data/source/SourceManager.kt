package app.tsundoku.reader.data.source

import app.tsundoku.reader.data.local.SourceDao
import app.tsundoku.reader.data.local.toEntity
import app.tsundoku.reader.data.local.toModel
import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.source.legado.LegadoSource
import app.tsundoku.reader.data.source.opds.OpdsSource
import app.tsundoku.reader.data.source.rss.RssSource
import app.tsundoku.reader.data.source.web.WebSource
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.LegadoSourceConfig
import app.tsundoku.reader.domain.model.OpdsConfig
import app.tsundoku.reader.domain.model.RssConfig
import app.tsundoku.reader.domain.model.SourceConfigs
import app.tsundoku.reader.domain.model.SourceType
import app.tsundoku.reader.domain.model.WebSourceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString

/** 管理已安装的来源，并按类型构造 [BookSource] 实例。 */
class SourceManager(
    private val sourceDao: SourceDao,
    private val network: NetworkClient,
) {

    val sources: Flow<List<BookSourceInfo>> =
        sourceDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun enabledSources(): List<BookSourceInfo> =
        sourceDao.getEnabled().map { it.toModel() }

    suspend fun add(info: BookSourceInfo): Long = sourceDao.insert(info.toEntity())

    suspend fun update(info: BookSourceInfo) = sourceDao.update(info.toEntity())

    suspend fun delete(id: Long) = sourceDao.delete(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    suspend fun isEmpty(): Boolean = sourceDao.count() == 0

    suspend fun sourceById(id: Long): BookSource? =
        sourceDao.get(id)?.toModel()?.let { build(it) }

    fun build(info: BookSourceInfo): BookSource? = when (info.type) {
        SourceType.WEB -> runCatching {
            WebSource(info, SourceConfigs.json.decodeFromString<WebSourceConfig>(info.config), network)
        }.getOrNull()

        SourceType.LEGADO -> runCatching {
            LegadoSource(info, SourceConfigs.json.decodeFromString<LegadoSourceConfig>(info.config), network)
        }.getOrNull()

        SourceType.OPDS -> runCatching {
            OpdsSource(info, SourceConfigs.json.decodeFromString<OpdsConfig>(info.config), network)
        }.getOrNull()

        SourceType.RSS -> runCatching {
            RssSource(info, SourceConfigs.json.decodeFromString<RssConfig>(info.config), network)
        }.getOrNull()

        SourceType.LOCAL -> null
    }
}
