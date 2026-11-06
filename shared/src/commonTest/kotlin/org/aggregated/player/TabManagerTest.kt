package org.aggregated.player

import org.aggregated.player.domain.model.KeepAlivePolicy
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.model.TabState
import org.aggregated.player.domain.usecase.TabEffect
import org.aggregated.player.domain.usecase.TabManager
import org.aggregated.player.domain.model.MemoryPressureLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 标签状态机 + LRU 的用例（设计文档 §4.1、§4.2）。
 *
 * 这里刻意**只断言下发给平台的效果**，不接触任何 WebView：
 * 「状态与实例会不会不一致」「淘汰会不会杀到正在看的页面」这两个最危险的点，
 * 在这一层就能被完全覆盖，不需要真机。
 */
class TabManagerTest {

    private val logger = FakeLogger()
    private val clock = FakeClock()

    // 默认用 LimitedTo：本类大部分用例在验「超出上限时谁被淘汰」，无上限策略单独在 KeepAlivePolicyTest 里测
    private fun manager(maxLiveTabs: Int = 2, pressureLiveTabs: Int = 1) =
        TabManager(
            logger = logger,
            clock = clock::now,
            policy = KeepAlivePolicy.LimitedTo(maxLiveTabs),
            pressureLiveTabs = pressureLiveTabs,
        )

    private fun site(id: String) = Site(id = id, name = "站点$id", url = "https://$id.example.com/")

    /** 找到某个标签在会话里的当前状态。 */
    private fun stateOf(manager: TabManager, tabId: String): TabState? =
        manager.session.value.find(tabId)?.state

    private fun List<TabEffect>.loads() = filterIsInstance<TabEffect.Load>().map { it.tab.id }
    private fun List<TabEffect>.resumes() = filterIsInstance<TabEffect.Resume>().map { it.tabId }
    private fun List<TabEffect>.pauses() = filterIsInstance<TabEffect.Pause>().map { it.tabId }
    private fun List<TabEffect>.evicts() = filterIsInstance<TabEffect.Evict>().map { it.tab.id }

    @Test
    fun openingSiteCreatesOneActiveTab() {
        val manager = manager()
        val effects = manager.openSite(site("a"))

        val active = assertNotNull(manager.session.value.activeTab)
        assertEquals("a", active.siteId)
        assertEquals(TabState.ACTIVE, active.state)
        assertEquals(listOf(active.id), effects.loads())
        // 首个标签不应该收到 Pause：没有需要让位的页面，发了就是多余的一次实例降载
        assertTrue(effects.pauses().isEmpty())
    }

    @Test
    fun switchingBackToExistingTabDoesNotReload() {
        // 这是 F2「切回即继续浏览」的核心断言：切回热标签不能产生 Load
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.openSite(site("b"))

        val effects = manager.openSite(site("a"))
        assertTrue(effects.loads().isEmpty())
        assertEquals(listOf(tabA), effects.resumes())
        assertEquals(TabState.ACTIVE, stateOf(manager, tabA))
    }

    @Test
    fun exceedingCapacityEvictsLeastRecentlyUsedAndProtectsActive() {
        val manager = manager(maxLiveTabs = 2)
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.openSite(site("b"))
        val tabB = tabIdOf(manager, "b")

        val effects = manager.openSite(site("c"))
        val tabC = tabIdOf(manager, "c")

        // A 是最久没被看的，必须是被释放的那个
        assertEquals(listOf(tabA), effects.evicts())
        assertEquals(TabState.COLD, stateOf(manager, tabA))
        assertEquals(TabState.BACKGROUND, stateOf(manager, tabB))
        assertEquals(TabState.ACTIVE, stateOf(manager, tabC))
        assertEquals(2, manager.session.value.liveTabCount)
    }

    @Test
    fun touchingATabSavesItFromEviction() {
        val manager = manager(maxLiveTabs = 2)
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.openSite(site("b"))
        // 回头看一眼 A（A 变热、B 变旧），此时开 C 被淘汰的应该是 B
        manager.activate(tabA)
        val tabB = tabIdOf(manager, "b")

        val effects = manager.openSite(site("c"))
        assertEquals(listOf(tabB), effects.evicts())
        assertEquals(TabState.BACKGROUND, stateOf(manager, tabA))
    }

    @Test
    fun closingActiveTabSwitchesToMostRecentLiveTab() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.openSite(site("b"))
        val tabB = tabIdOf(manager, "b")

        val effects = manager.close(tabB)
        assertTrue(effects.any { it is TabEffect.Discard && it.tabId == tabB })
        assertEquals(listOf(tabA), effects.resumes())
        assertEquals(tabA, manager.session.value.activeTabId)
        assertEquals(TabState.ACTIVE, stateOf(manager, tabA))
        assertFalse(manager.session.value.tabs.any { it.id == tabB })
    }

    @Test
    fun closingTheOnlyTabLeavesAnEmptySession() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")

        manager.close(tabA)
        assertNull(manager.session.value.activeTabId)
        assertTrue(manager.session.value.tabs.isEmpty())
    }

    @Test
    fun stoppingLiveTabReleasesInstanceButKeepsRecord() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")

        val effects = manager.stop(tabA)

        // 释放实例 + 状态降为冷，但标签记录保留（与 close 的区别：不删标签）
        assertEquals(listOf(tabA), effects.evicts())
        assertEquals(TabState.COLD, stateOf(manager, tabA))
        assertNull(manager.session.value.activeTabId)
        assertTrue(manager.session.value.tabs.any { it.id == tabA })
    }

    @Test
    fun stoppingColdTabIsNoOp() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.markCold(tabA)

        val effects = manager.stop(tabA)

        // 冷标签没有实例可释放：必须空操作，不能误发 Evict
        assertTrue(effects.isEmpty())
        assertEquals(TabState.COLD, stateOf(manager, tabA))
    }

    @Test
    fun closingActiveTabWithOnlyColdRemainingDoesNotReload() {
        // 关闭活动标签后，若只剩冷标签，绝不能自动 Load（重启页面），应回到无活动标签态
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        manager.markCold(tabA) // A 变冷、activeTabId 清空
        manager.openSite(site("b"))
        val tabB = tabIdOf(manager, "b")
        assertEquals(tabB, manager.session.value.activeTabId)

        val effects = manager.close(tabB)

        assertTrue(effects.loads().isEmpty(), "冷标签不该被自动重载")
        assertTrue(effects.resumes().isEmpty())
        assertNull(manager.session.value.activeTabId)
        // 冷标签 A 仍在列表里，是否重载由用户决定
        assertEquals(TabState.COLD, stateOf(manager, tabA))
    }

    @Test
    fun restoreDemotesEveryTabToCold() {
        // 落盘时可能是 ACTIVE/BACKGROUND，但进程刚起来时实例并不存在：必须一律降级；
        // 冷启动第一屏是空状态页，不自动加载任何标签，是否重载由用户点站点决定
        val manager = manager()
        val saved = TabSession(
            activeTabId = "tab-a",
            tabs = listOf(
                SiteTab(id = "tab-a", siteId = "a", entryUrl = "https://a/", url = "https://a/watch/1", title = "A", state = TabState.ACTIVE),
                SiteTab(id = "tab-b", siteId = "b", entryUrl = "https://b/", url = "https://b/", title = "B", state = TabState.BACKGROUND),
            ),
        )

        val effects = manager.restore(saved)

        assertEquals(listOf(TabState.COLD, TabState.COLD), manager.session.value.tabs.map { it.state })
        assertTrue(effects.isEmpty())
        assertEquals(0, manager.session.value.liveTabCount)
        // 活动标记一并清空：首屏走空状态页而不是上次退出前的页面
        assertNull(manager.session.value.activeTabId)
        // 标签记录保留，「上次看到的页面」URL 原样留存，手动重开仍能回到原位置
        assertEquals("https://a/watch/1", manager.session.value.tabs.first { it.id == "tab-a" }.url)
    }

    @Test
    fun activatingColdTabAsksForReload() {
        val manager = manager()
        manager.restore(TabSession(activeTabId = "tab-a", tabs = listOf(
            SiteTab(id = "tab-a", entryUrl = "https://a/", url = "https://a/"),
            SiteTab(id = "tab-b", entryUrl = "https://b/", url = "https://b/"),
        )))

        val effects = manager.activate("tab-b")
        // 两个标签都还是冷的：既不加载不存在的历史，也不该给没实例的标签发 Pause
        assertEquals(listOf("tab-b"), effects.loads())
        assertTrue(effects.resumes().isEmpty())
        assertTrue(effects.pauses().isEmpty())
        assertEquals(TabState.ACTIVE, stateOf(manager, "tab-b"))
    }

    @Test
    fun criticalPressureKeepsOnlyActiveTab() {
        val manager = manager(maxLiveTabs = 2, pressureLiveTabs = 1)
        manager.openSite(site("a"))
        manager.openSite(site("b"))
        val tabA = tabIdOf(manager, "a")
        val tabB = tabIdOf(manager, "b")

        val effects = manager.onMemoryPressure(MemoryPressureLevel.CRITICAL)

        assertEquals(listOf(tabA), effects.evicts())
        assertEquals(TabState.COLD, stateOf(manager, tabA))
        // 无论压力多大，正在看的页面不能被释放
        assertEquals(TabState.ACTIVE, stateOf(manager, tabB))
        assertEquals(1, manager.session.value.liveTabCount)
    }

    @Test
    fun markColdReflectsPlatformFailure() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")
        val snapshot = TabNavigationSnapshot(listOf("https://a/", "https://a/watch/1"), currentIndex = 1)

        manager.markCold(tabA, navigation = snapshot)

        assertEquals(TabState.COLD, stateOf(manager, tabA))
        assertNull(manager.session.value.activeTabId)
        assertEquals(snapshot, manager.session.value.find(tabA)?.navigation)
    }

    @Test
    fun platformUpdatesDoNotInventTabs() {
        val manager = manager()
        manager.openSite(site("a"))
        val tabA = tabIdOf(manager, "a")

        manager.updateFromPlatform(tabA, url = "https://a/watch/2", title = "在看的剧集")
        // 不存在的 id 必须被忽略，否则平台回调会凭空造出标签
        manager.updateFromPlatform("tab-ghost", title = "幽灵")

        assertEquals("在看的剧集", manager.session.value.find(tabA)?.title)
        assertEquals("https://a/watch/2", manager.session.value.find(tabA)?.url)
        assertEquals(1, manager.session.value.tabs.size)
    }

    private fun tabIdOf(manager: TabManager, siteId: String): String =
        manager.session.value.tabs.firstOrNull { it.siteId == siteId }?.id
            ?: error("找不到站点 $siteId 的标签")
}
