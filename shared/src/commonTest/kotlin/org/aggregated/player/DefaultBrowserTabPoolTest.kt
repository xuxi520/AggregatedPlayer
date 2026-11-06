package org.aggregated.player

import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.TabState
import org.aggregated.player.domain.usecase.NavigationAction
import org.aggregated.player.domain.usecase.TabEffect
import org.aggregated.player.platform.DefaultBrowserTabPool
import org.aggregated.player.platform.RelaxedMainThreadChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 实例池的用例（设计文档 §4.1、§4.2）。
 *
 * 这里验的是阶段 1 **最容易做错的四件事**，全部不需要真 WebView：
 * 1. 同一标签被创建两次（等于切回去重新加载，需求直接失败）；
 * 2. 切换标签时多了一次 `loadUrl`（同上，而且更难复现）；
 * 3. 释放顺序不对（`destroy` 前没摘父容器会泄漏，反过来会抛异常）；
 * 4. 一条效果失败导致整批效果没执行（用户看到「点了没反应」）。
 */
class DefaultBrowserTabPoolTest {

    private val logger = FakeLogger()
    private val factory = RecordingTabControllerFactory()
    private val pool = DefaultBrowserTabPool(factory, logger, RelaxedMainThreadChecker)

    private fun controllerOf(tabId: String): FakeTabController =
        assertNotNull(factory.created.firstOrNull { it.tabId == tabId })

    @Test
    fun obtainingSameTabTwiceCreatesOnlyOneInstanceAndNeverLoads() {
        pool.obtain("tab-a", "https://a.example.com/")
        val again = pool.obtain("tab-a", "https://a.example.com/")

        assertEquals(1, factory.createCount("tab-a"))
        assertEquals(1, pool.liveCount())
        // obtain 不加载：[BrowserTabControllerFactory] 只负责创建，
        // 真正发起加载的唯一入口是 Load 效果——这条断言就是「两处加载入口」的守卫
        assertEquals(0, controllerOf("tab-a").calls.count { it.startsWith("load:") })
        assertNull(again.currentUrl)
    }

    @Test
    fun loadEffectIsTheOnlyThingThatLoads() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a.example.com/watch/1"))))

        val controller = controllerOf("tab-a")
        // Load 现在会先就位标签持久化的 UA（reload=false，避免与 loadUrl 双重加载），再 loadUrl
        assertEquals(
            listOf("create:https://a.example.com/watch/1", "updateUserAgentMode:MOBILE:false", "load:https://a.example.com/watch/1"),
            controller.calls,
        )
    }

    @Test
    fun switchingTabsNeverReloads() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        pool.execute(listOf(TabEffect.Load(openedTab("tab-b", "https://b/1"))))
        val controllerA = controllerOf("tab-a")

        // 模拟「切回 A」：共享层对热标签只发 Resume，池必须原样照做
        pool.execute(listOf(TabEffect.Pause("tab-b"), TabEffect.Resume("tab-a")))

        assertEquals(listOf("resume"), controllerA.calls.filter { it == "resume" || it.startsWith("load:") }.drop(1))
        assertEquals(1, controllerA.calls.count { it.startsWith("load:") })
        assertEquals(listOf("pause"), controllerOf("tab-b").calls.filter { it == "pause" })
    }

    @Test
    fun evictAndDiscardReleaseInSafeOrder() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))

        pool.execute(listOf(TabEffect.Evict(openedTab("tab-a", "https://a/1"))))

        val controller = controllerOf("tab-a")
        // 顺序要求：停加载 → 摘父容器 → destroy。颠倒会触发窗口泄漏或抛异常
        assertEquals(listOf("stopLoading", "detach", "release"), controller.calls.filter { it in setOf("stopLoading", "detach", "release") })
        assertFalse(controller.isAlive)
        assertEquals(0, pool.liveCount())
    }

    @Test
    fun visibilityOnlyKeepsActiveTabVisible() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        pool.execute(listOf(TabEffect.Load(openedTab("tab-b", "https://b/1"))))
        pool.setHostContainer("container-1")

        pool.syncVisibility("tab-b")

        // 只有非活动标签被隐藏，活动标签可见；其余标签不应收到多余的可见性调用
        assertEquals(listOf("hidden"), controllerOf("tab-a").calls.filter { it == "visible" || it == "hidden" })
        assertEquals(listOf("visible"), controllerOf("tab-b").calls.filter { it == "visible" || it == "hidden" })
    }

    @Test
    fun everyInstanceIsAttachedToHostContainer() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        pool.setHostContainer("container-1")
        pool.execute(listOf(TabEffect.Load(openedTab("tab-b", "https://b/1"))))

        assertEquals("container-1", controllerOf("tab-a").parent)
        assertEquals("container-1", controllerOf("tab-b").parent)
    }

    @Test
    fun changingHostContainerDetachesBeforeReattaching() {
        pool.setHostContainer("container-1")
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))

        pool.setHostContainer("container-2")

        val controller = controllerOf("tab-a")
        // 换容器时必须先全部摘掉再挂新容器，否则 Android 会抛「视图已拥有父视图」
        assertEquals(listOf("detach", "attach:container-2"), controller.calls.takeLast(2))
        assertEquals("container-2", controller.parent)
    }

    @Test
    fun oneFailingEffectDoesNotBlockTheRest() {
        val brokenFactory = RecordingTabControllerFactory(failFor = setOf("tab-bad"))
        val brokenPool = DefaultBrowserTabPool(brokenFactory, logger, RelaxedMainThreadChecker)

        brokenPool.execute(
            listOf(
                TabEffect.Load(openedTab("tab-bad", "https://bad/1")),
                TabEffect.Load(openedTab("tab-good", "https://good/1")),
            ),
        )

        // 创建失败的那条只留日志，后面的效果必须照常执行
        assertNotNull(brokenFactory.created.firstOrNull { it.tabId == "tab-good" })
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.ERROR })
    }

    @Test
    fun navigateRoutesToRealInstanceAndIgnoresColdTabs() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        pool.execute(listOf(TabEffect.Navigate("tab-a", NavigationAction.BACK)))
        pool.execute(listOf(TabEffect.Navigate("tab-ghost", NavigationAction.RELOAD)))

        assertEquals(listOf("goBack"), controllerOf("tab-a").calls.filter { it == "goBack" })
        // 冷标签没有实例可导航：不悄悄重建（那会把当前页重载一遍），只留告警
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.WARN })
        assertEquals(1, pool.liveCount())
    }

    @Test
    fun navigateSupportsReloadAndStop() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))

        pool.execute(listOf(TabEffect.Navigate("tab-a", NavigationAction.RELOAD)))
        pool.execute(listOf(TabEffect.Navigate("tab-a", NavigationAction.STOP)))

        val calls = controllerOf("tab-a").calls
        assertTrue(calls.contains("reload"))
        assertTrue(calls.contains("stopLoading"))
    }

    @Test
    fun releaseAllClearsEverythingAndKeepsContainerUsable() {
        pool.setHostContainer("container-1")
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        pool.execute(listOf(TabEffect.Load(openedTab("tab-b", "https://b/1"))))

        pool.releaseAll()

        assertEquals(0, pool.liveCount())
        assertNull(pool.peek("tab-a"))
        assertNull(pool.peek("tab-b"))
        // 容器本身不属于池，销毁实例后仍可以给新实例使用
        pool.execute(listOf(TabEffect.Load(openedTab("tab-c", "https://c/1"))))
        assertEquals("container-1", controllerOf("tab-c").parent)
    }

    @Test
    fun resumeForMissingInstanceOnlyWarns() {
        pool.execute(listOf(TabEffect.Resume("tab-ghost")))

        assertEquals(0, pool.liveCount())
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.WARN })
    }

    @Test
    fun snapshotIsTakenFromLiveInstance() {
        pool.execute(listOf(TabEffect.Load(openedTab("tab-a", "https://a/1"))))
        controllerOf("tab-a").snapshot = TabNavigationSnapshot(listOf("https://a/1", "https://a/2"), currentIndex = 1)

        assertEquals(1, pool.peek("tab-a")?.navigationSnapshot()?.currentIndex)
        assertTrue(pool.peek("tab-a")?.navigationSnapshot()?.canGoBack == true)
    }

    private fun openedTab(tabId: String, url: String) = SiteTab(
        id = tabId,
        entryUrl = url,
        url = url,
        state = TabState.ACTIVE,
    )
}
