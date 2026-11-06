package org.aggregated.player

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.usecase.AdRuleEngine
import org.aggregated.player.domain.usecase.InterceptionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 拦截引擎的用例。这里断言的是**是否拦截**这个用户可感知的行为，
 * 不依赖内部索引结构，方便阶段 2 替换实现（例如接入 AdblockAndroid）时用例仍然有效。
 */
class AdRuleEngineTest {

    private val logger = FakeLogger()
    private val engine = AdRuleEngine(logger)

    private fun domain(pattern: String, siteId: String? = null, enabled: Boolean = true) =
        AdRule(id = "r-$pattern", pattern = pattern, kind = AdRuleKind.DOMAIN, siteId = siteId, enabled = enabled)

    private fun substring(pattern: String, siteId: String? = null, enabled: Boolean = true) =
        AdRule(id = "r-$pattern", pattern = pattern, kind = AdRuleKind.URL_SUBSTRING, siteId = siteId, enabled = enabled)

    private fun exception(pattern: String) =
        AdRule(id = "x-$pattern", pattern = pattern, kind = AdRuleKind.EXCEPTION)

    private fun blocked(url: String, siteId: String? = "site-a"): Boolean =
        engine.decide(url = url, siteId = siteId) is InterceptionDecision.Blocked

    @Test
    fun domainRuleBlocksSubdomains() {
        engine.reload(listOf(domain("||ads.example.com^")))

        assertTrue(blocked("https://ads.example.com/banner.js"))
        assertTrue(blocked("https://player.ads.example.com/vast.xml"))
        // 点边界：同前缀的不同域名不能被牵连
        assertEquals(false, blocked("https://notads.example.com/app.js"))
        assertEquals(false, blocked("https://example.com/page.html"))
    }

    @Test
    fun domainRuleAcceptsBareHostPattern() {
        // 用户在点级标记里大概率只会输入裸域名，语法必须容错
        engine.reload(listOf(domain("ads.example.com")))
        assertTrue(blocked("https://m.ads.example.com/ad.js"))
    }

    @Test
    fun domainRuleWithPathPrefixOnlyBlocksThatPath() {
        engine.reload(listOf(domain("||cdn.example.com/prevideo^")))

        assertTrue(blocked("https://cdn.example.com/prevideo/1.ts"))
        assertEquals(false, blocked("https://cdn.example.com/movie/main.m3u8"))
    }

    @Test
    fun substringRuleIsCaseInsensitive() {
        engine.reload(listOf(substring("/Track-Pixel.js")))
        assertTrue(blocked("https://any.site/x/track-pixel.js?q=1"))
    }

    @Test
    fun exceptionBeatsBlockRule() {
        engine.reload(
            listOf(
                substring("/ad-"),
                exception("@@/ad-needed.css"),
            ),
        )

        assertTrue(blocked("https://site.com/ad-banner.js"))
        // 白名单必须能精确救回被泛化子串误伤的资源，否则用户只能整条删掉
        assertEquals(false, blocked("https://site.com/ad-needed.css"))
    }

    @Test
    fun siteScopedRuleDoesNotAffectOtherSites() {
        engine.reload(listOf(substring("/promo", siteId = "site-a")))

        assertTrue(blocked("https://a.com/x/promo.js", siteId = "site-a"))
        assertEquals(false, blocked("https://b.com/x/promo.js", siteId = "site-b"))
        // 临时标签（无归属站点）只受全局规则约束
        assertEquals(false, blocked("https://c.com/x/promo.js", siteId = null))
    }

    @Test
    fun disabledRuleIsIgnoredButStillCountedAsPresent() {
        engine.reload(listOf(substring("/stats.js", enabled = false)))
        assertEquals(false, blocked("https://site.com/stats.js"))
        assertEquals(0, engine.activeRuleCount)
    }

    @Test
    fun elementRulesDoNotParticipateInUrlBlocking() {
        engine.reload(listOf(AdRule(id = "e1", pattern = ".ad-banner", kind = AdRuleKind.ELEMENT)))

        assertEquals(false, blocked("https://site.com/.ad-banner"))
        // 但它们要能被注入路径取到，否则点级标记的元素规则就是死数据
        assertEquals(1, engine.elementRulesFor(siteId = null).size)
    }

    @Test
    fun overlyBroadDomainRuleIsRejectedWithWarning() {
        engine.reload(listOf(domain("||com^")))

        // 一级域名会把整个 TLD 拦死，属于灾难级配置：丢弃并告警
        assertEquals(false, blocked("https://example.com/a.js"))
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.WARN })
    }

    @Test
    fun localhostRuleIsAllowed() {
        // 本机调试站点（http://localhost:8000）需要能被标记掉，所以单主机名例外放行
        engine.reload(listOf(domain("||localhost^")))
        assertTrue(blocked("http://localhost:8000/ads/x.js"))
    }

    @Test
    fun siteLevelSwitchDisablesAllBlocking() {
        engine.reload(listOf(substring("/ads/")))

        // 站点级逃生口：误拦截时用户能立刻在该站点关掉拦截，而不用删规则
        val decision = engine.decide(url = "https://site.com/ads/x.js", siteId = "site-a", siteAdBlockEnabled = false)
        assertIs<InterceptionDecision.Pass>(decision)
    }

    @Test
    fun malformedUrlPassesInsteadOfBlockingEverything() {
        engine.reload(listOf(domain("||example.com^")))

        assertEquals(false, blocked("not a url at all"))
        assertEquals(false, blocked(""))
    }

    @Test
    fun reloadReplacesPreviousRules() {
        engine.reload(listOf(substring("/old-rule")))
        engine.reload(listOf(substring("/new-rule")))

        assertEquals(false, blocked("https://site.com/old-rule.js"))
        assertTrue(blocked("https://site.com/new-rule.js"))
    }
}
