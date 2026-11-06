package org.aggregated.player.platform.web

import org.aggregated.player.domain.usecase.MediaCandidateFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_IN_FLIGHT = 4
private const val MAX_TRACKED_URLS = 256
private const val TIMEOUT_MS = 3_000
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val RANGE_HEADER = "Range"
private const val RANGE_PROBE_VALUE = "bytes=0-0"

/**
 * C2「响应头精确判定」的 Android 实现（阶段 4-B）。
 *
 * **为什么不是文档里设想的 `androidx.webkit shouldInterceptResponse`**：AndroidX WebView 没有
 * 「拿到服务器响应头」的公开回调（`WebViewClientCompat` 只提供 `shouldInterceptRequest`）。
 * 唯一能拿到 Content-Type 的办法是**自己发一次请求**。这里刻意选择「零侵入探测」：
 * 在 `shouldInterceptRequest` 里照常返回 null 放行，另起守护线程发一次轻量 HEAD
 * （服务器不支持 HEAD 时退化为 `Range: bytes=0-0` 的 GET，只读响应头、不读 body），
 * 命中媒体 MIME 才二次 [MediaCandidateFactory.sniff] 入队。
 * 这样绝不会替换 WebView 的响应流，也就不会破坏页面脚本、跨域与重定向。
 *
 * 代价是一条额外的探测请求，因此三层收敛：共享层纯函数 `shouldProbeMime` 只挑可疑 URL、
 * [seen] 按 URL 去重、[inFlight] 限制并发。全部失败静默降级——C2 是 C1 的补充而非主通道。
 * 探测请求必须复用同一份 headers（Referer/Cookie/UA），否则防盗链站点会直接 403。
 */
internal object AndroidResponseProbe {

    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = AtomicInteger(0)

    fun probe(
        tabId: String,
        siteId: String?,
        url: String,
        headers: Map<String, String>,
        pageUrl: String?,
    ) {
        if (!MediaCandidateFactory.shouldProbeMime(url)) return
        // 长时间浏览后集合会无限增长；清空是「尽力而为」策略下可接受的去重精度损失
        if (seen.size > MAX_TRACKED_URLS) seen.clear()
        if (!seen.add(url)) return
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return
        }
        val thread = Thread({
            runCatching {
                val mime = probeMime(url, headers)
                if (mime != null) {
                    val candidate = MediaCandidateFactory.sniff(
                        tabId = tabId,
                        siteId = siteId,
                        url = url,
                        requestHeaders = headers,
                        pageUrl = pageUrl,
                        mimeType = mime,
                    )
                    if (candidate != null) MediaCaptureBridge.onCandidate?.invoke(candidate)
                }
            }
            inFlight.decrementAndGet()
        }, "agg-mime-probe")
        thread.isDaemon = true
        thread.start()
    }

    private fun probeMime(url: String, headers: Map<String, String>): String? =
        requestMime(url, headers, "HEAD")
            ?: requestMime(url, headers + (RANGE_HEADER to RANGE_PROBE_VALUE), "GET")

    private fun requestMime(url: String, headers: Map<String, String>, method: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            val code = connection.responseCode
            if (code in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                connection.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}
