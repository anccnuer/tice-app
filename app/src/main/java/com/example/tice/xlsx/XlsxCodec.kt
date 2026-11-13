package com.example.tice.xlsx

import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class HeaderCell(val column: String, val text: String)

/** 一个测试项目列，规则来自表头批注（如「一分钟跳绳：值范围0~300整数」）。 */
data class ProjectColumn(
    val key: String,
    val column: String,
    val hint: String,
    val min: Double?,
    val max: Double?,
    val integerOnly: Boolean,
    val textValue: Boolean
)

data class StudentRow(
    val sourceRow: Int,
    val studentId: String,
    val gradeId: String,
    val classId: String,
    val className: String,
    val name: String,
    val gender: String,
    val birthDate: String,
    val address: String,
    val nationCode: String
)

data class WorkbookSchema(
    val headers: List<HeaderCell>,
    val projects: List<ProjectColumn>,
    val students: List<StudentRow>
)

/**
 * 导出结果。[sheetXml] 只由兼容重载填充，供离线校验与 JVM 单测读取；
 * 生产流式路径恒为空串，避免把整张工作表字符串带回调用方继续占内存。
 */
data class ExportResult(
    val keptStudents: Int,
    val writtenCells: Int,
    val sheetXml: String = ""
)

/**
 * xlsx 解析与「模板保真」导出。
 *
 * 设计要点：原始 xlsx 字节是不可变模板。导出时逐个 zip 部件原样复制，仅重写
 * xl/worksheets/sheet1.xml 的 <sheetData>：保留表头行，按所选班级过滤学生行并重编号，
 * 追加/覆盖成绩单元格。批注、VML、样式、sharedStrings、列宽、其它信息列全部字节级不变。
 *
 * 解析走 [StreamingXlsx] 流式读取，不构建 DOM；生产导出按「前缀 → 单行 → 后缀」流式读写，
 * 任何时刻只物化很小的前缀与一个 row，常驻内存与工作表体积解耦，避免大表被系统
 * lowmemorykiller 直接 SIGKILL（这种情况没有 Java 异常，catch 无法兜底）。
 */
object XlsxCodec {
    private val ROW_RE = Regex("(?s)<row\\b[^>]*/>|<row\\b[^>]*>.*?</row>")
    private val ROW_NUM_RE = Regex("<row\\b[^>]*\\br=\"(\\d+)\"")
    private val CELL_REF_RE = Regex("r=\"([A-Z]+)\\d+\"")
    private val DIMENSION_RE = Regex("<dimension\\s+ref=\"([A-Z]+\\d+:)([A-Z]+)\\d+\"\\s*/>")
    private val STYLE_RE = Regex("s=\"(\\d+)\"")

    const val MAX_TEMPLATE_BYTES = 24 * 1024 * 1024
    /** 解压后单个工作表体积的默认上限；Android 侧还会按设备可用内存给出更小的预算。 */
    const val MAX_SHEET_BYTES = 8 * 1024 * 1024
    /** 流式片段（前缀或单行）的字符上限，防止异常模板把单个片段撑成几十 MB。 */
    private const val MAX_FRAGMENT_CHARS = 2 * 1024 * 1024

    private const val SHEET_DATA_OPEN = "<sheetData>"
    private const val SHEET_DATA_CLOSE = "</sheetData>"

    fun parse(bytes: ByteArray): WorkbookSchema {
        require(bytes.size <= MAX_TEMPLATE_BYTES) {
            "Excel 文件超过 ${MAX_TEMPLATE_BYTES / 1024 / 1024}MB 上限"
        }
        requireXlsx(bytes)
        return parseParts(OoxmlParts.readZip(bytes))
    }

    /** 明确区分旧版 .xls 与损坏文件，避免用户只看到笼统的解析失败。 */
    private fun requireXlsx(bytes: ByteArray) {
        val isOle2 = bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
        if (isOle2) {
            throw IllegalArgumentException("这是 .xls 旧格式，请在 Excel 或 WPS 中「另存为 .xlsx」后再导入")
        }
        val isZip = bytes.size >= 2 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            throw IllegalArgumentException("不是有效的 .xlsx 文件，请确认文件未被加密或损坏")
        }
    }

    private fun parseParts(parts: Map<String, ByteArray>): WorkbookSchema {
        val shared = StreamingXlsx.parseSharedStrings(parts[OoxmlParts.SHARED_STRINGS])
        val comments = StreamingXlsx.parseComments(parts[OoxmlParts.COMMENTS])
        val sheetPart = OoxmlParts.resolveSheetPart(parts)
        val sheetBytes = parts[sheetPart] ?: error("找不到工作表数据")
        return StreamingXlsx.parseSheet(sheetBytes, shared, comments)
    }

    /**
     * 兼容签名：scores 为「学籍号 → 项目名 → 成绩字符串」。
     *
     * 仅供 JVM 单测与离线校验使用：会读整份模板做一次解析，并用 [StringWriter] 捕获完整
     * [ExportResult.sheetXml]。生产路径必须走下面的 5 参重载，不要调用本重载。
     */
    fun export(
        template: File,
        selectedStudentIds: Set<String>,
        scores: Map<String, Map<String, String>>,
        out: OutputStream
    ): ExportResult {
        val bytes = template.readBytes()
        require(bytes.size <= MAX_TEMPLATE_BYTES) { "Excel 模板超过体积上限" }
        val schema = parseParts(OoxmlParts.readZip(bytes))
        val rowOfStudent = schema.students
            .filter { it.studentId in selectedStudentIds }
            .associate { it.studentId to it.sourceRow }
        val projects = schema.projects.map { it.key to it.column }
        val capture = StringWriter()
        val result = exportInternal(
            template, out, rowOfStudent, projects, scores,
            MAX_SHEET_BYTES.toLong(), capture
        )
        return result.copy(sheetXml = capture.toString())
    }

    /**
     * 生产路径：行列映射由 Room 直接给出（导入时已解析过），导出时不再二次解析整表。
     * 工作表按行流式改写并分块写进 zip，不产生整表字符串；[maxSheetBytes] 由 Android 侧
     * 按设备可用内存下传，超限在读之前/读取过程中立即失败，避免被系统杀进程。
     * 未测项目不写单元格；数值写 <v>，文本成绩（如 2′20″）写 inlineStr，避免改动 sharedStrings。
     */
    fun export(
        template: File,
        out: OutputStream,
        rowOfStudent: Map<String, Int>,
        projects: List<Pair<String, String>>,
        scores: Map<String, Map<String, String>>,
        maxSheetBytes: Long = MAX_SHEET_BYTES.toLong()
    ): ExportResult = exportInternal(
        template, out, rowOfStudent, projects, scores, maxSheetBytes, null
    )

    private fun exportInternal(
        template: File,
        out: OutputStream,
        rowOfStudent: Map<String, Int>,
        projects: List<Pair<String, String>>,
        scores: Map<String, Map<String, String>>,
        maxSheetBytes: Long,
        capture: Writer?
    ): ExportResult {
        require(template.isFile && template.length() > 0) { "尚未导入原始 Excel 模板，无法导出" }
        require(template.length() <= MAX_TEMPLATE_BYTES) { "Excel 模板超过体积上限" }
        require(maxSheetBytes > 0) { "工作表体积预算无效" }
        val keptByRow = HashMap<Int, String>((rowOfStudent.size * 2).coerceAtLeast(1))
        rowOfStudent.forEach { (id, row) -> keptByRow[row] = id }

        var stats: SheetStats? = null
        ZipFile(template).use { zip ->
            val names = ArrayList<String>()
            val nameEntries = zip.entries()
            while (nameEntries.hasMoreElements()) names += nameEntries.nextElement().name
            val sheetPart = OoxmlParts.resolveSheetPartName(names)

            ZipOutputStream(out.buffered()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == sheetPart) {
                        val zipWriter = OutputStreamWriter(zos, StandardCharsets.UTF_8)
                        val target = if (capture != null) TeeWriter(zipWriter, capture) else zipWriter
                        stats = rewriteSheet(
                            openReader = { openSheetReader(zip, entry, maxSheetBytes) },
                            output = target,
                            keptByRow = keptByRow,
                            projects = projects,
                            scores = scores
                        )
                        target.flush()
                    } else {
                        zip.getInputStream(entry).use { input -> input.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
        val result = stats ?: error("找不到工作表数据")
        return ExportResult(result.keptStudents, result.writtenCells)
    }

    private data class SheetStats(val keptStudents: Int, val writtenCells: Int)

    private fun openSheetReader(zip: ZipFile, entry: ZipEntry, budgetBytes: Long): Reader {
        // 打开输入流之前先按声明大小拒绝，做到「零大分配即失败」
        require(entry.size <= budgetBytes) { sheetTooLarge(budgetBytes) }
        val input = BudgetInputStream(zip.getInputStream(entry), budgetBytes) { sheetTooLarge(budgetBytes) }
        return BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    }

    private fun sheetTooLarge(budgetBytes: Long): String =
        "工作表解压后超过 ${budgetBytes / 1024 / 1024}MB 预算，请一次只勾选一个班分批导出，或精简原始模板后重新导入"

    /**
     * 唯一的行处理核心。两次顺序扫描（[openReader] 可重复打开同一 zip 部件）：
     * 第一遍只统计命中行数以确定 dimension 的末行号，第二遍边读边写。
     * 输出形状与非流式版本完全一致：更新后的前缀 + sheetData 开标签 + 表头行 +
     * 重编号并回填成绩的命中行 + sheetData 闭标签 + 后缀原文。
     */
    private fun rewriteSheet(
        openReader: () -> Reader,
        output: Writer,
        keptByRow: Map<Int, String>,
        projects: List<Pair<String, String>>,
        scores: Map<String, Map<String, String>>
    ): SheetStats {
        var keptRows = 0
        var headerSeen = false
        scanSheet(
            reader = openReader(),
            onPrefix = {},
            onRow = { rowXml ->
                if (!headerSeen) {
                    headerSeen = true
                } else {
                    val oldNumber = ROW_NUM_RE.find(rowXml)?.groupValues?.get(1)?.toIntOrNull()
                    if (oldNumber != null && keptByRow.containsKey(oldNumber)) keptRows++
                }
            },
            onBodyEnd = {},
            onSuffix = {}
        )
        require(headerSeen) { "模板缺少表头行" }
        val lastRow = (keptRows + 1).coerceAtLeast(1)

        var newRowNumber = 2
        var writtenCells = 0
        var headerWritten = false
        scanSheet(
            reader = openReader(),
            onPrefix = { prefix ->
                output.write(DIMENSION_RE.replace(prefix) { m ->
                    "<dimension ref=\"${m.groupValues[1]}${m.groupValues[2]}$lastRow\"/>"
                })
                output.write(SHEET_DATA_OPEN)
            },
            onRow = { rowXml ->
                if (!headerWritten) {
                    output.write(rowXml) // 表头行原样保留
                    headerWritten = true
                } else {
                    val oldNumber = ROW_NUM_RE.find(rowXml)?.groupValues?.get(1)?.toIntOrNull()
                    val studentId = oldNumber?.let { keptByRow[it] }
                    if (studentId != null) {
                        var row = renumber(rowXml, newRowNumber)
                        projects.forEach { (key, column) ->
                            val raw = scores[studentId]?.get(key)?.trim().orEmpty()
                            if (raw.isNotEmpty()) {
                                row = writeScore(row, column, newRowNumber, raw)
                                writtenCells++
                            }
                        }
                        output.write(row)
                        newRowNumber++
                    }
                }
            },
            onBodyEnd = { output.write(SHEET_DATA_CLOSE) },
            onSuffix = { fragment -> output.write(fragment) }
        )
        return SheetStats(keptByRow.size, writtenCells)
    }

    /**
     * 按 8KB 字符块扫描工作表，只把「很小的前缀」与「当前一行」物化成字符串。
     * 行切分沿用 [ROW_RE]，因此行间空白与原实现一样被丢弃；后缀分块回调，不整段驻留。
     */
    private fun scanSheet(
        reader: Reader,
        onPrefix: (String) -> Unit,
        onRow: (String) -> Unit,
        onBodyEnd: () -> Unit,
        onSuffix: (String) -> Unit
    ) {
        val chunk = CharArray(8192)
        val pending = StringBuilder()
        var stage = 0 // 0 前缀 / 1 sheetData 内的行 / 2 后缀
        var prefixFound = false
        var bodyEnded = false
        reader.use { r ->
            while (true) {
                val n = r.read(chunk)
                if (n < 0) break
                pending.append(chunk, 0, n)
                // 同一块数据里要连续推进「前缀 → 行 → 后缀」三个阶段；
                // 小文件可能在第一块就读完，若等下一块会永远停在 stage 1。
                var keepGoing = true
                while (keepGoing) {
                    keepGoing = when (stage) {
                        0 -> {
                            val idx = pending.indexOf(SHEET_DATA_OPEN)
                            if (idx >= 0) {
                                onPrefix(pending.substring(0, idx))
                                pending.delete(0, idx + SHEET_DATA_OPEN.length)
                                prefixFound = true
                                stage = 1
                                true
                            } else {
                                require(pending.length <= MAX_FRAGMENT_CHARS) { "工作表前缀过大，请精简模板" }
                                false
                            }
                        }
                        1 -> {
                            val closeIdx = pending.indexOf(SHEET_DATA_CLOSE)
                            val window = if (closeIdx >= 0) pending.substring(0, closeIdx) else pending.toString()
                            val match = ROW_RE.find(window)
                            when {
                                match != null -> {
                                    onRow(match.value)
                                    pending.delete(0, match.range.last + 1)
                                    true
                                }
                                closeIdx >= 0 -> {
                                    pending.delete(0, closeIdx + SHEET_DATA_CLOSE.length)
                                    stage = 2
                                    bodyEnded = true
                                    onBodyEnd()
                                    true
                                }
                                else -> {
                                    require(pending.length <= MAX_FRAGMENT_CHARS) { "单行过大，请精简模板" }
                                    false
                                }
                            }
                        }
                        else -> {
                            if (pending.isNotEmpty()) {
                                onSuffix(pending.toString())
                                pending.clear()
                            }
                            false
                        }
                    }
                }
            }
        }
        require(prefixFound) { "工作表缺少 <sheetData>" }
        require(bodyEnded) { "工作表缺少 </sheetData>" }
        if (stage == 2 && pending.isNotEmpty()) onSuffix(pending.toString())
    }

    /** 在底层输入流上累计解压字节，超预算立即抛可读异常，不做任何大分配。 */
    private class BudgetInputStream(
        input: InputStream,
        private val limit: Long,
        private val message: () -> String
    ) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1L)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) add(count.toLong())
            return count
        }

        private fun add(count: Long) {
            total += count
            if (total > limit) throw IllegalArgumentException(message())
        }
    }

    /** 兼容重载用：同一份片段同时写进 zip 与捕获用的 StringWriter。close 不关闭底层 zip 流。 */
    private class TeeWriter(private val first: Writer, private val second: Writer) : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            first.write(cbuf, off, len)
            second.write(cbuf, off, len)
        }

        override fun flush() {
            first.flush()
            second.flush()
        }

        override fun close() = flush()
    }

    private fun renumber(rowXml: String, newNumber: Int): String {
        // Kotlin 的 replaceFirst 没有 lambda 重载；该正则只可能匹配 row 起始标签一次
        val rowR = Regex("(<row\\b[^>]*\\br=\")\\d+(\")")
        var result = rowR.replace(rowXml) { m ->
            m.groupValues[1] + newNumber + m.groupValues[2]
        }
        result = CELL_REF_RE.replace(result) { m -> "r=\"${m.groupValues[1]}$newNumber\"" }
        return result
    }

    private fun writeScore(rowXml: String, column: String, rowNumber: Int, value: String): String {
        val escaped = escapeXml(value)
        val numeric = value.toDoubleOrNull()?.let { !it.isNaN() && !it.isInfinite() } == true
        val target = if (numeric) {
            "<c r=\"$column$rowNumber\"><v>${value}</v></c>"
        } else {
            "<c r=\"$column$rowNumber\" t=\"inlineStr\"><is><t>$escaped</t></is></c>"
        }
        val cellRe = Regex("(?s)<c\\s+r=\"$column\\d+\"[^>]*/>|<c\\s+r=\"$column\\d+\"[^>]*>.*?</c>")
        val existing = cellRe.find(rowXml)
        if (existing == null) {
            val closeIndex = rowXml.lastIndexOf("</row>")
            return if (closeIndex >= 0) {
                rowXml.substring(0, closeIndex) + target + rowXml.substring(closeIndex)
            } else {
                rowXml + target
            }
        }
        val style = STYLE_RE.find(existing.value)?.let { " s=\"${it.groupValues[1]}\"" }.orEmpty()
        val targetWithStyle = target.replaceFirst("<c r=", "<c$style r=")
        return rowXml.replaceRange(existing.range, targetWithStyle)
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
