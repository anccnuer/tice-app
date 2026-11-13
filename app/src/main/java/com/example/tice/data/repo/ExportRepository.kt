package com.example.tice.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.tice.data.db.TiceDao
import com.example.tice.data.file.FileStore
import com.example.tice.xlsx.ExportResult
import com.example.tice.xlsx.XlsxCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

data class ExportOutcome(val result: ExportResult, val file: File)

class ExportRepository(private val dao: TiceDao, private val store: FileStore) {
    private companion object {
        const val TAG = "TiceExport"
    }

    /** [maxSheetBytes] 由 Android 层按设备可用内存算出，避免固定上限在低内存设备上形同虚设。 */
    suspend fun export(
        classIds: Set<String>,
        label: String,
        maxSheetBytes: Long = XlsxCodec.MAX_SHEET_BYTES.toLong()
    ): ExportOutcome = withContext(Dispatchers.IO) {
        require(classIds.isNotEmpty()) { "请先选择要导出的班级" }
        require(store.hasTemplate()) { "尚未导入原始 Excel 模板，无法导出" }
        val students = dao.studentsOfClasses(classIds.toList())
        require(students.isNotEmpty()) { "所选班级没有学生数据" }

        val ids = students.map { it.studentId }.toHashSet()
        // 行列映射来自导入时已落库的快照，导出时无需再解析整张工作表
        val rowOfStudent = students.associate { it.studentId to it.sourceRow }
        val projects = dao.rulesOnce().map { it.key to it.column }
        // 一次查询取全部成绩再过滤，避免逐行查库与 SQLite IN 参数上限
        val scores = dao.allScores().filter { it.studentId in ids }
            .groupBy { it.studentId }
            .mapValues { (_, list) -> list.associate { it.projectKey to it.value } }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
        val file = store.newExportFile("体测_${label}_$stamp.xlsx")
        val template = store.templateFile()
        val startedAt = System.currentTimeMillis()
        // 只记体积与耗时，不记姓名、学籍号或工作表内容，便于在模拟器上对照 am_pss / meminfo
        Log.d(
            TAG,
            "begin template=${template.length()}B sheetDeclared=${declaredSheetBytes(template)}B " +
                "budget=${maxSheetBytes}B classes=${classIds.size}"
        )
        val result = try {
            FileOutputStream(file).use { out ->
                XlsxCodec.export(template, out, rowOfStudent, projects, scores, maxSheetBytes)
            }
        } catch (e: Throwable) {
            // 生成中断只留下半成品，既占空间又会干扰下次分享，直接删掉
            file.delete()
            Log.w(TAG, "aborted: ${e.javaClass.simpleName}")
            throw e
        }
        Log.d(
            TAG,
            "done file=${file.length()}B kept=${result.keptStudents} cells=${result.writtenCells} " +
                "costMs=${System.currentTimeMillis() - startedAt}"
        )
        ExportOutcome(result, file)
    }

    /** 只读 zip 中央目录取工作表声明大小，用于日志；不解压、不占内存。 */
    private fun declaredSheetBytes(template: File): Long = try {
        ZipFile(template).use { zip ->
            zip.entries().asSequence()
                .firstOrNull {
                    !it.isDirectory && it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml")
                }?.size ?: -1L
        }
    } catch (e: Exception) {
        -1L
    }

    suspend fun writeToUri(context: Context, uri: Uri, file: File) = withContext(Dispatchers.IO) {
        val output = try {
            context.contentResolver.openOutputStream(uri, "w")
        } catch (e: SecurityException) {
            throw IllegalArgumentException("没有该保存位置的写入权限，请改存到「下载」目录", e)
        } catch (e: FileNotFoundException) {
            throw IllegalArgumentException("保存位置不可写，请改存到「下载」目录", e)
        } ?: throw IllegalArgumentException("无法写入所选保存位置，请改存到「下载」目录")
        try {
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (e: SecurityException) {
            throw IllegalArgumentException("保存过程中失去写入权限，请改存到「下载」目录", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("写入失败，请检查手机存储空间后重试", e)
        }
    }

    fun shareUri(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
}
