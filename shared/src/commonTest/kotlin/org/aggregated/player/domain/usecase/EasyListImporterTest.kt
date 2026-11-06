package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.ResourceType

class EasyListImporterTest {

    @Test
    fun `parses network, exception and cosmetic rules`() {
        val text = """
            ! comment line
            [Adblock Plus 2.0]
            ||doubleclick.net^
            @@||googleapis.com^
            ##.ad-banner
            example.com##.promo
        """.trimIndent()
        val rules = EasyListImporter.parse(text)

        val domains = rules.filter { it.kind == AdRuleKind.DOMAIN }.map { it.pattern }
        assertEquals(listOf("doubleclick.net"), domains)

        val exceptions = rules.filter { it.kind == AdRuleKind.EXCEPTION }.map { it.pattern }
        assertEquals(listOf("googleapis.com"), exceptions)

        val elements = rules.filter { it.kind == AdRuleKind.ELEMENT }
        assertTrue(elements.any { it.pattern == ".ad-banner" && it.siteId == null })
        assertTrue(elements.any { it.pattern == ".promo" && it.siteId == "example.com" })
    }

    @Test
    fun `ignores comments, blank lines and section headers`() {
        val text = """
            ! a comment
            [Adblock Plus 2.0]

            ||doubleclick.net^
        """.trimIndent()
        val rules = EasyListImporter.parse(text)
        assertEquals(1, rules.size)
        assertEquals("doubleclick.net", rules[0].pattern)
    }

    @Test
    fun `strips option suffix after dollar sign`() {
        val rules = EasyListImporter.parse("||ads.example.com^\$third-party")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules[0].pattern)
        assertEquals(true, rules[0].thirdParty)
    }

    @Test
    fun `parses third-party and domain options`() {
        val rules = EasyListImporter.parse("||ads.example.com^\$third-party,domain=example.com|~avoid.com")
        assertEquals(1, rules.size)
        val r = rules[0]
        assertEquals(true, r.thirdParty)
        assertEquals(setOf("example.com"), r.domainRestrictions)
        assertEquals(setOf("avoid.com"), r.excludedDomains)
    }

    @Test
    fun `parses resource type options with negation`() {
        val rules = EasyListImporter.parse("||ads.example.com^\$image,~script")
        assertEquals(1, rules.size)
        val r = rules[0]
        assertEquals(setOf(ResourceType.IMAGE), r.resourceTypes)
        assertEquals(setOf(ResourceType.SCRIPT), r.excludedResourceTypes)
    }

    @Test
    fun `parses regex rules`() {
        val rules = EasyListImporter.parse("/ads\\d+\\.tracker\\.com/")
        assertEquals(1, rules.size)
        val r = rules[0]
        assertTrue(r.isRegex)
        assertEquals("ads\\d+\\.tracker\\.com", r.pattern)
    }

    @Test
    fun `regex with options keeps options`() {
        val rules = EasyListImporter.parse("/ads\\d+\\.tracker\\.com/\$third-party")
        assertEquals(1, rules.size)
        assertTrue(rules[0].isRegex)
        assertEquals(true, rules[0].thirdParty)
    }

    @Test
    fun `unknown option is ignored gracefully`() {
        // $elemhide / $important 等未支持选项不应导致解析失败
        val rules = EasyListImporter.parse("||ads.example.com^\$important,elemhide")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules[0].pattern)
        assertFalse(rules[0].isRegex)
    }
}
