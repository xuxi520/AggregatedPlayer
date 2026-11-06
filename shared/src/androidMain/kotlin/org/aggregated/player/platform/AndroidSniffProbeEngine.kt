package org.aggregated.player.platform

import org.aggregated.player.domain.repository.SniffProbeEngine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000
private const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2 MB：清单是文本，超限即视为脏响应
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

/** 探测读取缓冲区 8 KiB：与 OkHttp 默认一致，纯 IO 吞吐用，无语义可命名。 */
private const val READ_BUFFER_BYTES = 8 * 1024

/**
 * 清单探测的 Android 实现：`HttpURLConnection` 直连 GET。
 *
 * 与 [AndroidResponseProbe] 同款纪律——复用嗅探到的请求头（防盗链站点 403 大多是因为
 * 少了 Referer/Cookie），全程异常自兜（任何失败返回 `null`），读满上限即停不收尾包。
 */
internal class AndroidSniffProbeEngine : SniffProbeEngine {

    override fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        connection.useTextResponse()
    }.getOrNull()

    private fun HttpURLConnection.useTextResponse(): String? {
        return try {
            if (responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) return null
            val body = inputStream.use { input ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                val out = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BODY_BYTES) return null
                }
                out.toString("UTF-8")
            }
            body.takeIf { it.isNotBlank() }
        } finally {
            disconnect()
        }
    }
}
