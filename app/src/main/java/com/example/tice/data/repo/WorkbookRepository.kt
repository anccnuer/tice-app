package com.example.tice.data.repo

import android.content.Context
import android.net.Uri
import com.example.tice.data.db.MetaEntity
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.data.db.TiceDao
import com.example.tice.data.file.FileStore
import com.example.tice.data.net.XlsxDownloader
import com.example.tice.xlsx.XlsxCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImportSummary(
    val studentCount: Int,
    val classCount: Int,
    val projectCount: Int,
    val source: String
)

/** 导入编排：本地文件、HTTP 地址、内置示例三条通道共用同一个 importBytes 入口。 */
class WorkbookRepository(
    private val dao: TiceDao,
    private val store: FileStore,
    private val downloader: XlsxDownloader
) {
    suspend fun importFromUri(context: Context, uri: Uri): ImportSummary {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选文件")
        return importBytes(bytes, "本地 Excel 文件")
    }

    suspend fun importFromUrl(url: String, onProgress: (Int) -> Unit = {}): ImportSummary =
        importBytes(downloader.download(url, onProgress), "网络地址")

    suspend fun importFromAssets(context: Context): ImportSummary {
        val bytes = context.assets.open("data-example.xlsx").use { it.readBytes() }
        return importBytes(bytes, "内置示例表")
    }

    suspend fun metaMap(): Map<String, String> = dao.allMeta().associate { it.key to it.value }

    suspend fun importBytes(bytes: ByteArray, source: String): ImportSummary {
        val schema = XlsxCodec.parse(bytes)
        val students = schema.students.map { row ->
            StudentEntity(
                studentId = row.studentId,
                gradeId = row.gradeId,
                classId = row.classId,
                className = row.className,
                nationCode = row.nationCode,
                name = row.name,
                gender = row.gender,
                birthDate = row.birthDate,
                address = row.address,
                sourceRow = row.sourceRow
            )
        }
        val rules = schema.projects.mapIndexed { index, project ->
            ProjectRuleEntity(
                key = project.key,
                column = project.column,
                hint = project.hint,
                minValue = project.min,
                maxValue = project.max,
                integerOnly = project.integerOnly,
                textValue = project.textValue,
                sortOrder = index
            )
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val meta = listOf(
            MetaEntity("source", source),
            MetaEntity("imported_at", stamp),
            MetaEntity("student_count", students.size.toString()),
            MetaEntity("project_count", rules.size.toString())
        )
        // 模板先原子落盘，再整体替换名单；成绩表按学籍号保留，重复导入不会丢已录成绩。
        store.saveTemplate(bytes)
        dao.replaceWorkbook(students, rules, meta)
        return ImportSummary(
            studentCount = students.size,
            classCount = students.map { it.classId }.distinct().size,
            projectCount = rules.size,
            source = source
        )
    }
}
