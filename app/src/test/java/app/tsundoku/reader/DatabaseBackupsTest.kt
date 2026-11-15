package app.tsundoku.reader

import app.tsundoku.reader.data.local.DatabaseBackups
import app.tsundoku.reader.data.local.DatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 升级前备份：不能把用户库直接暴露给一次失败迁移。 */
class DatabaseBackupsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun db(name: String = "tsundoku.db", body: String = "payload"): File =
        temp.newFile(name).apply { writeText(body) }

    @Test
    fun noBackupOnFreshInstall() {
        val dbFile = File(temp.root, "tsundoku.db")
        val backupDir = File(temp.root, "backup")
        val result = DatabaseBackups.backupIfUpgrading(dbFile, 0, 2, backupDir)
        assertNull(result)
        assertTrue(!backupDir.exists() || backupDir.listFiles()!!.isEmpty())
    }

    @Test
    fun noBackupWhenVersionUnchanged() {
        val dbFile = db()
        val result = DatabaseBackups.backupIfUpgrading(dbFile, 2, 2, File(temp.root, "backup"))
        assertNull(result)
    }

    @Test
    fun backupOnUpgrade() {
        val dbFile = db(body = "v1-data")
        val backupDir = File(temp.root, "backup")
        val result = DatabaseBackups.backupIfUpgrading(dbFile, 1, 2, backupDir, timestamp = 1_700_000_000_000)
        assertNotNull(result)
        assertTrue(result!!.exists())
        assertEquals("v1-data", result.readText())
        assertTrue(result.name.contains("-v1-"))
    }

    @Test
    fun sidecarFilesAreCopied() {
        val dbFile = db()
        File(dbFile.absolutePath + "-wal").writeText("wal")
        val result = DatabaseBackups.backupIfUpgrading(dbFile, 1, 2, File(temp.root, "backup"))!!
        assertTrue(File(result.absolutePath + "-wal").exists())
    }

    @Test
    fun oldBackupsArePruned() {
        val backupDir = temp.newFolder("backup")
        repeat(8) { index ->
            val file = File(backupDir, "tsundoku-v1-$index.db")
            file.writeText("x")
            file.setLastModified(1_000_000L + index * 1_000)
        }
        DatabaseBackups.prune(backupDir, keep = 3)
        assertEquals(3, backupDir.listFiles()!!.size)
    }

    @Test
    fun migrationRegistryCoversEveryVersionStep() {
        // 从 1 到 AppDatabase.VERSION 的每一步都必须有迁移，否则 Room 会直接抛异常。
        val target = app.tsundoku.reader.data.local.AppDatabase.VERSION
        val byStart = DatabaseMigrations.ALL.associateBy { it.startVersion }
        var version = 1
        while (version < target) {
            val migration = byStart[version]
            assertNotNull("缺少 v$version → v${version + 1} 的迁移", migration)
            assertEquals(version + 1, migration!!.endVersion)
            version++
        }
    }
}
