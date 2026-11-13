package com.example.tice.backup

import com.example.tice.data.db.MetaEntity
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.ScoreEntity
import com.example.tice.data.db.SessionEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.data.db.TiceDao
import com.example.tice.data.file.FileStore
import com.example.tice.xlsx.OoxmlParts
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RestoreResult(val studentCount: Int, val scoreCount: Int)

/** 备份包 = meta.json（版本/校验和）+ data.json（全部业务数据）+ template.xlsx（原始模板字节）。 */
class BackupManager(
    private val dao: TiceDao,
    private val store: FileStore
) {
    companion object {
        const val VERSION = 1
    }

    suspend fun createBackupZip(): File {
        val students = dao.allStudents()
        val scores = dao.allScores()
        val rules = dao.rulesOnce()
        val metaRows = dao.allMeta()
        val session = dao.session()

        val data = JSONObject().apply {
            put("version", VERSION)
            put("students", JSONArray().apply {
                students.forEach {
                    put(JSONObject().apply {
                        put("studentId", it.studentId)
                        put("gradeId", it.gradeId)
                        put("classId", it.classId)
                        put("className", it.className)
                        put("nationCode", it.nationCode)
                        put("name", it.name)
                        put("gender", it.gender)
                        put("birthDate", it.birthDate)
                        put("address", it.address)
                        put("sourceRow", it.sourceRow)
                    })
                }
            })
            put("scores", JSONArray().apply {
                scores.forEach {
                    put(JSONObject().apply {
                        put("studentId", it.studentId)
                        put("projectKey", it.projectKey)
                        put("value", it.value)
                        put("updatedAt", it.updatedAt)
                    })
                }
            })
            put("rules", JSONArray().apply {
                rules.forEach {
                    put(JSONObject().apply {
                        put("key", it.key)
                        put("column", it.column)
                        put("hint", it.hint)
                        put("minValue", it.minValue ?: JSONObject.NULL)
                        put("maxValue", it.maxValue ?: JSONObject.NULL)
                        put("integerOnly", it.integerOnly)
                        put("textValue", it.textValue)
                        put("sortOrder", it.sortOrder)
                    })
                }
            })
            put("meta", JSONArray().apply {
                metaRows.forEach { put(JSONObject().apply { put("key", it.key); put("value", it.value) }) }
            })
            put("session", session?.let {
                JSONObject().apply {
                    put("classId", it.classId); put("className", it.className)
                    put("batchSize", it.batchSize); put("randomMode", it.randomMode)
                    put("projectsCsv", it.projectsCsv); put("studentIdsCsv", it.studentIdsCsv)
                    put("index", it.index); put("batchNo", it.batchNo); put("updatedAt", it.updatedAt)
                }
            } ?: JSONObject.NULL)
        }
        val dataBytes = data.toString().toByteArray(StandardCharsets.UTF_8)
        val templateBytes = if (store.hasTemplate()) store.templateFile().readBytes() else ByteArray(0)
        val metaJson = JSONObject().apply {
            put("version", VERSION)
            put("app", "体测助手")
            put("createdAt", System.currentTimeMillis())
            put("studentCount", students.size)
            put("scoreCount", scores.size)
            put("templateBytes", templateBytes.size)
            put("dataSha256", sha256(dataBytes))
            put("templateSha256", sha256(templateBytes))
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json")); zip.write(metaJson); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(dataBytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("template.xlsx")); zip.write(templateBytes); zip.closeEntry()
        }
        return store.writeBackup(buffer.toByteArray())
    }

    /** 校验版本与 SHA-256 后单事务覆盖恢复；恢复前自动留一份回滚快照。 */
    suspend fun restore(input: InputStream): RestoreResult {
        val parts = OoxmlParts.readZip(input.readBytes())
        val meta = JSONObject(String(parts["meta.json"]
            ?: throw IllegalArgumentException("不是有效的体测备份包"), StandardCharsets.UTF_8))
        require(meta.optInt("version", 0) in 1..VERSION) { "备份包版本不兼容" }
        val dataBytes = parts["data.json"] ?: throw IllegalArgumentException("备份包缺少 data.json")
        require(sha256(dataBytes) == meta.optString("dataSha256")) { "备份包校验失败：数据已损坏" }
        val templateBytes = parts["template.xlsx"] ?: ByteArray(0)
        if (templateBytes.isNotEmpty()) {
            require(sha256(templateBytes) == meta.optString("templateSha256")) { "备份包校验失败：模板已损坏" }
        }
        val data = JSONObject(String(dataBytes, StandardCharsets.UTF_8))

        val students = parseStudents(data.getJSONArray("students"))
        val scores = parseScores(data.getJSONArray("scores"))
        val rules = parseRules(data.getJSONArray("rules"))
        val metaRows = data.optJSONArray("meta")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); MetaEntity(o.getString("key"), o.optString("value"))
            }
        }.orEmpty()
        val session = data.optJSONObject("session")?.let {
            SessionEntity(
                classId = it.optString("classId"), className = it.optString("className"),
                batchSize = it.optInt("batchSize", 5), randomMode = it.optBoolean("randomMode", true),
                projectsCsv = it.optString("projectsCsv"), studentIdsCsv = it.optString("studentIdsCsv"),
                index = it.optInt("index"), batchNo = it.optInt("batchNo", 1),
                updatedAt = it.optLong("updatedAt")
            )
        }

        runCatching {
            if (store.hasTemplate() && dao.allStudents().isNotEmpty()) createBackupZip()
        }
        if (templateBytes.isNotEmpty()) store.saveTemplate(templateBytes)
        dao.restoreAll(students, scores, rules, metaRows, session)
        return RestoreResult(students.size, scores.size)
    }

    private fun parseStudents(arr: JSONArray): List<StudentEntity> = (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let {
            StudentEntity(
                studentId = it.getString("studentId"), gradeId = it.optString("gradeId"),
                classId = it.optString("classId"), className = it.optString("className"),
                nationCode = it.optString("nationCode"), name = it.optString("name"),
                gender = it.optString("gender"), birthDate = it.optString("birthDate"),
                address = it.optString("address"), sourceRow = it.optInt("sourceRow")
            )
        }
    }

    private fun parseScores(arr: JSONArray): List<ScoreEntity> = (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let {
            ScoreEntity(it.getString("studentId"), it.getString("projectKey"), it.optString("value"), it.optLong("updatedAt"))
        }
    }

    private fun parseRules(arr: JSONArray): List<ProjectRuleEntity> = (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let {
            val min = if (it.isNull("minValue")) null else it.optDouble("minValue")
            val max = if (it.isNull("maxValue")) null else it.optDouble("maxValue")
            ProjectRuleEntity(
                key = it.getString("key"), column = it.optString("column"), hint = it.optString("hint"),
                minValue = min, maxValue = max, integerOnly = it.optBoolean("integerOnly"),
                textValue = it.optBoolean("textValue"), sortOrder = it.optInt("sortOrder")
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
