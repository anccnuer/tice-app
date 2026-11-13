package com.example.tice.backup

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
import java.text.SimpleDateFormat
import java.util.Date
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

    fun start(preferred: Int = 8080): Int {
        var lastError: Exception? = null
        for (candidate in preferred..(preferred + 12)) {
            try {
                server = ServerSocket(candidate, 32, InetAddress.getByName("0.0.0.0"))
                port = candidate
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val socket = server ?: throw IOException("8080~8092 端口均不可用", lastError)
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
            socket.soTimeout = 15_000
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
                method != "GET" -> writeHtml(out, 405, page("不支持的操作", "此服务仅提供只读下载，不能提交数据。"))
                params["code"] != code -> writeHtml(out, 403, codePage())
                path == "/" || path == "/index.html" -> writeHtml(out, 200, indexPage())
                path == "/backup/latest" -> sendBackup(out, backups().firstOrNull())
                path.startsWith("/backup/") -> {
                    val name = decode(path.removePrefix("/backup/"))
                    sendBackup(out, backups().firstOrNull { it.name == name })
                }
                else -> writeHtml(out, 404, page("页面不存在", "请返回首页查看备份包列表。"))
            }
        } catch (e: Exception) {
            onLog("请求处理异常 ${e.javaClass.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendBackup(out: OutputStream, file: File?) {
        if (file == null || !file.isFile) {
            writeHtml(out, 404, page("暂无备份包", "请先在手机 App 的「数据与备份」页点击生成备份。"))
            return
        }
        val extra = "Content-Disposition: attachment; filename=\"${file.name}\"\r\n"
        writeHeader(out, 200, "OK", "application/zip", file.length(), extra)
        file.inputStream().use { it.copyTo(out) }
        out.flush()
        onLog("下载备份包 ${file.name}")
    }

    private fun writeHtml(out: OutputStream, status: Int, html: String) =
        writeBytes(out, status, "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytes(out: OutputStream, status: Int, type: String, body: ByteArray) {
        writeHeader(out, status, reason(status), type, body.size.toLong())
        out.write(body)
        out.flush()
    }

    private fun writeHeader(out: OutputStream, status: Int, text: String, type: String, length: Long, extra: String = "") {
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

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f)
        else -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024f)
    }

    private fun indexPage(): String {
        val files = backups()
        val rows = if (files.isEmpty()) {
            """<div class="empty">还没有备份包。请在手机 App 的「数据与备份」页点击「生成备份」。</div>"""
        } else {
            files.joinToString("") { file ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(file.lastModified()))
                """
                <div class="row">
                  <div class="file"><span class="dot"></span><div><strong>${file.name}</strong><small>$time · ${sizeLabel(file.length())}</small></div></div>
                  <a class="btn" href="/backup/${file.name}?code=$code">下载备份包</a>
                </div>
                """.trimIndent()
            }
        }
        val latest = if (files.isEmpty()) "" else
            """<a class="primary" href="/backup/latest?code=$code">下载最新备份</a>"""
        return layout("体测助手 · 局域网备份") {
            """
            <div class="hero">
              <div class="label">READ-ONLY BACKUP</div>
              <h1>局域网备份下载</h1>
              <p>手机与电脑处于同一 WiFi 时，在此下载完整备份包。此页面<strong>只读</strong>，不提供上传或修改数据。</p>
              <div class="meta"><span>访问码 <b>$code</b></span><span>共 ${files.size} 个备份</span></div>
              <div class="actions">$latest<a class="ghost" href="/health">服务状态</a></div>
            </div>
            <div class="card"><h2>备份快照</h2><p class="sub">每个备份包包含原始 Excel 模板、全部学生与成绩数据、校验信息。</p>$rows</div>
            <div class="card"><h2>如何恢复</h2><ol><li>在电脑上下载备份包（.zip），妥善保存到本地。</li><li>手机打开「体测助手 → 数据与备份 → 导入备份包恢复」。</li><li>选择该 zip 文件，App 校验通过后一键恢复，恢复前会自动保留一份回滚快照。</li></ol></div>
            <div class="warn">备份包含学生姓名、学籍号等敏感信息，请仅在可信的校内网络开启本服务，用完及时关闭。</div>
            """
        }
    }

    private fun codePage(): String = layout("需要访问码") {
        """
        <div class="card narrow"><h1>请输入访问码</h1>
        <p class="sub">访问码显示在手机 App 的「数据与备份」页及前台通知中。</p>
        <form method="get" action="/">
          <input name="code" inputmode="numeric" maxlength="4" placeholder="4 位访问码" autofocus required />
          <button class="primary" type="submit">进入备份页</button>
        </form>
        <p class="sub">此表单为只读查询，不会向手机写入任何数据。</p></div>
        """
    }

    private fun page(title: String, message: String): String = layout(title) {
        """<div class="card narrow"><h1>$title</h1><p class="sub">$message</p><a class="ghost" href="/?code=$code">返回首页</a></div>"""
    }

    private fun layout(title: String, content: () -> String): String = """
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>$title</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#F5F7FB;color:#111827;
font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;line-height:1.6}
.wrap{max-width:880px;margin:0 auto;padding:32px 20px 60px}
.hero{background:linear-gradient(135deg,#1E3A8A 0%,#2563EB 55%,#0EA5A4 100%);
color:#fff;border-radius:24px;padding:36px 34px;box-shadow:0 18px 40px rgba(37,99,235,.22)}
.label{font-size:12px;letter-spacing:.22em;opacity:.75;font-weight:700}
h1{font-size:30px;margin:12px 0 10px}h2{font-size:20px;margin:0 0 6px}
p{margin:0 0 12px}.sub{color:#6B7280;font-size:14px}
.meta{display:flex;gap:22px;flex-wrap:wrap;margin:18px 0 20px;font-size:14px;opacity:.95}
.meta b{font-size:18px;letter-spacing:.18em}
.actions{display:flex;gap:12px;flex-wrap:wrap}
.primary,.ghost,.btn{display:inline-flex;align-items:center;justify-content:center;
border-radius:12px;padding:11px 20px;font-size:15px;font-weight:600;text-decoration:none;
border:0;cursor:pointer;transition:transform .15s ease,box-shadow .15s ease}
.primary{background:#fff;color:#1D4ED8;box-shadow:0 6px 16px rgba(0,0,0,.12)}
.primary:hover,.btn:hover{transform:translateY(-2px);box-shadow:0 10px 22px rgba(0,0,0,.16)}
.ghost{background:rgba(255,255,255,.16);color:#fff;border:1px solid rgba(255,255,255,.5)}
.card{background:#fff;border-radius:20px;padding:26px;margin-top:22px;box-shadow:0 8px 26px rgba(30,58,138,.07)}
.card.narrow{max-width:520px;margin:60px auto}
.row{display:flex;align-items:center;justify-content:space-between;gap:16px;
padding:16px 0;border-top:1px solid #EEF2F7;flex-wrap:wrap}
.file{display:flex;align-items:center;gap:12px}.file strong{font-size:15px;display:block}
.file small{color:#6B7280;font-size:13px}
.dot{width:10px;height:10px;border-radius:50%;background:#16A34A;box-shadow:0 0 0 4px rgba(22,163,74,.14)}
.btn{background:#2563EB;color:#fff;padding:9px 18px;font-size:14px}
.empty{background:#F8FAFC;border:1px dashed #CBD5E1;border-radius:14px;padding:22px;
text-align:center;color:#6B7280;margin-top:14px}
ol{margin:8px 0 0;padding-left:22px;color:#374151}
input{width:100%;padding:14px;border:1px solid #CBD5E1;border-radius:12px;
font-size:18px;letter-spacing:.2em;margin:12px 0}
form .primary{background:#2563EB;color:#fff;width:100%}
.warn{margin-top:22px;background:#FFFBEB;border:1px solid #FDE68A;color:#92400E;
border-radius:14px;padding:16px 18px;font-size:14px}
@media(max-width:600px){.wrap{padding:18px 14px 40px}.hero{padding:26px 22px}h1{font-size:24px}}
</style></head><body><div class="wrap">${content()}</div></body></html>
""".trimIndent()
}
