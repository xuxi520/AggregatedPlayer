package org.aggregated.player

import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.usecase.MediaKindClassifier
import org.aggregated.player.domain.usecase.MediaSniffer
import org.aggregated.player.domain.usecase.SniffCandidate
import org.aggregated.player.domain.usecase.forTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 嗅探链路（设计文档 §4.4）的用例。
 *
 * 关注三件事：类型判定是否符合真实站点的写法、去重是否会把同一条流刷成一堆记录、
 * 队列是否会无界增长。这三点直接决定「使用外部引擎播放」按钮的行为是否可预期。
 */
class MediaSnifferTest {

    private val logger = FakeLogger()
    private val clock = FakeClock()

    private fun candidate(
        url: String,
        mimeType: String? = null,
        tabId: String = "tab-1",
        headers: Map<String, String> = emptyMap(),
        contentLength: Long? = null,
    ) = SniffCandidate(
        tabId = tabId,
        siteId = "site-a",
        url = url,
        pageUrl = "https://site-a.com/play/1",
        mimeType = mimeType,
        headers = headers,
        contentLengthBytes = contentLength,
    )

    // ---------- 类型判定 ----------

    @Test
    fun classifiesByExtensionWhenNoMimeTypeAvailable() {
        // C1 通道只能看到 URL，这是 P0 阶段的主要判定手段
        assertEquals(MediaKind.HLS, MediaKindClassifier.classify("https://e.com/index.m3u8"))
        assertEquals(MediaKind.HLS, MediaKindClassifier.classify("https://e.com/index.m3u8?sign=abc"))
        assertEquals(MediaKind.DASH, MediaKindClassifier.classify("https://e.com/manifest.mpd"))
        assertEquals(MediaKind.DIRECT, MediaKindClassifier.classify("https://e.com/movie.mp4"))
        assertEquals(MediaKind.MSE_BLOB, MediaKindClassifier.classify("blob:https://e.com/6f2c-4a1e"))
        assertEquals(MediaKind.UNKNOWN, MediaKindClassifier.classify("https://e.com/page.html"))
    }

    @Test
    fun mimeTypeWinsOverExtension() {
        // 站点常见把 HLS 挂在 .m3u8.txt 或动态地址上，真实响应头比扩展名可靠
        assertEquals(
            MediaKind.HLS,
            MediaKindClassifier.classify("https://e.com/stream?id=1", "application/vnd.apple.mpegurl"),
        )
        assertEquals(
            MediaKind.DASH,
            MediaKindClassifier.classify("https://e.com/stream", "application/dash+xml"),
        )
        // charset 后缀不能干扰判定
        assertEquals(MediaKind.DIRECT, MediaKindClassifier.classify("https://e.com/x", "video/mp4; charset=binary"))
    }

    @Test
    fun audioStreamsAreRecognised() {
        assertEquals(MediaKind.DIRECT, MediaKindClassifier.classify("https://e.com/podcast", "audio/mpeg"))
    }

    // ---------- 入队与去重 ----------

    @Test
    fun unknownCandidatesAreDropped() {
        val sniffer = MediaSniffer(logger, clock::now)
        assertNull(sniffer.ingest(candidate("https://e.com/app.js")))
        assertTrue(sniffer.media.value.isEmpty())
    }

    @Test
    fun repeatedUrlIsMergedNotDuplicated() {
        val sniffer = MediaSniffer(logger, clock::now)
        // 先由 C1 猜出类型（无 MIME、无体积）
        val first = assertNotNull(sniffer.ingest(candidate("https://e.com/master.m3u8")))
        assertEquals(MediaKind.HLS, first.kind)
        assertNull(first.estimatedSizeBytes)

        // 再由 C2 拿到真实响应头：合并而不是新增记录
        val second = assertNotNull(sniffer.ingest(candidate("https://e.com/master.m3u8", "application/x-mpegurl", contentLength = 4096)))
        assertEquals(1, sniffer.media.value.size)
        assertEquals(first.id, second.id)
        assertEquals("application/x-mpegurl", second.mimeType)
        assertEquals(4096L, second.estimatedSizeBytes)
        // 首次发现时间必须保留：队列排序与过期判断都依赖它
        assertEquals(first.discoveredAt, second.discoveredAt)
    }

    @Test
    fun newestResultsComeFirstAndQueueIsBounded() {
        val sniffer = MediaSniffer(logger, clock::now, capacity = 3)
        listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4", "e.mp4").forEach { sniffer.ingest(candidate("https://e.com/$it")) }

        val queue = sniffer.media.value
        assertEquals(3, queue.size)
        assertEquals("https://e.com/e.mp4", queue.first().url)
        // 超限丢的是最旧的：旧地址多半已过期，留着只会让用户点了失败
        assertFalse(queue.any { it.url.endsWith("a.mp4") })
    }

    @Test
    fun sameUrlInDifferentTabsStaysSeparate() {
        // 两个标签看同一个站点是常见场景，队列按 (tabId, url) 去重才不会互相顶掉
        val sniffer = MediaSniffer(logger, clock::now)
        sniffer.ingest(candidate("https://e.com/x.mp4", tabId = "tab-1"))
        sniffer.ingest(candidate("https://e.com/x.mp4", tabId = "tab-2"))
        assertEquals(2, sniffer.media.value.size)
    }

    @Test
    fun clearForTabOnlyAffectsThatTab() {
        val sniffer = MediaSniffer(logger, clock::now)
        sniffer.ingest(candidate("https://e.com/x.mp4", tabId = "tab-1"))
        sniffer.ingest(candidate("https://e.com/y.mp4", tabId = "tab-2"))
        sniffer.clearForTab("tab-1")

        assertEquals(listOf("tab-2"), sniffer.media.value.map { it.tabId })
    }

    @Test
    fun latestPlayableSkipsMemoryStreams() {
        val sniffer = MediaSniffer(logger, clock::now)
        sniffer.ingest(candidate("https://e.com/real.mp4"))
        // blob 排在后面（更新），但它不能当外部播放的目标
        val blob = assertNotNull(sniffer.ingest(candidate("blob:https://e.com/6f2c")))
        assertEquals(MediaKind.MSE_BLOB, blob.kind)
        assertFalse(blob.supportsExternalPlayback)

        val target = assertNotNull(sniffer.latestPlayable("tab-1"))
        assertEquals("https://e.com/real.mp4", target.url)
    }

    @Test
    fun headersAreCarriedThroughForAntiLeech() {
        // Referer/Cookie 是外部播放能否成功的关键，嗅探阶段就必须带上（§4.4）
        val sniffer = MediaSniffer(logger, clock::now)
        val media = assertNotNull(
            sniffer.ingest(candidate("https://e.com/x.mp4", headers = mapOf("Referer" to "https://site-a.com/play/1", "Cookie" to "token=1"))),
        )
        assertEquals("https://site-a.com/play/1", media.headers["Referer"])
        // 日志里只允许出现键名，不允许出现 Cookie 值
        assertFalse(logger.entries.any { it.message.contains("token=1") })
        assertEquals("[Cookie, Referer]", media.headersForLog())
    }

    @Test
    fun forTabOnlyReturnsCurrentTabResults() {
        val sniffer = MediaSniffer(logger, clock::now)
        sniffer.ingest(candidate("https://a.com/a.mp4", tabId = "tab-1"))
        sniffer.ingest(candidate("https://b.com/b.mp4", tabId = "tab-2"))
        val all = sniffer.media.value

        assertEquals(2, all.size)
        assertEquals("https://a.com/a.mp4", all.forTab("tab-1").single().url)
        assertEquals("https://b.com/b.mp4", all.forTab("tab-2").single().url)
        assertTrue(all.forTab(null).isEmpty(), "无活动标签时不展示任何候选")
        assertTrue(all.forTab("tab-3").isEmpty())
    }
}
