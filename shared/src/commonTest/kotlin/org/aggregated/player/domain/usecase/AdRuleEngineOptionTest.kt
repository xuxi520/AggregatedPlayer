package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertIs
import org.aggregated.player.FakeLogger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.ResourceType

/**
 * `$option` 约束与正则规则在 [AdRuleEngine.decide] 的兑现测试。
 *
 * 用 [AdRuleEngine] 直接喂手工构造的带选项规则（不经过过滤列表），精确验证匹配语义，
 * 尤其是「上下文缺失时少拦优先」的保守策略。
 */
class AdRuleEngineOptionTest {

    private fun engine(vararg rules: AdRule) = AdRuleEngine(FakeLogger()).apply { reload(rules.toList()) }

    private fun domainRule(
        pattern: String,
        thirdParty: Boolean? = null,
        resourceTypes: Set<ResourceType> = emptySet(),
        excludedResourceTypes: Set<ResourceType> = emptySet(),
        domainRestrictions: Set<String> = emptySet(),
        excludedDomains: Set<String> = emptySet(),
    ) = AdRule(
        id = "r-$pattern",
        pattern = pattern,
        kind = AdRuleKind.DOMAIN,
        thirdParty = thirdParty,
        resourceTypes = resourceTypes,
        excludedResourceTypes = excludedResourceTypes,
        domainRestrictions = domainRestrictions,
        excludedDomains = excludedDomains,
    )

    @Test
    fun `third-party rule blocks cross-site request and passes same-site`() {
        val engine = engine(domainRule("ads.example.net", thirdParty = true))
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://news.site.com/article",
            ),
            "跨站请求应视为第三方并拦截",
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://example.net/home",
            ),
            "同注册域请求应视为首方并放行",
        )
    }

    @Test
    fun `third-party rule is skipped when context missing`() {
        val engine = engine(domainRule("ads.example.net", thirdParty = true))
        assertIs<InterceptionDecision.Pass>(
            engine.decide(url = "https://ads.example.net/banner.js", siteId = null, documentUrl = null),
            "缺页面 URL 无法断言第三方，宁可少拦",
        )
    }

    @Test
    fun `first-party-only rule blocks only same-site`() {
        val engine = engine(domainRule("cdn.example.com", thirdParty = false))
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://cdn.example.com/lib.js",
                siteId = null,
                documentUrl = "https://www.example.com/home",
            ),
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://cdn.example.com/lib.js",
                siteId = null,
                documentUrl = "https://other.org/home",
            ),
        )
    }

    @Test
    fun `domain restriction scopes rule to page domain`() {
        val engine = engine(domainRule("ads.example.net", domainRestrictions = setOf("allow.com")))
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://sub.allow.com/page",
            ),
            "页面注册域命中限定域应拦截",
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://deny.com/page",
            ),
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(url = "https://ads.example.net/banner.js", siteId = null, documentUrl = null),
            "缺页面上下文时限定域规则不命中",
        )
    }

    @Test
    fun `excluded domain lets a page pass`() {
        val engine = engine(domainRule("ads.example.net", excludedDomains = setOf("safe.com")))
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://safe.com/page",
            ),
        )
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/banner.js",
                siteId = null,
                documentUrl = "https://other.com/page",
            ),
        )
    }

    @Test
    fun `positive resource type requires known type`() {
        val engine = engine(domainRule("ads.example.net", resourceTypes = setOf(ResourceType.IMAGE)))
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/banner.png",
                siteId = null,
                resourceType = ResourceType.IMAGE,
                documentUrl = "https://site.com/page",
            ),
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/banner.png",
                siteId = null,
                resourceType = ResourceType.SCRIPT,
                documentUrl = "https://site.com/page",
            ),
            "类型不匹配应放行",
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(url = "https://ads.example.net/banner.png", siteId = null),
            "Android 无资源类型（UNKNOWN）时少拦优先，不命中",
        )
    }

    @Test
    fun `excluded resource type lets that type pass`() {
        val engine = engine(domainRule("ads.example.net", excludedResourceTypes = setOf(ResourceType.SCRIPT)))
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/lib.js",
                siteId = null,
                resourceType = ResourceType.SCRIPT,
                documentUrl = "https://site.com/page",
            ),
        )
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/pic.png",
                siteId = null,
                resourceType = ResourceType.IMAGE,
                documentUrl = "https://site.com/page",
            ),
        )
    }

    @Test
    fun `regex rule matches full url`() {
        val engine = engine(
            AdRule(
                id = "rx",
                pattern = "ads\\d+\\.tracker\\.com",
                kind = AdRuleKind.URL_SUBSTRING,
                isRegex = true,
            ),
        )
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(url = "https://ads42.tracker.com/px.gif", siteId = null),
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(url = "https://ads.tracker.com/px.gif", siteId = null),
        )
    }

    @Test
    fun `regex exception takes precedence`() {
        val engine = engine(
            AdRule(id = "block", pattern = "tracker.com", kind = AdRuleKind.DOMAIN),
            AdRule(id = "allow", pattern = "safe", kind = AdRuleKind.EXCEPTION, isRegex = true),
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(url = "https://tracker.com/safe-pixel.gif", siteId = null),
            "正则例外先于屏蔽规则",
        )
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(url = "https://tracker.com/ads.gif", siteId = null),
        )
    }

    @Test
    fun `options combine with and semantics`() {
        val engine = engine(
            domainRule(
                "ads.example.net",
                thirdParty = true,
                domainRestrictions = setOf("news.com"),
                resourceTypes = setOf(ResourceType.SCRIPT),
            ),
        )
        assertIs<InterceptionDecision.Blocked>(
            engine.decide(
                url = "https://ads.example.net/a.js",
                siteId = null,
                resourceType = ResourceType.SCRIPT,
                documentUrl = "https://news.com/page",
            ),
            "全部约束满足才拦截",
        )
        assertIs<InterceptionDecision.Pass>(
            engine.decide(
                url = "https://ads.example.net/a.png",
                siteId = null,
                resourceType = ResourceType.IMAGE,
                documentUrl = "https://news.com/page",
            ),
            "任一约束不满足即放行",
        )
    }
}
