package org.aggregated.player

import org.aggregated.player.domain.model.UriParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `UriParts` 是用手写解析器，规则匹配与嗅探都建立在它的输出上，
 * 所以这里覆盖的重点是「容易静默出错」的边界，而不是常规 URL。
 */
class UriPartsTest {

    @Test
    fun parsesFullUrl() {
        val parts = UriParts.parse("https://Player.Example.COM:8443/v/1.mov?token=abc#fragment")
        assertEquals("https", parts?.scheme)
        assertEquals("player.example.com", parts?.host)
        assertEquals(8443, parts?.port)
        assertEquals("/v/1.mov", parts?.path)
        assertEquals("token=abc", parts?.query)
    }

    @Test
    fun blankInputIsNotParsable() {
        assertNull(UriParts.parse(""))
        assertNull(UriParts.parse("   "))
    }

    @Test
    fun missingPathNormalizesToSlash() {
        // 规则里的路径前缀都以 / 起始，这里不归一化会让 startsWith 判定随机失效
        assertEquals("/", UriParts.parse("https://example.com")?.path)
    }

    @Test
    fun userInfoDoesNotLeakIntoHost() {
        val parts = UriParts.parse("https://user:pass@video.example.com/a.mp4")
        assertEquals("video.example.com", parts?.host)
        assertNull(parts?.port)
    }

    @Test
    fun portWithNonDigitsIsIgnored() {
        val parts = UriParts.parse("https://example.com:80x/a")
        assertEquals("example.com", parts?.host)
        assertNull(parts?.port)
    }

    @Test
    fun ipv6HostIsExtracted() {
        val parts = UriParts.parse("http://[::1]:8080/segment.ts")
        assertEquals("::1", parts?.host)
        assertEquals(8080, parts?.port)
        assertEquals("/segment.ts", parts?.path)
    }

    @Test
    fun blobUrlKeepsInnerHost() {
        // MSE/Blob 地址形如 blob:https://site/uuid，判定类型与展示都需要内层主机
        val parts = UriParts.parse("blob:https://media.example.com/6f2c-4a1e")
        assertEquals("media.example.com", parts?.host)
    }

    @Test
    fun dataUrlHasNoHost() {
        val parts = UriParts.parse("data:video/mp4;base64,AAAA")
        assertEquals("data", parts?.scheme)
        assertNull(parts?.host)
    }

    @Test
    fun extensionComesFromLastSegment() {
        assertEquals("m3u8", UriParts.parse("https://e.com/index.m3u8?sign=1")?.fileExtension)
        assertEquals("mp4", UriParts.parse("https://e.com/a/b/c.MP4")?.fileExtension)
        // 版本片段不是扩展名：长度与字符合法性检查把它挡掉
        assertEquals("", UriParts.parse("https://e.com/v1.2/video")?.fileExtension)
        assertEquals("", UriParts.parse("https://e.com/no-extension")?.fileExtension)
    }

    @Test
    fun trailingDotIsTrimmedFromHost() {
        // FQDN 写法 `example.com.` 必须与 `example.com` 等价，否则域名规则会漏匹配
        assertEquals("example.com", UriParts.parse("https://example.com./a")?.host)
    }

    @Test
    fun hostMatchesRespectsDotBoundary() {
        val parts = assertNotNull(UriParts.parse("https://player.ads.example.com/x.js"))
        assertTrue(parts.hostMatches("ads.example.com"))
        assertTrue(parts.hostMatches("example.com"))
        // 关键反例：前缀相似但不能算命中，否则会误伤大量正常域名
        assertTrue(!parts.hostMatches("notexample.com"))
        assertTrue(!parts.hostMatches("ample.com"))
    }

    @Test
    fun hostMatchesIsFalseWhenHostMissing() {
        assertTrue(!assertNotNull(UriParts.parse("data:text/html,hi")).hostMatches("example.com"))
    }
}
