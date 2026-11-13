package com.example.tice.xlsx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** OOXML 部件常量与 zip 读取辅助。这里不引入任何 Android API，便于命令行单元测试。 */
internal object OoxmlParts {
    const val DEFAULT_SHEET = "xl/worksheets/sheet1.xml"
    const val SHARED_STRINGS = "xl/sharedStrings.xml"
    const val COMMENTS = "xl/comments1.xml"

    /** 表头批注里的数值范围，例如「-30~40」「500 ~ 9999」。 */
    val RANGE_RE = Regex("(-?\\d+(?:\\.\\d+)?)\\s*[~～]\\s*(-?\\d+(?:\\.\\d+)?)")

    /** 学生信息列：这些列的单元格在导出时必须保持原样，只有其余列才是测试项目。 */
    val INFO_HEADERS = setOf(
        "年级编号", "班级编号", "班级名称", "学籍号", "民族代码",
        "姓名", "性别", "出生日期", "家庭住址"
    )

    /** 解压后全部部件的总体积上限；压缩包里的 XML 可放大数倍，超限提前失败而不是被系统杀进程。 */
    const val MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024

    /**
     * 分块解压并累计总体积。默认上限兼容现有导入与备份恢复（都调用本方法读取 zip）。
     * 不再用 readBytes()，避免动态扩容时的瞬时双倍内存。
     */
    fun readZip(
        bytes: ByteArray,
        maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES
    ): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n < 0) break
                        total += n
                        require(total <= maxUncompressedBytes) {
                            "文件解压后超过 ${maxUncompressedBytes / 1024 / 1024}MB 上限，请精简模板或备份包"
                        }
                        buffer.write(chunk, 0, n)
                    }
                    result[entry.name] = buffer.toByteArray()
                }
                zis.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "不是有效的 xlsx 文件（zip 内无部件）" }
        return result
    }

    /** 流式导出时只有部件名，没有解压后的内容，用名字定位工作表。 */
    fun resolveSheetPartName(names: Iterable<String>): String {
        if (DEFAULT_SHEET in names) return DEFAULT_SHEET
        return names.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .minOrNull()
            ?: error("找不到工作表部件 xl/worksheets/sheet*.xml")
    }

    fun resolveSheetPart(parts: Map<String, ByteArray>): String = resolveSheetPartName(parts.keys)
}
