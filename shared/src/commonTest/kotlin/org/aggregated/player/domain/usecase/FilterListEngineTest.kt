package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertTrue
import org.aggregated.player.FakeLogger

class FilterListEngineTest {

    @Test
    fun `bundled list blocks known ad domain and passes clean domain`() {
        val engine = FilterListEngine(FakeLogger())
        assertTrue(engine.activeRuleCount > 0, "内置列表应解析出规则")
        assertTrue(
            engine.decide("https://ad.doubleclick.net/foo", null, true) is InterceptionDecision.Blocked,
            "应拦截已知广告域名",
        )
        assertTrue(
            engine.decide("https://example.com/", null, true) is InterceptionDecision.Pass,
            "未命中列表的域名应放行",
        )
    }

    @Test
    fun `bundled list yields cosmetic element rules`() {
        val engine = FilterListEngine(FakeLogger())
        val selectors = engine.elementRulesFor(null).map { it.pattern }
        assertTrue(selectors.isNotEmpty(), "应包含 ## 元素隐藏规则")
        assertTrue(selectors.contains(".ad-banner"))
    }

    @Test
    fun `exception in list allows otherwise-blocked host`() {
        // googleapis.com 在内置列表里是 @@ 例外
        val engine = FilterListEngine(FakeLogger())
        assertTrue(
            engine.decide("https://ajax.googleapis.com/foo.js", null, true) is InterceptionDecision.Pass,
            "@@ 例外应放行",
        )
    }
}
