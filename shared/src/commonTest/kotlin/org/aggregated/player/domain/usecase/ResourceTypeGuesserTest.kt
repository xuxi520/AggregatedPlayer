package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import org.aggregated.player.domain.model.ResourceType

class ResourceTypeGuesserTest {

    @Test
    fun `guesses common static resource types from extension`() {
        assertEquals(ResourceType.SCRIPT, ResourceTypeGuesser.guess("https://cdn.com/lib.js?v=1"))
        assertEquals(ResourceType.STYLESHEET, ResourceTypeGuesser.guess("https://cdn.com/a.css"))
        assertEquals(ResourceType.IMAGE, ResourceTypeGuesser.guess("https://cdn.com/banner.png"))
        assertEquals(ResourceType.FONT, ResourceTypeGuesser.guess("https://cdn.com/icon.woff2"))
        assertEquals(ResourceType.MEDIA, ResourceTypeGuesser.guess("https://cdn.com/v.mp4"))
    }

    @Test
    fun `returns unknown for extensionless or dynamic urls`() {
        assertEquals(ResourceType.UNKNOWN, ResourceTypeGuesser.guess("https://api.com/track"))
        assertEquals(ResourceType.UNKNOWN, ResourceTypeGuesser.guess("https://api.com/collect?x=1"))
    }
}
