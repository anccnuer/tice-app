package com.example.tice.backup

import com.example.tice.data.db.MetaEntity
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.ScoreEntity
import com.example.tice.data.db.SessionEntity
import com.example.tice.data.db.StudentEntity
import com.example.tice.xlsx.OoxmlParts
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包的纯 JVM 编解码：zip 结构、JSON 字段名与 SHA-256 校验集中在此处。
 *
 * 字段名即数据契约（VERSION = 1），改动会影响历史备份包的恢复，必须保持兼容。
 */
object BackupCodec {
    const val VERSION = 1
    private const val META_ENTRY = "meta.json"
    private const val DATA_ENTRY = "data.json"
    private const val TEMPLATE_ENTRY = "template.xlsx"

    data class Payload(
        val students: List<StudentEntity> = emptyList(),
        val scores: List<ScoreEntity> = emptyList(),
        val rules: List<ProjectRuleEntity> = emptyList(),
        val metaRows: List<MetaEntity> = emptyList(),
        val session: SessionEntity? = null,
        val templateBytes: ByteArray = ByteArray(0)
    )

    fun encode(payload: Payload): ByteArray {
        val dataBytes = buildDataJson(payload)
        val metaBytes = buildMetaJson(payload, dataBytes)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(META_ENTRY)); zip.write(metaBytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA_ENTRY)); zip.write(dataBytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry(TEMPLATE_ENTRY)); zip.write(payload.templateBytes); zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): Payload {
        val parts = OoxmlParts.readZip(bytes)
        val meta = JSONObject(
            String(
                parts[META_ENTRY] ?: throw IllegalArgumentException("不是有效的体测备份包"),
                StandardCharsets.UTF_8
            )
        )
        require(meta.optInt("version", 0) in 1..VERSION) { "备份包版本不兼容" }
        val dataBytes = parts[DATA_ENTRY] ?: throw IllegalArgumentException("备份包缺少 data.json")
        require(sha256(dataBytes) == meta.optString("dataSha256")) { "备份包校验失败：数据已损坏" }
        val templateBytes = parts[TEMPLATE_ENTRY] ?: ByteArray(0)
        if (templateBytes.isNotEmpty()) {
            require(sha256(templateBytes) == meta.optString("templateSha256")) {
                "备份包校验失败：模板已损坏"
            }
        }
        return parseData(dataBytes, templateBytes)
    }

    private fun buildDataJson(payload: Payload): ByteArray {
        val data = JSONObject().apply {
            put("version", VERSION)
            put("students", JSONArray().apply {
                payload.students.forEach {
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
                payload.scores.forEach {
                    put(JSONObject().apply {
                        put("studentId", it.studentId)
                        put("projectKey", it.projectKey)
                        put("value", it.value)
                        put("updatedAt", it.updatedAt)
                    })
                }
            })
            put("rules", JSONArray().apply {
                payload.rules.forEach {
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
                payload.metaRows.forEach {
                    put(JSONObject().apply {
                        put("key", it.key)
                        put("value", it.value)
                    })
                }
            })
            put("session", payload.session?.let {
                JSONObject().apply {
                    put("classId", it.classId)
                    put("className", it.className)
                    put("batchSize", it.batchSize)
                    put("randomMode", it.randomMode)
                    put("projectsCsv", it.projectsCsv)
                    put("studentIdsCsv", it.studentIdsCsv)
                    put("index", it.index)
                    put("batchNo", it.batchNo)
                    put("updatedAt", it.updatedAt)
                }
            } ?: JSONObject.NULL)
        }
        return data.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildMetaJson(payload: Payload, dataBytes: ByteArray): ByteArray = JSONObject().apply {
        put("version", VERSION)
        put("app", "体测助手")
        put("createdAt", System.currentTimeMillis())
        put("studentCount", payload.students.size)
        put("scoreCount", payload.scores.size)
        put("templateBytes", payload.templateBytes.size)
        put("dataSha256", sha256(dataBytes))
        put("templateSha256", sha256(payload.templateBytes))
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun parseData(dataBytes: ByteArray, templateBytes: ByteArray): Payload {
        val data = JSONObject(String(dataBytes, StandardCharsets.UTF_8))
        return Payload(
            students = parseStudents(data.getJSONArray("students")),
            scores = parseScores(data.getJSONArray("scores")),
            rules = parseRules(data.getJSONArray("rules")),
            metaRows = data.optJSONArray("meta")?.mapObjects {
                MetaEntity(it.getString("key"), it.optString("value"))
            }.orEmpty(),
            session = data.optJSONObject("session")?.let {
                SessionEntity(
                    classId = it.optString("classId"), className = it.optString("className"),
                    batchSize = it.optInt("batchSize", 5), randomMode = it.optBoolean("randomMode", true),
                    projectsCsv = it.optString("projectsCsv"), studentIdsCsv = it.optString("studentIdsCsv"),
                    index = it.optInt("index"), batchNo = it.optInt("batchNo", 1),
                    updatedAt = it.optLong("updatedAt")
                )
            },
            templateBytes = templateBytes
        )
    }

    private fun parseStudents(arr: JSONArray): List<StudentEntity> = arr.mapObjects {
        StudentEntity(
            studentId = it.getString("studentId"), gradeId = it.optString("gradeId"),
            classId = it.optString("classId"), className = it.optString("className"),
            nationCode = it.optString("nationCode"), name = it.optString("name"),
            gender = it.optString("gender"), birthDate = it.optString("birthDate"),
            address = it.optString("address"), sourceRow = it.optInt("sourceRow")
        )
    }

    private fun parseScores(arr: JSONArray): List<ScoreEntity> = arr.mapObjects {
        ScoreEntity(
            it.getString("studentId"),
            it.getString("projectKey"),
            it.optString("value"),
            it.optLong("updatedAt")
        )
    }

    private fun parseRules(arr: JSONArray): List<ProjectRuleEntity> = arr.mapObjects {
        val min = if (it.isNull("minValue")) null else it.optDouble("minValue")
        val max = if (it.isNull("maxValue")) null else it.optDouble("maxValue")
        ProjectRuleEntity(
            key = it.getString("key"), column = it.optString("column"), hint = it.optString("hint"),
            minValue = min, maxValue = max, integerOnly = it.optBoolean("integerOnly"),
            textValue = it.optBoolean("textValue"), sortOrder = it.optInt("sortOrder")
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** 批量读取 JSON 对象数组，三个 parse 方法共用一套下标遍历。 */
private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }
