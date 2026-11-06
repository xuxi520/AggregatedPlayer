package org.aggregated.player.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UrlPathPrefix]：防盗链头「按 URL 精确匹配」的纯逻辑。
 *
 * 重点验证两件事：**路径段级边界**（不误命中同前缀兄弟，如 `/a/ep1` 不能命中 `/a/ep10`）
 * 与**最长命中优先**（分片命中目录前缀、清单命中更长的完整 path）。
 */
class UrlPathPrefixTest {

    @Test
    fun matchesExactPath() {
        assertTrue(UrlPathPrefix.matches("/a/index.m3u8", "/a/index.m3u8"))
    }

    @Test
    fun matchesChildPath() {
        assertTrue(UrlPathPrefix.matches("/a", "/a/seg1.ts"))
        assertTrue(UrlPathPrefix.matches("/a/", "/a/seg1.ts"))
    }

    @Test
    fun doesNotMatchSiblingWithSamePrefix() {
        // 防回归：裸字符串前缀会让 /video/ep1 误命中 /video/ep10
        assertFalse(UrlPathPrefix.matches("/a/ep1", "/a/ep10/seg.ts"))
    }

    @Test
    fun emptyPrefixMatchesEverything() {
        assertTrue(UrlPathPrefix.matches("", "/whatever/seg.ts"))
    }

    @Test
    fun unrelatedPathDoesNotMatch() {
        assertFalse(UrlPathPrefix.matches("/a", "/b/seg.ts"))
    }

    @Test
    fun bestMatchPicksLongestPrefix() {
        val candidates = listOf("/a", "/a/index.m3u8", "/b")
        // 清单自身命中完整 path
        assertEquals("/a/index.m3u8", UrlPathPrefix.bestMatch(candidates, "/a/index.m3u8"))
        // 分片命中所在目录
        assertEquals("/a", UrlPathPrefix.bestMatch(candidates, "/a/seg1.ts"))
        // 另一部剧集命中自己的目录，不被前者抢走
        assertEquals("/b", UrlPathPrefix.bestMatch(candidates, "/b/seg1.ts"))
    }

    @Test
    fun bestMatchReturnsNullWhenNothingMatches() {
        assertNull(UrlPathPrefix.bestMatch(listOf("/a", "/b"), "/c/seg.ts"))
    }
}
