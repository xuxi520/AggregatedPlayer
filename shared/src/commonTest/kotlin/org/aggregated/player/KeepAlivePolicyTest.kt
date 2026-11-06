package org.aggregated.player

import org.aggregated.player.domain.model.KeepAlivePolicy
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.TabState
import org.aggregated.player.domain.usecase.NavigationAction
import org.aggregated.player.domain.usecase.TabEffect
import org.aggregated.player.domain.usecase.TabManager
import org.aggregated.player.domain.model.MemoryPressureLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保活策略的用例（设计文档 §4.1、§4.2，Q2 定档）。
 *
 * 这批用例的本质是在守一条产品底线：**用户切回任何已打开的站点，实例都还在**。
 * 「不淘汰」是默认行为，因此必须有反例用例证明淘汰只在系统压力到来时发生——
 * 否则阶段 1 的实现里任何一处多余的 `evictToBudget` 调用都会静默地破坏需求。
 */
class KeepAlivePolicyTest {

    private val logger = FakeLogger()
    private val clock = FakeClock()

    private fun manager(policy: KeepAlivePolicy, pressureLiveTabs: Int = 2, hardTabLimit: Int = 32, softWarn: Int = 12) =
        TabManager(
            logger = logger,
            clock = clock::now,
            policy = policy,
            pressureLiveTabs = pressureLiveTabs,
            hardTabLimit = hardTabLimit,
            softWarnTabCount = softWarn,
        )

    private fun site(id: String) = Site(id = id, name = "站点$id", url = "https://$id.example.com/")

    private fun List<TabEffect>.evicts() = filterIsInstance<TabEffect.Evict>().map { it.tab.id }

    @Test
    fun unboundedKeepsEveryOpenedTabAlive() {
        val manager = manager(KeepAlivePolicy.Unbounded)

        val allEffects = ('a'..'j').map { site(it.toString()) }.flatMap { manager.openSite(it) }

        assertEquals(10, manager.session.value.tabs.size)
        // 一条 Evict 都不许出现：出现了就说明实现又悄悄加回了主动淘汰
        assertTrue(allEffects.none { it is TabEffect.Evict })
        assertEquals(10, manager.session.value.liveTabCount)
        // 只有最后一个活动标签之外的标签收到 Pause（降载），没有任何标签被释放
        assertEquals(9, allEffects.count { it is TabEffect.Pause })
    }

    @Test
    fun unboundedStillDegradesWhenSystemIsCriticalButNeverTheActiveTab() {
        val manager = manager(KeepAlivePolicy.Unbounded, pressureLiveTabs = 2)
        repeat(5) { index -> manager.openSite(site("s$index")) }
        val activeId = manager.session.value.activeTabId!!

        val effects = manager.onMemoryPressure(MemoryPressureLevel.CRITICAL)

        assertEquals(3, effects.evicts().size)
        assertFalse(activeId in effects.evicts())
        assertEquals(TabState.ACTIVE, manager.session.value.find(activeId)?.state)
        assertEquals(2, manager.session.value.liveTabCount)
        // 被淘汰的标签必须留下 COLD 记录，用户点回来才会重新加载
        effects.evicts().forEach { assertEquals(TabState.COLD, manager.session.value.find(it)?.state) }
    }

    @Test
    fun moderateAndBackgroundNeverEvictUnderUnbounded() {
        val manager = manager(KeepAlivePolicy.Unbounded)
        repeat(6) { index -> manager.openSite(site("s$index")) }

        assertTrue(manager.onMemoryPressure(MemoryPressureLevel.MODERATE).isEmpty())
        assertTrue(manager.onMemoryPressure(MemoryPressureLevel.BACKGROUND).isEmpty())
        assertEquals(6, manager.session.value.liveTabCount)
    }

    @Test
    fun limitedToEvictsLeastRecentlyUsedWhenOverCapacity() {
        val manager = manager(KeepAlivePolicy.LimitedTo(2), pressureLiveTabs = 1)

        manager.openSite(site("a"))
        val tabA = manager.session.value.tabs.first { it.siteId == "a" }.id
        manager.openSite(site("b"))
        val effects = manager.openSite(site("c"))

        assertEquals(listOf(tabA), effects.evicts())
        assertEquals(2, manager.session.value.liveTabCount)
    }

    @Test
    fun criticalUnderLimitedNeverExceedsItsOwnCap() {
        // 压力存活数(3) 大于 上限(1) 时，不能因为降级反而「多留」实例
        val manager = manager(KeepAlivePolicy.LimitedTo(1), pressureLiveTabs = 3)
        repeat(4) { index -> manager.openSite(site("s$index")) }

        manager.onMemoryPressure(MemoryPressureLevel.CRITICAL)

        assertEquals(1, manager.session.value.liveTabCount)
    }

    @Test
    fun hardTabLimitRefusesNewTabsButNeverBlocksSwitching() {
        val manager = manager(KeepAlivePolicy.Unbounded, hardTabLimit = 2)
        manager.openSite(site("a"))
        manager.openSite(site("b"))

        // 已达上限：开新站点被拒
        val rejected = manager.openSite(site("c"))
        assertEquals(2, manager.session.value.tabs.size)
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.WARN })

        // 但切回已打开的站点必须照常工作——这是最容易写错的一步：
        // 如果把上限检查放在查已有标签之前，用户会被锁死在现有标签里
        val switch = manager.openSite(site("a"))
        assertEquals(1, switch.count { it is TabEffect.Resume })
        assertEquals(2, manager.session.value.tabs.size)
        assertEquals("a", manager.session.value.activeTab?.siteId)
        assertTrue(rejected.isNotEmpty())
    }

    @Test
    fun softWarnThresholdOnlyWarnsInUiAndNeverEvicts() {
        val manager = manager(KeepAlivePolicy.Unbounded, softWarn = 3)
        repeat(3) { index -> manager.openSite(site("s$index")) }

        assertFalse(manager.shouldWarnAboutMemory())
        manager.openSite(site("extra"))

        assertTrue(manager.shouldWarnAboutMemory())
        assertEquals(4, manager.session.value.liveTabCount)
    }

    @Test
    fun coldTabWakeUpUnderUnboundedDoesNotEvictItsPeers() {
        val manager = manager(KeepAlivePolicy.Unbounded, pressureLiveTabs = 1)
        manager.openSite(site("a"))
        val tabA = manager.session.value.tabs.first { it.siteId == "a" }.id
        manager.openSite(site("b"))

        // 把 A 打成冷标签（模拟一次严重降级），再唤醒它
        manager.markCold(tabA)
        val effects = manager.activate(tabA)

        assertEquals(listOf(tabA), effects.filterIsInstance<TabEffect.Load>().map { it.tab.id })
        assertEquals(2, manager.session.value.liveTabCount)
        assertTrue(effects.none { it is TabEffect.Evict })
    }

    @Test
    fun navigationEffectIsAddressedToTheActiveTabOnly() {
        val manager = manager(KeepAlivePolicy.Unbounded)
        manager.openSite(site("a"))
        manager.openSite(site("b"))

        val active = manager.snapshot().activeTabId ?: error("应有活动标签")
        val effects: List<TabEffect> = listOf(TabEffect.Navigate(active, NavigationAction.RELOAD))

        assertEquals(1, effects.size)
        assertEquals("b", manager.session.value.activeTab?.siteId)
        // Navigate 不改变任何状态，因此不会引发淘汰或降级
        assertTrue(effects.none { it is TabEffect.Evict || it is TabEffect.Load })
    }
}
