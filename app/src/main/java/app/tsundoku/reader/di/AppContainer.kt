package app.tsundoku.reader.di

import android.app.Application
import androidx.room.Room
import app.tsundoku.reader.data.import.BookImporter
import app.tsundoku.reader.data.local.AppDatabase
import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.prefs.SettingsRepository
import app.tsundoku.reader.data.repo.LibraryRepository
import app.tsundoku.reader.data.repo.UpdatesRepository
import app.tsundoku.reader.data.source.SourceManager
import app.tsundoku.reader.data.source.legado.LegadoImporter
import app.tsundoku.reader.data.storage.BookStorage
import app.tsundoku.reader.data.storage.MetadataSyncer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.RssConfig
import app.tsundoku.reader.domain.model.SourceConfigs
import app.tsundoku.reader.domain.model.SourceType
import kotlinx.serialization.encodeToString

/**
 * 手写依赖容器。比 Hilt/Koin 更轻，编译更快，也更容易看懂。
 */
class AppContainer(private val app: Application) {

    private val database: AppDatabase = Room.databaseBuilder(
        app,
        AppDatabase::class.java,
        "tsundoku.db",
    ).fallbackToDestructiveMigration().build()

    val network = NetworkClient()
    val library = LibraryRepository(database.bookDao(), database.chapterDao())
    val sources = SourceManager(database.sourceDao(), network)
    val settings = SettingsRepository(app)
    val storage = BookStorage(app)
    val meta = MetadataSyncer(storage, library, settings)
    val importer = BookImporter(app, library, network, storage, meta)
    val legadoImporter = LegadoImporter(database.sourceDao())
    val updates = UpdatesRepository(library, sources)

    /** 首次启动时放一个可用的示例订阅，避免空界面。 */
    suspend fun seedDemoSourceIfNeeded() {
        if (!sources.isEmpty()) return
        val config = RssConfig(
            name = "示例订阅 · 阮一峰的网络日志",
            feedUrl = "https://www.ruanyifeng.com/blog/atom.xml",
            lang = "zh",
            title = "阮一峰的网络日志",
        )
        sources.add(
            BookSourceInfo(
                id = 0,
                name = "示例订阅 · 阮一峰的网络日志",
                type = SourceType.RSS,
                lang = "zh",
                enabled = true,
                config = SourceConfigs.json.encodeToString(config),
                iconUrl = null,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }
}
