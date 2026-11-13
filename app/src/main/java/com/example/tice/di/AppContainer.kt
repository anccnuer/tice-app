package com.example.tice.di

import android.content.Context
import com.example.tice.backup.BackupManager
import com.example.tice.data.db.TiceDatabase
import com.example.tice.data.file.FileStore
import com.example.tice.data.net.XlsxDownloader
import com.example.tice.data.repo.ExportRepository
import com.example.tice.data.repo.TestRepository
import com.example.tice.data.repo.WorkbookRepository

/** 手工依赖容器，避免引入 Hilt 注解处理，命令行构建更轻。 */
class AppContainer(context: Context) {
    private val database = TiceDatabase.build(context)
    val dao = database.dao()
    val fileStore = FileStore(context)
    val workbookRepository = WorkbookRepository(dao, fileStore, XlsxDownloader())
    val testRepository = TestRepository(dao)
    val exportRepository = ExportRepository(dao, fileStore)
    val backupManager = BackupManager(dao, fileStore)
}
