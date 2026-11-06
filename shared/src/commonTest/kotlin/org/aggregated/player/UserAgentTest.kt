package org.aggregated.player

import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.platform.buildUserAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * User-Agent 组装的用例（设计文档 §4.4）。
 *
 * 用真实的 Android WebView UA 片段作为输入，而不是随便编的字符串：
 * UA 的每一个 token 都可能被站点拿去判客户端类型，用例必须贴近现实。
 */
class UserAgentTest {

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"

    @Test
    fun mobileModeKeepsBaseAndAppendsToken() {
        val ua = buildUserAgent(mobileUa, UserAgentMode.MOBILE, "AggregatedPlayer/1.0")

        assertTrue(ua.startsWith(mobileUa))
        assertTrue(ua.endsWith("AggregatedPlayer/1.0"))
        // 移动标记必须还在：很多站点靠它决定给不给移动版播放器
        assertTrue(ua.contains("Mobile Safari"))
    }

    @Test
    fun desktopModeStripsMobileTokenOnly() {
        val ua = buildUserAgent(mobileUa, UserAgentMode.DESKTOP_SIMULATE, "AggregatedPlayer/1.0")

        assertFalse(ua.contains("Mobile Safari"))
        assertTrue(ua.contains("Safari/537.36"))
        // 机型与系统信息要保留：删掉它们更容易被站点风控，也更难排查「为什么这站打不开」
        assertTrue(ua.contains("Android 14"))
        assertTrue(ua.contains("Pixel 8"))
    }

    @Test
    fun missingOrBlankTokenAddsNothing() {
        assertEquals(mobileUa, buildUserAgent(mobileUa, UserAgentMode.MOBILE, null))
        assertEquals(mobileUa, buildUserAgent(mobileUa, UserAgentMode.MOBILE, "   "))
        // 末尾不能留空格：UA 尾部空白会让部分站点的解析出错
        assertFalse(buildUserAgent(mobileUa, UserAgentMode.MOBILE, " ").endsWith(" "))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val ua = buildUserAgent("  $mobileUa  ", UserAgentMode.MOBILE, " App/2.0 ")

        assertEquals("$mobileUa App/2.0", ua)
    }

    @Test
    fun desktopModeHandlesUaEndingWithMobile() {
        // 有些内核的 UA 以 Mobile 结尾（后面没有 Safari），也要能剥掉
        assertEquals("Mozilla/5.0 (Linux) Chrome/120", buildUserAgent("Mozilla/5.0 (Linux) Chrome/120 Mobile", UserAgentMode.DESKTOP_SIMULATE, null))
    }
}
