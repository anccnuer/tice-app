package com.example.tice.xlsx

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * 流式解析 xlsx 部件（sharedStrings / comments / sheetData）。
 *
 * 不构建 DOM：内存占用只与学生行数线性相关，避免上千行的大表把 Android 进程撑爆被杀。
 */
internal object StreamingXlsx {

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        runCatching { factory.isNamespaceAware = false }
        return factory.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
    }

    fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val parser = newParser(bytes)
        val result = ArrayList<String>()
        var builder: StringBuilder? = null
        var inPhonetic = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> builder = StringBuilder()
                    "rPh" -> inPhonetic = true
                }
                XmlPullParser.TEXT -> {
                    val b = builder
                    if (b != null && !inPhonetic) b.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "rPh" -> inPhonetic = false
                    "si" -> {
                        result.add(builder?.toString().orEmpty())
                        builder = null
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    fun parseComments(bytes: ByteArray?): Map<String, String> {
        if (bytes == null) return emptyMap()
        val parser = newParser(bytes)
        val result = HashMap<String, String>()
        var ref: String? = null
        var builder: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "comment") {
                    ref = parser.getAttributeValue(null, "ref")
                    builder = StringBuilder()
                }
                XmlPullParser.TEXT -> {
                    val b = builder
                    if (b != null) b.append(parser.text)
                }
                XmlPullParser.END_TAG -> if (parser.name == "comment") {
                    val key = ref
                    if (!key.isNullOrBlank()) result[key] = builder?.toString().orEmpty()
                    ref = null
                    builder = null
                }
            }
            event = parser.next()
        }
        return result
    }

    fun parseSheet(
        sheetBytes: ByteArray,
        shared: List<String>,
        comments: Map<String, String>
    ): WorkbookSchema {
        val parser = newParser(sheetBytes)
        var headers: List<HeaderCell> = emptyList()
        var headerColumns: Map<String, String> = emptyMap()
        var headerDone = false
        val students = ArrayList<StudentRow>()

        var currentRow = 0
        var cells: LinkedHashMap<String, String>? = null
        var column: String? = null
        var cellType: String? = null
        var builder = StringBuilder()
        var inValue = false
        var inInlineString = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (currentRow + 1)
                        cells = LinkedHashMap()
                    }
                    "c" -> {
                        column = columnOf(parser.getAttributeValue(null, "r"))
                        cellType = parser.getAttributeValue(null, "t")
                        builder = StringBuilder()
                        inValue = false
                        inInlineString = false
                    }
                    "v" -> if (column != null) inValue = true
                    "is" -> if (column != null) inInlineString = true
                }
                XmlPullParser.TEXT -> {
                    if (column != null && (inValue || inInlineString)) builder.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "is" -> inInlineString = false
                    "c" -> {
                        val col = column
                        if (col != null) {
                            val raw = builder.toString()
                            val text = if (cellType == "s") {
                                shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            } else {
                                raw
                            }
                            cells?.put(col, text)
                        }
                        column = null
                        cellType = null
                    }
                    "row" -> {
                        val rowCells = cells
                        cells = null
                        if (rowCells != null && rowCells.isNotEmpty()) {
                            if (!headerDone) {
                                headerDone = true
                                headers = rowCells
                                    .map { HeaderCell(it.key, it.value.trim()) }
                                    .filter { it.text.isNotBlank() }
                                headerColumns = headers.associate { it.text to it.column }
                                require(headerColumns.containsKey("学籍号")) {
                                    "表头缺少「学籍号」列，无法导入"
                                }
                            } else {
                                fun valueOf(header: String): String =
                                    headerColumns[header]?.let { rowCells[it] }?.trim().orEmpty()

                                val id = valueOf("学籍号")
                                if (id.isNotEmpty()) {
                                    students += StudentRow(
                                        sourceRow = currentRow,
                                        studentId = id,
                                        gradeId = valueOf("年级编号"),
                                        classId = valueOf("班级编号"),
                                        className = valueOf("班级名称"),
                                        name = valueOf("姓名"),
                                        gender = valueOf("性别"),
                                        birthDate = valueOf("出生日期"),
                                        address = valueOf("家庭住址"),
                                        nationCode = valueOf("民族代码")
                                    )
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        require(headerDone) { "工作表没有表头行" }
        val projects = headers
            .filter { it.text !in OoxmlParts.INFO_HEADERS }
            .map { header ->
                val hint = comments[header.column + "1"].orEmpty()
                val range = OoxmlParts.RANGE_RE.find(hint)
                ProjectColumn(
                    key = header.text,
                    column = header.column,
                    hint = hint,
                    min = range?.groupValues?.get(1)?.toDoubleOrNull(),
                    max = range?.groupValues?.get(2)?.toDoubleOrNull(),
                    integerOnly = hint.contains("整数"),
                    // 「50米×8往返跑」是 2′20″ 这类文本成绩，必须写 inlineStr 而非数值
                    textValue = hint.contains("′") || hint.contains("″")
                )
            }
        require(projects.isNotEmpty()) { "没有识别到任何测试项目列" }
        require(students.isNotEmpty()) { "没有解析到学生数据行" }
        require(students.distinctBy { it.studentId }.size == students.size) {
            "学籍号存在重复，请检查原始表"
        }
        return WorkbookSchema(headers, projects, students)
    }

    private fun columnOf(reference: String?): String? {
        if (reference == null) return null
        val letters = reference.takeWhile { it.isLetter() }
        return letters.ifEmpty { null }
    }
}
