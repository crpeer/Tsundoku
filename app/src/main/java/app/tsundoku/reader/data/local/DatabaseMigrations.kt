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

    /** v2 → v3：批注（高亮/笔记）与书签。 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `annotations` (
                    `id` TEXT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `chapterTitle` TEXT NOT NULL,
                    `startPath` TEXT NOT NULL,
                    `startOffset` INTEGER NOT NULL,
                    `endPath` TEXT NOT NULL,
                    `endOffset` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `color` INTEGER NOT NULL,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotations_bookId` ON `annotations` (`bookId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_annotations_bookId_chapterIndex` " +
                    "ON `annotations` (`bookId`, `chapterIndex`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bookmarks` (
                    `id` TEXT NOT NULL,
                    `bookId` INTEGER NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `chapterTitle` TEXT NOT NULL,
                    `offset` INTEGER NOT NULL,
                    `ratio` REAL NOT NULL,
                    `excerpt` TEXT,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId_chapterIndex` " +
                    "ON `bookmarks` (`bookId`, `chapterIndex`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
