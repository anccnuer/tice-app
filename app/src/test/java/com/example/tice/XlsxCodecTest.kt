package com.example.tice

import com.example.tice.xlsx.XlsxCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 纯 JVM 命令行单元测试：验证解析规则与「模板保真」导出，无需 Android 设备。 */
class XlsxCodecTest {
    private val template = File("../data-example.xlsx")

    private fun parts(file: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory) result[entry.name] = zip.getInputStream(entry).readBytes()
            }
        }
        return result
    }

    /** 生产流式重载不返回整表字符串，断言一律从导出 zip 读回工作表部件。 */
    private fun sheetXmlOf(file: File): String =
        String(parts(file)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)

    @Test
    fun parseTemplateHeadersAndCommentRules() {
        assertTrue("缺少示例表 ${template.absolutePath}", template.isFile)
        val schema = XlsxCodec.parse(template.readBytes())
        assertEquals(17, schema.headers.size)
        assertEquals(8, schema.projects.size)
        assertEquals(2, schema.students.size)
        assertEquals("DEMO-STU-0001", schema.students.first().studentId)
        assertEquals("2026级1班", schema.students.first().className)

        val jump = schema.projects.first { it.key == "一分钟跳绳" }
        assertEquals(0.0, jump.min!!, 0.0)
        assertEquals(300.0, jump.max!!, 0.0)
        assertTrue(jump.integerOnly)

        val sitUp = schema.projects.first { it.key == "坐位体前屈" }
        assertEquals(-30.0, sitUp.min!!, 0.0)
        assertTrue(!sitUp.integerOnly)

        val shuttle = schema.projects.first { it.key == "50米×8往返跑" }
        assertTrue("2′20″ 属于文本成绩", shuttle.textValue)
        assertNull(shuttle.min) // 批注中的日期/格式说明不能被误判为数值范围
    }

    @Test
    fun exportKeepsEveryPartByteIdenticalAndWritesScores() {
        val original = parts(template)
        val schema = XlsxCodec.parse(template.readBytes())
        val student = schema.students.first()
        val previewDir = File("build").apply { mkdirs() }
        val out = File(previewDir, "export-preview.xlsx")
        val scores = mapOf(
            student.studentId to mapOf(
                "身高" to "165.5",
                "一分钟跳绳" to "123",
                "50米×8往返跑" to "2′20″"
            )
        )
        val result = FileOutputStream(out).use { stream ->
            XlsxCodec.export(template, setOf(student.studentId), scores, stream)
        }
        assertEquals(1, result.keptStudents)
        assertEquals(3, result.writtenCells)

        val exported = parts(out)
        assertEquals(original.keys, exported.keys)
        original.forEach { (name, data) ->
            if (name == "xl/worksheets/sheet1.xml") return@forEach
            assertArrayEquals("部件被改动: $name", data, exported[name])
        }

        val sheet = result.sheetXml
        assertTrue(sheet.contains("<c r=\"J2\"><v>165.5</v></c>"))
        assertTrue(sheet.contains("<c r=\"O2\"><v>123</v></c>"))
        assertTrue(sheet.contains("<c r=\"Q2\" t=\"inlineStr\"><is><t>2′20″</t></is></c>"))
        assertTrue(sheet.contains("<dimension ref=\"A1:Q2\"/>"))
        assertTrue(sheet.contains("<legacyDrawing"))
        assertTrue(sheet.contains("<c r=\"D2\" s=\"1\" t=\"s\">")) // 学籍号样式与文本格式保留
        assertEquals(1, Regex("</sheetData>").findAll(sheet).count())
        assertEquals(1, Regex("<sheetData>").findAll(sheet).count())
        assertNotNull(exported["xl/comments1.xml"])
        assertNotNull(exported["xl/drawings/vmlDrawing1.vml"])
    }

    @Test
    fun exportRenumberRowsWhenFilteringLaterClassmates() {
        val schema = XlsxCodec.parse(template.readBytes())
        val second = schema.students[1]
        File("build").apply { mkdirs() }
        val out = File("build/export-second-student.xlsx")
        val result = FileOutputStream(out).use { stream ->
            XlsxCodec.export(template, setOf(second.studentId), mapOf(second.studentId to mapOf("体重" to "42.5")), stream)
        }
        assertEquals(1, result.keptStudents)
        assertTrue("原第 3 行应重编号为第 2 行", result.sheetXml.contains("<row r=\"2\""))
        assertTrue("单元格引用需同步重编号", result.sheetXml.contains("<c r=\"D2\" s=\"1\" t=\"s\">"))
        assertTrue(result.sheetXml.contains("<c r=\"K2\"><v>42.5</v></c>"))
        assertTrue(!result.sheetXml.contains("r=\"D3\""))
    }

    @Test
    fun exportWithExplicitMappingsSkipsParsingAndKeepsParts() {
        val schema = XlsxCodec.parse(template.readBytes())
        val first = schema.students.first()
        val second = schema.students[1]
        val rowOfStudent = schema.students.associate { it.studentId to it.sourceRow }
        val projects = schema.projects.map { it.key to it.column }
        val scores = mapOf(
            first.studentId to mapOf("身高" to "165.5", "一分钟跳绳" to "123"),
            second.studentId to mapOf("体重" to "42.5")
        )
        File("build").apply { mkdirs() }
        val out = File("build/export-explicit.xlsx")
        val result = FileOutputStream(out).use { stream ->
            XlsxCodec.export(template, stream, rowOfStudent, projects, scores)
        }
        assertEquals(2, result.keptStudents)
        assertEquals(3, result.writtenCells)
        assertTrue("生产流式路径不得持有整表字符串", result.sheetXml.isEmpty())

        val sheet = sheetXmlOf(out)
        assertTrue(sheet.contains("<c r=\"J2\"><v>165.5</v></c>"))
        assertTrue(sheet.contains("<c r=\"O2\"><v>123</v></c>"))
        assertTrue(sheet.contains("<c r=\"K3\"><v>42.5</v></c>"))
        assertTrue(sheet.contains("<dimension ref=\"A1:Q3\"/>"))
        assertTrue(sheet.contains("<legacyDrawing"))
        assertTrue(sheet.contains("<c r=\"D2\" s=\"1\" t=\"s\">"))
        assertEquals(1, Regex("</sheetData>").findAll(sheet).count())

        val original = parts(template)
        val exported = parts(out)
        assertEquals(original.keys, exported.keys)
        original.forEach { (name, data) ->
            if (name == "xl/worksheets/sheet1.xml") return@forEach
            assertArrayEquals("部件被改动: $name", data, exported[name])
        }
    }

    @Test
    fun exportStreamsLargeSheetWithoutMaterializingAllRows() {
        val original = parts(template)
        val sheetName = "xl/worksheets/sheet1.xml"
        val sheet = String(original[sheetName]!!, Charsets.UTF_8)
        val rowRe = Regex("(?s)<row\\b[^>]*/>|<row\\b[^>]*>.*?</row>")
        val bodyStart = sheet.indexOf("<sheetData>") + "<sheetData>".length
        val bodyEnd = sheet.indexOf("</sheetData>")
        val rows = rowRe.findAll(sheet.substring(bodyStart, bodyEnd)).map { it.value }.toList()
        val header = rows.first()
        val dataRow = rows[1]
        val rowTagRe = Regex("(<row\\b[^>]*\\br=\")\\d+(\")")
        val cellRefRe = Regex("r=\"([A-Z]+)\\d+\"")

        val count = 5000
        val bigBody = StringBuilder(header.length + dataRow.length * count)
        bigBody.append(header)
        repeat(count) { i ->
            val rowNumber = i + 2
            val renumbered = rowTagRe.replace(dataRow) { m ->
                m.groupValues[1] + rowNumber + m.groupValues[2]
            }
            bigBody.append(cellRefRe.replace(renumbered) { m -> "r=\"${m.groupValues[1]}$rowNumber\"" })
        }
        val bigSheet = sheet.substring(0, bodyStart) + bigBody + sheet.substring(bodyEnd)

        File("build").apply { mkdirs() }
        val bigTemplate = File("build/export-large-template.xlsx")
        ZipOutputStream(FileOutputStream(bigTemplate).buffered()).use { zos ->
            original.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(if (name == sheetName) bigSheet.toByteArray(Charsets.UTF_8) else data)
                zos.closeEntry()
            }
        }

        val rowOfStudent = (2..(count + 1)).associate { row -> "S$row" to row }
        val projects = XlsxCodec.parse(template.readBytes()).projects.map { it.key to it.column }
        val out = File("build/export-large.xlsx")
        val result = FileOutputStream(out).use { stream ->
            XlsxCodec.export(bigTemplate, stream, rowOfStudent, projects, mapOf("S2" to mapOf("身高" to "165.5")))
        }
        assertEquals(count, result.keptStudents)
        assertEquals(1, result.writtenCells)
        assertTrue("生产流式路径不得持有整表字符串", result.sheetXml.isEmpty())

        val exportedSheet = sheetXmlOf(out)
        assertTrue(exportedSheet.contains("<dimension ref=\"A1:Q${count + 1}\"/>"))
        assertTrue(exportedSheet.contains("<c r=\"J2\"><v>165.5</v></c>"))
        assertEquals(1, Regex("</sheetData>").findAll(exportedSheet).count())
        assertEquals(1, Regex("<sheetData>").findAll(exportedSheet).count())

        val exported = parts(out)
        assertEquals(original.keys, exported.keys)
        original.forEach { (name, data) ->
            if (name == sheetName) return@forEach
            assertArrayEquals("部件被改动: $name", data, exported[name])
        }
    }

    @Test
    fun exportRejectsSheetOverBudgetBeforeWriting() {
        val schema = XlsxCodec.parse(template.readBytes())
        val rowOfStudent = schema.students.associate { it.studentId to it.sourceRow }
        val projects = schema.projects.map { it.key to it.column }
        File("build").apply { mkdirs() }
        val out = File("build/export-over-budget.xlsx")
        out.delete()

        val error = assertThrows(IllegalArgumentException::class.java) {
            FileOutputStream(out).use { stream ->
                XlsxCodec.export(template, stream, rowOfStudent, projects, emptyMap(), 1024L)
            }
        }
        assertTrue(error.message.orEmpty().contains("工作表解压后超过"))
        // 声明大小在打开输入流之前就被拒绝，不应把工作表内容写进输出
        val sheet = runCatching {
            parts(out)["xl/worksheets/sheet1.xml"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        }.getOrDefault("")
        assertTrue("超预算时不应写出工作表", !sheet.contains("<sheetData>"))
    }
}
