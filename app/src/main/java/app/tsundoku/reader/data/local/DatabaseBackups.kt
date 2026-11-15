package app.tsundoku.reader.data.local

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据库升级前的备份。
 *
 * 这是纯文件逻辑（不依赖 Android），方便单元测试：
 * 只要「现有版本」与「目标版本」不一致就把整份数据库复制一份到 `backup/`，
 * 迁移失败时用户至少还能手工救回来。
 */
object DatabaseBackups {

    /** 最多保留的备份份数，超出后删最旧的。 */
    const val MAX_BACKUPS = 5

    /**
     * 需要升级时备份 [dbFile]，返回备份文件；无需备份（全新安装/版本相同）时返回 null。
     */
    fun backupIfUpgrading(
        dbFile: File,
        existingVersion: Int,
        targetVersion: Int,
        backupDir: File,
        keep: Int = MAX_BACKUPS,
        timestamp: Long = System.currentTimeMillis(),
    ): File? {
        if (existingVersion <= 0) return null
        if (existingVersion == targetVersion) return null
        if (!dbFile.exists() || dbFile.length() == 0L) return null

        backupDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
        val name = "${dbFile.nameWithoutExtension}-v$existingVersion-$stamp.db"
        val target = File(backupDir, name)

        // SQLite 可能有 -wal/-shm 侧车文件；正式备份前先合并，避免备份只有一半数据。
        dbFile.copyTo(target, overwrite = true)
        copySidecar(dbFile, "-wal", target)
        copySidecar(dbFile, "-shm", target)

        prune(backupDir, keep)
        return target
    }

    /** 只保留最近 [keep] 份备份。 */
    fun prune(backupDir: File, keep: Int) {
        if (keep <= 0) return
        val backups = backupDir.listFiles { file -> file.isFile && file.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        backups.drop(keep).forEach { runCatching { it.delete() } }
    }

    private fun copySidecar(dbFile: File, suffix: String, target: File) {
        val sidecar = File(dbFile.absolutePath + suffix)
        if (!sidecar.exists()) return
        runCatching { sidecar.copyTo(File(target.absolutePath + suffix), overwrite = true) }
    }
}
