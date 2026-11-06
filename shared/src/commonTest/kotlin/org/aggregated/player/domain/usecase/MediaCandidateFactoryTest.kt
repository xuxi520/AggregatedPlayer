package org.aggregated.player.domain.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MediaCandidateFactory] 是 `shouldInterceptRequest` 多线程并发调用的唯一入口，
 * 重点验证两件事：防盗链头合并规则正确、纯函数无共享状态（并发同输入必同输出）。
 */
class MediaCandidateFactoryTest {

    private fun sniff(
        url: String = "https://cdn.example.com/movie.mp4",
        requestHeaders: Map<String, String> = emptyMap(),
        cookieHeader: String? = null,
        userAgent: String? = null,
        pageUrl: String? = null,
        mimeType: String? = null,
    ): SniffCandidate? = MediaCandidateFactory.sniff(
        tabId = "tab-1",
        siteId = "site-1",
        url = url,
        requestHeaders = requestHeaders,
        cookieHeader = cookieHeader,
        userAgent = userAgent,
        pageUrl = pageUrl,
        mimeType = mimeType,
    )

    @Test
    fun `直链 MP4 应入队且归属标签与站点`() {
        val candidate = assertNotNull(sniff())
        assertEquals("https://cdn.example.com/movie.mp4", candidate.url)
        assertEquals("tab-1", candidate.tabId)
        assertEquals("site-1", candidate.siteId)
    }

    @Test
    fun `非媒体请求应静默丢弃`() {
        assertNull(sniff(url = "https://cdn.example.com/app.js"))
        assertNull(sniff(url = "https://cdn.example.com/style.css"))
    }

    @Test
    fun `缺什么补什么 Referer Cookie UA 且保留原有请求头`() {
        val candidate = assertNotNull(
            sniff(
                requestHeaders = mapOf("Origin" to "https://site.example.com", "Accept" to "*/*"),
                cookieHeader = "sid=abc; token=1",
                userAgent = "AggregatedPlayer/1.0",
                pageUrl = "https://site.example.com/play/1",
            ),
        )
        assertEquals("https://site.example.com/play/1", candidate.headers["Referer"])
        assertEquals("sid=abc; token=1", candidate.headers["Cookie"])
        assertEquals("AggregatedPlayer/1.0", candidate.headers["User-Agent"])
        assertEquals("https://site.example.com", candidate.headers["Origin"])
        assertEquals("*/*", candidate.headers["Accept"])
    }

    @Test
    fun `已有同名头忽略大小写不应重复添加`() {
        val candidate = assertNotNull(
            sniff(
                requestHeaders = mapOf("referer" to "https://from-header.example.com", "cookie" to "own=1"),
                cookieHeader = "from-manager=1",
                userAgent = "UA",
                pageUrl = "https://page.example.com",
            ),
        )
        assertEquals("https://from-header.example.com", candidate.headers["referer"])
        assertEquals("own=1", candidate.headers["cookie"])
        assertNull(candidate.headers["Referer"])
        assertNull(candidate.headers["Cookie"])
        assertEquals("UA", candidate.headers["User-Agent"])
    }

    @Test
    fun `空白的头值不应补进结果`() {
        val candidate = assertNotNull(sniff(cookieHeader = "  ", userAgent = "", pageUrl = null))
        assertNull(candidate.headers["Cookie"])
        assertNull(candidate.headers["User-Agent"])
        assertNull(candidate.headers["Referer"])
    }

    @Test
    fun `C2 响应头通道可用 MIME 判定无扩展名的 HLS`() {
        val candidate = assertNotNull(
            sniff(
                url = "https://cdn.example.com/playlist?id=42",
                mimeType = "application/vnd.apple.mpegurl",
            ),
        )
        assertEquals("application/vnd.apple.mpegurl", candidate.mimeType)
    }

    @Test
    fun `HLS 与 DASH 请求都能入队`() {
        assertNotNull(sniff(url = "https://cdn.example.com/index.m3u8"))
        assertNotNull(sniff(url = "https://cdn.example.com/manifest.mpd"))
    }

    @Test
    fun `C2 只挑无扩展名但像播放接口的 URL`() {
        assertTrue(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/playlist?id=42"))
        assertTrue(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/api/play?vid=1"))
        // C1 已能判出的、静态资源、普通页面、轮询上报、内存流都不该再发探测请求
        assertFalse(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/index.m3u8"))
        assertFalse(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/app.js"))
        assertFalse(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/style.css"))
        assertFalse(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/home"))
        assertFalse(MediaCandidateFactory.shouldProbeMime("https://cdn.example.com/video/heartbeat?t=1"))
        assertFalse(MediaCandidateFactory.shouldProbeMime("blob:https://site.example.com/abc"))
    }

    @Test
    fun `C3 候选应标记 viaJsHook 供 UI 区分置信度`() {
        val candidate = assertNotNull(
            MediaCandidateFactory.sniff(
                tabId = "tab-1",
                siteId = null,
                url = "https://cdn.example.com/index.m3u8",
                viaJsHook = true,
            ),
        )
        assertTrue(candidate.viaJsHook)
    }

    @Test
    fun `并发调用同一输入结果恒定 不依赖共享状态`() = runTest {
        val expected = sniff(
            requestHeaders = mapOf("Origin" to "https://site.example.com"),
            cookieHeader = "sid=abc",
            userAgent = "UA",
            pageUrl = "https://site.example.com/play/1",
        )
        val results = List(CONCURRENT_CALLS) {
            async(Dispatchers.Default) {
                sniff(
                    requestHeaders = mapOf("Origin" to "https://site.example.com"),
                    cookieHeader = "sid=abc",
                    userAgent = "UA",
                    pageUrl = "https://site.example.com/play/1",
                )
            }
        }.awaitAll()
        assertTrue(results.all { it == expected }, "并发调用产生了不一致的候选，说明 sniff 里存在共享状态")
    }

    private companion object {
        const val CONCURRENT_CALLS = 64
    }
}
