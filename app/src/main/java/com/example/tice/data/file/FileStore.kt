package com.example.tice.data.file

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 应用私有目录：原始 Excel 模板字节、导出表、备份包。 */
class FileStore(context: Context) {
    private val root: File = context.filesDir
    private val template = File(root, "template.xlsx")
    private val exportsDir = File(root, "exports").apply { mkdirs() }
    private val backupsDir = File(root, "backups").apply { mkdirs() }

    fun hasTemplate(): Boolean = template.isFile && template.length() > 0
    fun templateFile(): File = template
    fun templateBytes(): ByteArray = template.readBytes()

    fun saveTemplate(bytes: ByteArray) {
        val tmp = File(root, "template.xlsx.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), template.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun newExportFile(displayName: String): File {
        val safe = displayName.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
        return File(exportsDir, safe)
    }

    fun backupFiles(): List<File> =
        backupsDir.listFiles { _, name -> name.endsWith(".zip") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun newBackupFile(): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())
        return File(backupsDir, "tice-backup-$stamp.zip")
    }

    fun writeBackup(bytes: ByteArray): File {
        val file = newBackupFile()
        file.writeBytes(bytes)
        return file
    }

    fun backupSizeLabel(): String {
        val size = backupsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return if (size >= 1024 * 1024) "${size / 1024 / 1024} MB" else "${size / 1024} KB"
    }
}
