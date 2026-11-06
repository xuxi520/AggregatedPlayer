package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertTrue
import org.aggregated.player.FakeLogger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind

class CompositeAdBlockEngineTest {

    private fun engineBlocking(host: String) = AdRuleEngine(FakeLogger()).apply {
        reload(listOf(AdRule(id = "r-$host", pattern = "||$host^", kind = AdRuleKind.DOMAIN)))
    }

    @Test
    fun `first blocking engine wins`() {
        val composite = CompositeAdBlockEngine(listOf(engineBlocking("a.com"), engineBlocking("b.com")))
        assertTrue(composite.decide("https://a.com/x", null, true) is InterceptionDecision.Blocked)
        assertTrue(composite.decide("https://b.com/x", null, true) is InterceptionDecision.Blocked)
        assertTrue(composite.decide("https://c.com/x", null, true) is InterceptionDecision.Pass)
    }

    @Test
    fun `disabled site switch still passes through composite`() {
        val composite = CompositeAdBlockEngine(listOf(engineBlocking("a.com")))
        assertTrue(composite.decide("https://a.com/x", null, false) is InterceptionDecision.Pass)
    }

    @Test
    fun `element rules merged across engines`() {
        val a = AdRuleEngine(FakeLogger()).apply {
            reload(listOf(AdRule(id = "e1", pattern = ".x", kind = AdRuleKind.ELEMENT)))
        }
        val b = AdRuleEngine(FakeLogger()).apply {
            reload(listOf(AdRule(id = "e2", pattern = ".y", kind = AdRuleKind.ELEMENT)))
        }
        val composite = CompositeAdBlockEngine(listOf(a, b))
        val selectors = composite.elementRulesFor(null).map { it.pattern }
        assertTrue(selectors.contains(".x") && selectors.contains(".y"), "元素隐藏规则应合并")
    }
}
