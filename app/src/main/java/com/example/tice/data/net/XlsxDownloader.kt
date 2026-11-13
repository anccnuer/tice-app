package com.example.tice.data.net

import com.example.tice.xlsx.XlsxCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 通过 HTTP/HTTPS 地址下载 Excel。带超时、体积上限与进度回调，错误只记状态码不记内容。 */
class XlsxDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        val text = url.trim()
        require(text.isNotEmpty()) { "请填写下载地址" }
        val request = try {
            Request.Builder().url(text).build()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("下载地址格式不正确，请填写完整 http:// 或 https:// 地址")
        }
        require(request.url.scheme in setOf("http", "https")) { "仅支持 http/https 下载地址" }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("下载内容为空")
            val totalHint = body.contentLength()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= XlsxCodec.MAX_TEMPLATE_BYTES) { "下载的表格超过 24MB 上限" }
                    output.write(buffer, 0, count)
                    if (totalHint > 0) onProgress((total * 100L / totalHint).toInt().coerceAtMost(99))
                }
            }
            val bytes = output.toByteArray()
            require(bytes.isNotEmpty()) { "下载的文件为空" }
            onProgress(100)
            bytes
        }
    }
}
