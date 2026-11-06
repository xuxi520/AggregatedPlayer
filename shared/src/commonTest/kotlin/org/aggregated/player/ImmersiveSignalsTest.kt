package org.aggregated.player

import org.aggregated.player.platform.ImmersiveSignals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ImmersiveSignals] 的纯逻辑用例。
 *
 * 它驱动悬浮控件的滚动隐显，状态一旦「粘住」在滚动中，控件就会永久消失——
 * 所以重点验证幂等与可逆，而不是具体文案。
 */
class ImmersiveSignalsTest {

    @Test
    fun `初始为未滚动`() {
        assertFalse(ImmersiveSignals().isScrolling.value)
    }

    @Test
    fun `滚动后转静默可反复切换`() {
        val signals = ImmersiveSignals()
        signals.markScrolling()
        assertTrue(signals.isScrolling.value)
        signals.markIdle()
        assertFalse(signals.isScrolling.value)
    }

    @Test
    fun `重复标记不产生多余状态变化`() {
        val signals = ImmersiveSignals()
        signals.markScrolling()
        signals.markScrolling()
        assertTrue(signals.isScrolling.value)
        signals.markIdle()
        signals.markIdle()
        assertFalse(signals.isScrolling.value)
    }
}
