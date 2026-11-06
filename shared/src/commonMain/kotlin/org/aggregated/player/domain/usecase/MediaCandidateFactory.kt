package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.MediaKind

/**
 * 把平台层观察到的一次资源请求，构造成 [SniffCandidate]。
 *
 * **纯函数**：所有外部状态（`CookieManager`、WebView UA、页面 URL、请求头）都由调用方以参数传入，
 * 内部不持有任何可变字段、不读平台 API。`shouldInterceptRequest` 是多线程并发回调，
 * 这里无共享状态即可保证「同一输入永远同一输出」；去重与队列的并发安全由
 * [MediaSniffer.ingest] 的 `MutableStateFlow.update` CAS 负责，两者职责不重叠。
 *
 * 放在 commonMain 还有一个目的：防盗链要素（Referer/Cookie/UA）的合并规则能被 `commonTest`
 * 直接覆盖，而平台侧只保留「怎么采集」这一层薄适配。
 */
object MediaCandidateFactory {

    /** 无扩展名但可能是播放列表/流接口的路径关键词（C2 才需要探测响应头）。 */
    private val PROBE_KEYWORDS = listOf(
        "m3u8", "playlist", "manifest", "mpd", "hls", "dash", "stream", "video", "play", "live", "media",
    )

    /** 明显是静态资源或轮询上报的 URL：探测它们只会白白发请求。 */
    private val PROBE_EXCLUDES = listOf(
        ".js", ".css", ".json", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".html", ".htm", ".xml", ".map", ".txt",
        "heartbeat", "ping", "analytics", "track", "report",
    )

    /**
     * C1 漏掉、但值得用 C2 异步探测一次响应头的 URL（如 `playlist?id=42` 返回 `application/vnd.apple.mpegurl`）。
     *
     * **纯函数**：只按 URL 形状判断，不碰网络。平台层据此决定是否发那一次轻量 HEAD，
     * 避免对每个请求都做探测（WebView 的资源请求量级很大）。
     */
    fun shouldProbeMime(url: String): Boolean {
        if (MediaKindClassifier.classify(url) != MediaKind.UNKNOWN) return false
        val path = url.substringBefore('?').lowercase()
        if (PROBE_EXCLUDES.any { path.endsWith(it) }) return false
        val lower = url.lowercase()
        if (PROBE_EXCLUDES.any { lower.contains(it) }) return false
        return PROBE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * 以请求头为底，补齐外部播放必需的防盗链要素（C1 构造候选、C2 复用同一份头去探测）。
     *
     * HTTP 头名大小写不敏感，但 `Map` 的键是区分大小写的：先按忽略大小写查一遍再补，
     * 避免出现 `referer` 与 `Referer` 两个同名头。Cookie 通常不在 `WebResourceRequest.requestHeaders`
     * 里（由 WebView 的 `CookieManager` 内部附加），所以必须允许单独传入。
     */
    fun buildHeaders(
        requestHeaders: Map<String, String>,
        cookieHeader: String?,
        userAgent: String?,
        pageUrl: String?,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>(requestHeaders)
        putIfAbsent(merged, "Referer", pageUrl)
        putIfAbsent(merged, "Cookie", cookieHeader)
        putIfAbsent(merged, "User-Agent", userAgent)
        return merged
    }

    /**
     * @return 判定为 [MediaKind.UNKNOWN] 的噪声返回 `null`（不告警，正常浏览里绝大多数请求都不是媒体流）。
     */
    fun sniff(
        tabId: String,
        siteId: String?,
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
        cookieHeader: String? = null,
        userAgent: String? = null,
        pageUrl: String? = null,
        mimeType: String? = null,
        contentLengthBytes: Long? = null,
        viaJsHook: Boolean = false,
        title: String? = null,
    ): SniffCandidate? {
        val kind = MediaKindClassifier.classify(url, mimeType)
        if (kind == MediaKind.UNKNOWN) return null
        return SniffCandidate(
            tabId = tabId,
            siteId = siteId,
            url = url,
            pageUrl = pageUrl,
            mimeType = mimeType,
            headers = buildHeaders(requestHeaders, cookieHeader, userAgent, pageUrl),
            contentLengthBytes = contentLengthBytes,
            viaJsHook = viaJsHook,
            title = title,
        )
    }

    private fun putIfAbsent(target: MutableMap<String, String>, name: String, value: String?) {
        if (value.isNullOrBlank()) return
        if (target.keys.any { it.equals(name, ignoreCase = true) }) return
        target[name] = value
    }
}
