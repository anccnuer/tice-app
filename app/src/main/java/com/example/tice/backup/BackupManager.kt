package com.example.tice.backup

import com.example.tice.data.db.TiceDao
import com.example.tice.data.file.FileStore
import java.io.File
import java.io.InputStream

data class RestoreResult(
    val studentCount: Int,
    val scoreCount: Int,
    /** 恢复前的回滚快照是否成功生成；未生成时由界面提示教师手动留底。 */
    val snapshotCreated: Boolean = true
)

/** 备份编排：从 Room 取数交给 [BackupCodec] 打包，恢复则先校验再单事务覆盖。 */
class BackupManager(
    private val dao: TiceDao,
    private val store: FileStore
) {
    companion object {
        val VERSION = BackupCodec.VERSION
    }

    suspend fun createBackupZip(): File {
        val payload = BackupCodec.Payload(
            students = dao.allStudents(),
            scores = dao.allScores(),
            rules = dao.rulesOnce(),
            metaRows = dao.allMeta(),
            session = dao.session(),
            templateBytes = if (store.hasTemplate()) store.templateFile().readBytes() else ByteArray(0)
        )
        return store.writeBackup(BackupCodec.encode(payload))
    }

    /** 校验版本与 SHA-256 后单事务覆盖恢复；恢复前尽量留一份回滚快照。 */
    suspend fun restore(input: InputStream): RestoreResult {
        val payload = BackupCodec.decode(input.readBytes())

        // 快照失败不再静默：恢复仍会继续（Room 事务保证失败可回滚），但把结果带回界面提示。
        val snapshotCreated = if (store.hasTemplate() && dao.allStudents().isNotEmpty()) {
            runCatching { createBackupZip() }.isSuccess
        } else {
            true
        }
        if (payload.templateBytes.isNotEmpty()) store.saveTemplate(payload.templateBytes)
        dao.restoreAll(payload.students, payload.scores, payload.rules, payload.metaRows, payload.session)
        return RestoreResult(payload.students.size, payload.scores.size, snapshotCreated)
    }
}
