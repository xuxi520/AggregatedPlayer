package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aggregated.player.domain.model.AdRuleKind

class DefaultAdRulesTest {

    @Test
    fun `all returns non-empty global DOMAIN rules with unique ids`() {
        val rules = DefaultAdRules.all()
        assertTrue(rules.isNotEmpty(), "默认规则不应为空")
        assertEquals(rules.size, rules.distinctBy { it.id }.size, "规则 id 必须唯一")
        assertTrue(
            rules.all { it.kind == AdRuleKind.DOMAIN && it.siteId == null && it.enabled },
            "默认规则应全是启用的全局 DOMAIN 规则",
        )
    }

    @Test
    fun `ids are stable and do not collide with timestamp-based user ids`() {
        val rules = DefaultAdRules.all()
        // 用户规则 id 形如 rule-<timestamp>，种子用 seed-ad-<domain>，前缀不同即不撞键
        assertTrue(rules.all { it.id.startsWith("seed-ad-") })
    }
}
