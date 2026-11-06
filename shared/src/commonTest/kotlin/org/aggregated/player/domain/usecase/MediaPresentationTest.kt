package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.SniffedMedia
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaPresentationTest {

    private fun media(
        url: String,
        kind: MediaKind = MediaKind.HLS,
        mimeType: String? = null,
        viaJsHook: Boolean = false,
        title: String? = null,
        discoveredAt: Long = 0L,
    ) = SniffedMedia(
        id = "media-$url",
        tabId = "tab-1",
        siteId = null,
        url = url,
        kind = kind,
        mimeType = mimeType,
        discoveredAt = discoveredAt,
        viaJsHook = viaJsHook,
        title = title,
    )

    @Test
    fun displayNamePrefersPageTitle() {
        assertEquals(
            "第01集",
            mediaDisplayName(media("https://cdn.example.com/a/index.m3u8", title = "第01集")),
        )
    }

    @Test
    fun displayNameUsesExtensionSegmentWithItsParent() {
        // 实测形态：目录段是 32 位签名 hash，末段是泛用 index.m3u8；取两段并截断 hash
        assertEquals(
            "848882ba5b8e3f84de17a2a6/index.m3u8",
            mediaDisplayName(
                media("https://vip.example.com/20241112/848882ba5b8e3f84de17a2a68b47f8b8/index.m3u8?sign=abc"),
            ),
        )
    }

    @Test
    fun displayNameHandlesDirectFile() {
        assertEquals(
            "video/bigfile.mp4",
            mediaDisplayName(media("https://cdn.example.com/video/bigfile.mp4", kind = MediaKind.DIRECT)),
        )
        assertEquals(
            "playlist",
            mediaDisplayName(media("https://cdn.example.com/api/playlist", kind = MediaKind.DIRECT)),
        )
    }

    @Test
    fun displayNameForBlobIsFixedLabel() {
        assertEquals("网页内存流", mediaDisplayName(media("blob:https://x/uuid", kind = MediaKind.MSE_BLOB)))
    }

    @Test
    fun sortingPutsHeaderConfirmedDirectLinkFirstAndJsHookLast() {
        val sorted = listOf(
            media("https://a/js.m3u8", viaJsHook = true, discoveredAt = 30),
            media("https://b/confirmed.mp4", kind = MediaKind.DIRECT, mimeType = "video/mp4", discoveredAt = 10),
            media("https://c/guess.m3u8", discoveredAt = 20),
        ).sortedForDisplay()
        assertEquals(
            listOf("https://b/confirmed.mp4", "https://c/guess.m3u8", "https://a/js.m3u8"),
            sorted.map { it.url },
        )
    }

    @Test
    fun sortingIsNewestFirstWithinSameConfidence() {
        val sorted = listOf(
            media("https://a/old.m3u8", discoveredAt = 10),
            media("https://b/new.m3u8", discoveredAt = 20),
        ).sortedForDisplay()
        assertEquals(listOf("https://b/new.m3u8", "https://a/old.m3u8"), sorted.map { it.url })
    }

    @Test
    fun hintsReflectEvidenceStrength() {
        assertEquals("响应头确认", mediaSourceHint(media("https://a/x.mp4", mimeType = "video/mp4")))
        assertEquals("JS 捕获", mediaSourceHint(media("https://a/x.m3u8", viaJsHook = true)))
        assertEquals("URL 特征", mediaSourceHint(media("https://a/x.m3u8")))
        assertEquals("video/mp4", mediaTypeHint(media("https://a/x.mp4", mimeType = "video/mp4")))
        assertEquals("HLS 清单", mediaTypeHint(media("https://a/x.m3u8")))
    }
}
