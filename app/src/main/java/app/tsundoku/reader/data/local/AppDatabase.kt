package app.tsundoku.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, SourceEntity::class, AnnotationEntity::class, BookmarkEntity::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun sourceDao(): SourceDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** 改表结构时 +1，并在 [DatabaseMigrations] 里补上对应迁移。 */
        const val VERSION = 3
    }
}
