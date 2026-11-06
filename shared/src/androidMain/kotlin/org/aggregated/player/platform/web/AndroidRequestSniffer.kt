package org.aggregated.player.platform.web

import android.webkit.CookieManager
import org.aggregated.player.domain.usecase.MediaCandidateFactory

/**
 * Android 端「请求 → 嗅探候选」的薄适配层。
 *
 * 覆盖三条通道：C1 网络拦截回调（[observe]）、C2 响应头异步探测（[AndroidResponseProbe]）、
 * C3 JS Hook 回传（[observeJs]）。三条都收敛到共享层纯函数 [MediaCandidateFactory.sniff]
 * 与 [MediaCaptureBridge]，判定、去重、队列并发安全不在这里。
 *
 * 不持有任何可变字段：C1 由 WebView 多个工作线程并发回调、C3 由 JavaBridge 线程回调，
 * 保持无共享状态是最省心的正确性保证；Cookie 每次通过只读的 `CookieManager` 取。
 *
 * 为什么单独补 Cookie：WebView 把 Cookie 交给 `CookieManager` 内部附加，
 * `WebResourceRequest.requestHeaders` 通常不含它；缺了 Cookie 会让带防盗链的直链在外部播放时直接 403。
 * UA 则在 `applyProfile()`（主线程）缓存后由调用方传入，本回调严禁再读 WebView。
 */
internal object AndroidRequestSniffer {

    fun observe(
        tabId: String,
        siteId: String?,
        url: String,
        requestHeaders: Map<String, String>,
        userAgent: String?,
        pageUrl: String?,
    ) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        // 合并一次，C1 与 C2 复用同一份带防盗链要素的头（buildHeaders 是纯函数）
        val headers = MediaCandidateFactory.buildHeaders(requestHeaders, cookie, userAgent, pageUrl)
        val candidate = MediaCandidateFactory.sniff(
            tabId = tabId,
            siteId = siteId,
            url = url,
            requestHeaders = headers,
            pageUrl = pageUrl,
        )
        if (candidate != null) {
            MediaCaptureBridge.onCandidate?.invoke(candidate)
            return
        }
        // C1 判不出类型：只对「无扩展名但像播放接口」的 URL 做一次零侵入响应头探测
        AndroidResponseProbe.probe(
            tabId = tabId,
            siteId = siteId,
            url = url,
            headers = headers,
            pageUrl = pageUrl,
        )
    }

    /**
     * C3：页面 JS Hook（fetch / XHR）观察到的媒体 URL，已在脚本里用 `<a>` 解析成绝对地址。
     *
     * 没有 `WebResourceRequest`，所以请求头只能由页面上下文重建：Referer 取页面 URL、
     * Cookie 取 `CookieManager`、UA 取主线程缓存。标记 `viaJsHook = true`，
     * UI 据此标注置信度较低；`MediaKindClassifier` 会丢掉不匹配的类型，因此噪声不会入队。
     */
    fun observeJs(
        tabId: String,
        siteId: String?,
        url: String,
        pageUrl: String?,
        userAgent: String?,
    ) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val headers = MediaCandidateFactory.buildHeaders(emptyMap(), cookie, userAgent, pageUrl)
        val candidate = MediaCandidateFactory.sniff(
            tabId = tabId,
            siteId = siteId,
            url = url,
            requestHeaders = headers,
            pageUrl = pageUrl,
            viaJsHook = true,
        ) ?: return
        MediaCaptureBridge.onCandidate?.invoke(candidate)
    }
}
