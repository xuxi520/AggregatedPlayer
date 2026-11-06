package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.ElementInfo
import org.aggregated.player.domain.model.PathStep
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ElementSelectorFactory] 的候选选择器生成测试，重点覆盖「id 末尾长数字泛化」，
 * 防止点选 `ad-banner-12345` 这类动态 id 后，刷新导致规则失效。
 */
class ElementSelectorFactoryTest {

    private fun info(id: String?, classes: List<String> = emptyList()): ElementInfo {
        val step = PathStep(tag = "DIV", id = id, classes = classes)
        return ElementInfo(tag = "DIV", id = id, classes = classes, path = listOf(step))
    }

    @Test
    fun `id 末尾长数字应生成前缀匹配候选，且保留精确 id`() {
        val cands = ElementSelectorFactory.candidates(info("ad-banner-12345"))
        assertTrue(cands.any { it.selector == "DIV[id^=\"ad-banner-\"]" }, "应泛化为前缀匹配")
        assertTrue(cands.any { it.selector == "#ad-banner-12345" }, "精确 id 仍应保留供选择")
    }

    @Test
    fun `短数字 id 不应泛化`() {
        val cands = ElementSelectorFactory.candidates(info("box2"))
        assertFalse(cands.any { it.selector.contains("id^=") }, "长度<3 的数字不应泛化")
    }

    @Test
    fun `纯数字 id 不应泛化`() {
        val cands = ElementSelectorFactory.candidates(info("12345"))
        assertFalse(cands.any { it.selector.contains("id^=") }, "纯数字 id 无意义，不应泛化")
    }
}
