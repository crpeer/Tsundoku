package app.tsundoku.reader.di

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import app.tsundoku.reader.data.import.BookImporter
import app.tsundoku.reader.data.local.AppDatabase
import app.tsundoku.reader.data.local.DatabaseBackups
import app.tsundoku.reader.data.local.DatabaseMigrations
import app.tsundoku.reader.data.net.NetworkClient
import app.tsundoku.reader.data.prefs.SettingsRepository
import app.tsundoku.reader.data.repo.AnnotationRepository
import app.tsundoku.reader.data.repo.LibraryRepository
import app.tsundoku.reader.data.repo.UpdatesRepository
import app.tsundoku.reader.data.source.SourceManager
import app.tsundoku.reader.data.source.SourceDiagnostics
import app.tsundoku.reader.data.source.legado.LegadoImporter
import app.tsundoku.reader.data.storage.BookStorage
import app.tsundoku.reader.data.storage.MetadataSyncer
import app.tsundoku.reader.domain.model.BookSourceInfo
import app.tsundoku.reader.domain.model.RssConfig
import app.tsundoku.reader.domain.model.SourceConfigs
import app.tsundoku.reader.domain.model.SourceType
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 手写依赖容器。比 Hilt/Koin 更轻，编译更快，也更容易看懂。
 */
class AppContainer(private val app: Application) {

    private val database: AppDatabase = buildDatabase()

    private fun buildDatabase(): AppDatabase {
        val dbFile = app.getDatabasePath(DB_NAME)
        // 升级前先备份；全新安装时 existingVersion 为 0，不会产生备份。
        runCatching {
            val existingVersion = readVersion(dbFile)
            DatabaseBackups.backupIfUpgrading(
                dbFile = dbFile,
                existingVersion = existingVersion,
                targetVersion = AppDatabase.VERSION,
                backupDir = File(dbFile.parentFile, "backup"),
            )
        }
        return Room.databaseBuilder(app, AppDatabase::class.java, DB_NAME)
            // 缺迁移时抛异常（而不是清库）。新增表结构时必须同步补 MIGRATION。
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    private fun readVersion(dbFile: File): Int {
        if (!dbFile.exists() || dbFile.length() == 0L) return 0
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrDefault(0)
    }

    val network = NetworkClient()

    /** 应用级作用域：ViewModel 被销毁后仍能完成最后一次落盘。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val library = LibraryRepository(
        database.bookDao(),
        database.chapterDao(),
        transaction = { block -> database.withTransaction { block() } },
    )
    val sources = SourceManager(database.sourceDao(), network)
    val settings = SettingsRepository(app)
    val storage = BookStorage(app)
    val annotations = AnnotationRepository(database.annotationDao(), database.bookmarkDao())
    val meta = MetadataSyncer(storage, library, settings, annotations)
    val importer = BookImporter(app, library, network, storage, meta)
    val legadoImporter = LegadoImporter(database.sourceDao())
    val updates = UpdatesRepository(library, sources)
    val diagnostics = SourceDiagnostics(sources)

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

    private companion object {
        const val DB_NAME = "tsundoku.db"
    }
}
