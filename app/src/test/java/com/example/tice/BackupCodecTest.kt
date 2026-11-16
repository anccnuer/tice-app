package com.example.tice

import com.example.tice.backup.BackupCodec
import com.example.tice.data.db.MetaEntity
import com.example.tice.data.db.ProjectRuleEntity
import com.example.tice.data.db.ScoreEntity
import com.example.tice.data.db.SessionEntity
import com.example.tice.data.db.StudentEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份包 v1 的编解码契约：往返一致、篡改与版本不兼容必须被拒绝。 */
class BackupCodecTest {

    private fun samplePayload() = BackupCodec.Payload(
        students = listOf(
            StudentEntity(
                studentId = "DEMO-STU-0001",
                gradeId = "2026",
                classId = "C1",
                className = "1班",
                nationCode = "01",
                name = "示例学生甲",
                gender = "男",
                birthDate = "2014-01-01",
                address = "示例省示例市",
                sourceRow = 2
            )
        ),
        scores = listOf(ScoreEntity("DEMO-STU-0001", "肺活量", "2500", 1700000000000L)),
        rules = listOf(
            ProjectRuleEntity("肺活量", "H", "500~9999", 500.0, 9999.0, false, false, 0)
        ),
        metaRows = listOf(MetaEntity("group_size", "2"), MetaEntity("source", "assets")),
        session = SessionEntity(
            classId = "C1",
            className = "1班",
            batchSize = 3,
            randomMode = false,
            projectsCsv = "肺活量",
            studentIdsCsv = "DEMO-STU-0001",
            index = 0,
            batchNo = 1,
            updatedAt = 1700000000000L
        ),
        templateBytes = "fake-xlsx-template".toByteArray()
    )

    @Test
    fun `编码后解码字段保持完整`() {
        val payload = samplePayload()
        val decoded = BackupCodec.decode(BackupCodec.encode(payload))

        assertEquals(1, decoded.students.size)
        assertEquals("示例学生甲", decoded.students[0].name)
        assertEquals(2, decoded.students[0].sourceRow)
        assertEquals("2500", decoded.scores[0].value)
        assertEquals(500.0, decoded.rules[0].minValue!!, 0.0)
        assertEquals(2, decoded.metaRows.size)
        assertEquals("2", decoded.metaRows.first { it.key == "group_size" }.value)
        assertNotNull(decoded.session)
        assertEquals("C1", decoded.session?.classId)
        assertArrayEquals(payload.templateBytes, decoded.templateBytes)
    }

    @Test
    fun `data_json 被篡改时拒绝恢复`() {
        val broken = rewriteEntry(BackupCodec.encode(samplePayload()), "data.json") {
            it + " ".toByteArray()
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(broken)
        }
        assertEquals("备份包校验失败：数据已损坏", error.message)
    }

    @Test
    fun `模板被篡改时拒绝恢复`() {
        val broken = rewriteEntry(BackupCodec.encode(samplePayload()), "template.xlsx") {
            "tampered".toByteArray()
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(broken)
        }
        assertEquals("备份包校验失败：模板已损坏", error.message)
    }

    @Test
    fun `版本不兼容时拒绝恢复`() {
        val broken = rewriteEntry(BackupCodec.encode(samplePayload()), "meta.json") {
            """{"version":999}""".toByteArray()
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(broken)
        }
        assertEquals("备份包版本不兼容", error.message)
    }

    /** 重新打包并替换指定部件，用于模拟损坏的备份包。 */
    private fun rewriteEntry(
        zipBytes: ByteArray,
        target: String,
        transform: (ByteArray) -> ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { input ->
            ZipOutputStream(out).use { output ->
                var entry: ZipEntry? = input.nextEntry
                while (entry != null) {
                    val data = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if (entry.name == target) transform(data) else data)
                    output.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return out.toByteArray()
    }
}
