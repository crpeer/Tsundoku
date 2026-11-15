package app.tsundoku.reader.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移登记表。
 *
 * 规则：
 * - 每次改表结构都要 `version + 1` 并在这里加一条 Migration。
 * - 不要用 `fallbackToDestructiveMigration()`，缺迁移时宁可让 Room 抛异常，
 *   也不要静默清空用户书架。
 * - 升级前会先把旧库整份备份到 `databases/backup/`（见 [DatabaseBackups]）。
 */
object DatabaseMigrations {

    /** v1 → v2：记录「当前章节内的位置」（滚动偏移或页码）。 */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN lastPositionOffset INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
