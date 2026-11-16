package com.example.tice.backup

import com.example.tice.util.BACKUP_PORT_RANGE
import com.example.tice.util.DEFAULT_BACKUP_PORT
import com.example.tice.util.HTTP_SOCKET_TIMEOUT_MS
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 手机端极简只读 HTTP 服务：只提供 GET 页面与备份包下载，不实现任何写入口。
 * 使用 java.net.ServerSocket 自实现，避免额外第三方依赖与 AGP 冲突。
 */
class BackupHttpServer(
    private val code: String,
    private val backups: () -> List<File>,
    private val onLog: (String) -> Unit = {}
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    var port: Int = 0
        private set

    fun start(preferred: Int = DEFAULT_BACKUP_PORT): Int {
        var lastError: Exception? = null
        for (candidate in preferred..(preferred + BACKUP_PORT_RANGE)) {
            try {
                server = ServerSocket(candidate, 32, InetAddress.getByName("0.0.0.0"))
                port = candidate
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val socket = server ?: throw IOException(
            "$DEFAULT_BACKUP_PORT~${DEFAULT_BACKUP_PORT + BACKUP_PORT_RANGE} 端口均不可用",
            lastError
        )
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "tice-http").apply { isDaemon = true; start() }
        onLog("HTTP 服务启动，端口 $port")
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        onLog("HTTP 服务停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                Thread({ handle(client) }, "tice-http-client").apply { isDaemon = true; start() }
            } catch (e: IOException) {
                if (running) onLog("连接异常 ${e.javaClass.simpleName}") else break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = HTTP_SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val segments = requestLine.split(" ")
            if (segments.size < 2) return
            val method = segments[0].uppercase(Locale.ROOT)
            val target = segments[1]
            val queryIndex = target.indexOf('?')
            val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
            val query = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""
            val params = query.split("&").mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i < 0) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
            }.toMap()
            val out = socket.getOutputStream()
            onLog("$method $path")
            when {
                path == "/health" -> writeBytes(out, 200, "text/plain; charset=utf-8", "ok".toByteArray())
                method != "GET" -> writeHtml(
                    out,
                    405,
                    messagePage(code, "不支持的操作", "此服务仅提供只读下载，不能提交数据。")
                )
                params["code"] != code -> writeHtml(out, 403, codePage())
                path == "/" || path == "/index.html" -> writeHtml(out, 200, indexPage(code, backups()))
                path == "/backup/latest" -> sendBackup(out, backups().firstOrNull())
                path.startsWith("/backup/") -> {
                    val name = decode(path.removePrefix("/backup/"))
                    sendBackup(out, backups().firstOrNull { it.name == name })
                }
                else -> writeHtml(
                    out,
                    404,
                    messagePage(code, "页面不存在", "请返回首页查看备份包列表。")
                )
            }
        } catch (e: Exception) {
            onLog("请求处理异常 ${e.javaClass.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendBackup(out: OutputStream, file: File?) {
        if (file == null || !file.isFile) {
            writeHtml(
                out,
                404,
                messagePage(code, "暂无备份包", "请先在手机 App 的「数据与备份」页点击生成备份。")
            )
            return
        }
        writeHeader(out, 200, "OK", "application/zip", file.length(), contentDisposition(file.name))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
        onLog("下载备份包 ${file.name}")
    }

    /** 文件名本身是 ASCII 安全字符，这里仍做转义，避免异常字符破坏响应头。 */
    private fun contentDisposition(name: String): String {
        val safe = name
            .replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
        return "Content-Disposition: attachment; filename=\"$safe\"\r\n"
    }

    private fun writeHtml(out: OutputStream, status: Int, html: String) =
        writeBytes(out, status, "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytes(out: OutputStream, status: Int, type: String, body: ByteArray) {
        writeHeader(out, status, reason(status), type, body.size.toLong())
        out.write(body)
        out.flush()
    }

    private fun writeHeader(
        out: OutputStream,
        status: Int,
        text: String,
        type: String,
        length: Long,
        extra: String = ""
    ) {
        val header = "HTTP/1.1 $status $text\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: $length\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n" + extra + "\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}
