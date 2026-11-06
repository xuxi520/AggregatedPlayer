package org.aggregated.player.platform

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.aggregated.player.domain.repository.SniffProbeEngine

private const val PROBE_CONNECT_TIMEOUT_MS = 10_000
private const val PROBE_READ_TIMEOUT_MS = 15_000
private const val PROBE_BUFFER_BYTES = 8 * 1024
private const val PROBE_MAX_BYTES = 2 * 1024 * 1024
private const val HTTP_OK = 200

/**
 * 桌面端清单探测（与 Android 侧同款语义）。
 *
 * 复用嗅探到的请求头（Referer/Cookie/UA），否则防盗链站点直接 403；
 * 严格限时限长（清单是文本，超限即放弃），失败一律静默返回 `null`——
 * 调用方在共享层协程里，无法感知平台异常类型。
 *
 * 注意：桌面端当前没有嗅探来源（无内嵌内核即无请求监听），本实现是**能力就位**——
 * 将来接入内嵌内核并把嗅探到的请求头喂进来即可直接生效。
 */
private object JvmSniffProbeEngine : SniffProbeEngine {

    override fun fetchText(url: String, headers: Map<String, String>): String? =
        runCatching { fetch(url, headers) }.getOrNull()

    private fun fetch(url: String, headers: Map<String, String>): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            readTimeout = PROBE_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            if (connection.responseCode != HTTP_OK) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(PROBE_BUFFER_BYTES)
                val out = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > PROBE_MAX_BYTES) return null
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}

actual fun createSniffProbeEngine(): SniffProbeEngine = JvmSniffProbeEngine
