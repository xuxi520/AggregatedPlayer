package org.aggregated.player

import kotlinx.coroutines.test.runTest
import org.aggregated.player.data.CorruptDocumentException
import org.aggregated.player.data.JsonAdRuleRepository
import org.aggregated.player.data.JsonDownloadRepository
import org.aggregated.player.data.JsonSiteRepository
import org.aggregated.player.data.JsonSettingsRepository
import org.aggregated.player.data.JsonTabSessionRepository
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.AppSettings
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.domain.model.TriggerDock
import org.aggregated.player.domain.model.TriggerSize
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.model.TabState
import org.aggregated.player.domain.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON 持久化层的用例（设计文档 §4.7）。
 *
 * 最值得测的不是「能存能读」，而是**坏文件不会把用户配置清空**：
 * 内存压力回收进程时正好写完一半的 JSON 是真实会出现的场景。
 */
class JsonRepositoryTest {

    private val store = InMemoryJsonDocumentStore()
    private val logger = FakeLogger()
    private val clock = FakeClock()

    private fun sites() = JsonSiteRepository(store, logger, clock::now)
    private fun rules() = JsonAdRuleRepository(store, logger, clock::now)
    private fun tabs() = JsonTabSessionRepository(store, logger, clock::now)
    private fun downloads() = JsonDownloadRepository(store, logger, clock::now)
    private fun settings() = JsonSettingsRepository(store, logger)

    private fun site(id: String, index: Int) = Site(id = id, name = "站点$id", url = "https://$id.example.com/", sortIndex = index)

    // ---------- 站点 ----------

    @Test
    fun sitesRoundTripAndSort() = runTest {
        val repo = sites()
        repo.upsert(site("b", index = 1))
        repo.upsert(site("a", index = 0))

        assertEquals(listOf("a", "b"), repo.loadAll().map { it.id })
    }

    @Test
    fun firstWriteFillsCreatedAtAndUpdateKeepsIt() = runTest {
        val repo = sites()
        repo.upsert(site("a", index = 0))
        val created = repo.loadAll().single().createdAt
        assertTrue(created > 0L)

        // 改名时 UI 传回的是 createdAt = 0（默认值），不能把创建时间抹掉
        clock.now()
        repo.upsert(site("a", index = 0).copy(name = "改名了", createdAt = 0L))
        val after = repo.loadAll().single()
        assertEquals(created, after.createdAt)
        assertEquals("改名了", after.name)
    }

    @Test
    fun deleteRemovesOnlyTarget() = runTest {
        val repo = sites()
        repo.upsert(site("a", index = 0))
        repo.upsert(site("b", index = 1))
        repo.delete("a")

        assertEquals(listOf("b"), repo.loadAll().map { it.id })
    }

    @Test
    fun corruptedSitesFileIsReadAsEmptyButNeverOverwritten() = runTest {
        // 半截 JSON：读侧降级（应用还能开），写侧必须拒绝，否则一次保存就清空用户所有站点
        val halfWritten = "{\"schemaVersion\":1,\"items\":[{\"id\":\"a"
        store.files["sites.json"] = halfWritten
        val repo = sites()
        assertTrue(repo.loadAll().isEmpty())
    
        assertFailsWith<CorruptDocumentException> { repo.upsert(site("x", index = 0)) }
        // 原文件保持原样，用户还有机会手工修复
        assertEquals(halfWritten, store.files["sites.json"])
        assertTrue(logger.hasError)
    }

    @Test
    fun newerSchemaVersionIsNotRewritten() = runTest {
        store.files["sites.json"] = """{"schemaVersion":99,"items":[{"id":"a","name":"A","url":"https://a/","futureField":1}]}"""
        val repo = sites()

        assertTrue(repo.loadAll().isEmpty())
        assertFailsWith<CorruptDocumentException> { repo.upsert(site("x", index = 0)) }
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() = runTest {
        store.files["sites.json"] = """{"schemaVersion":1,"items":[{"id":"a","name":"A","url":"https://a/","iconUrl":null,"adBlockEnabled":true,"sortIndex":0,"createdAt":1,"addedLater":"x"}]}"""

        assertEquals(listOf("a"), sites().loadAll().map { it.id })
    }

    // ---------- 规则 ----------

    @Test
    fun markingSamePatternTwiceIsIdempotent() = runTest {
        val repo = rules()
        val first = repo.addMarkedRule(siteId = "a", pattern = "/track.js", kind = AdRuleKind.URL_SUBSTRING, note = null)
        // 用户手滑多点两次，不该堆出两条规则
        val second = repo.addMarkedRule(siteId = "a", pattern = " /track.js ", kind = AdRuleKind.URL_SUBSTRING, note = null)

        assertEquals(first.id, second.id)
        assertEquals(1, repo.loadAll().size)
    }

    @Test
    fun samePatternUnderDifferentScopesAreSeparateRules() = runTest {
        val repo = rules()
        repo.addMarkedRule(siteId = "a", pattern = "/promo", kind = AdRuleKind.URL_SUBSTRING, note = null)
        repo.addMarkedRule(siteId = "b", pattern = "/promo", kind = AdRuleKind.URL_SUBSTRING, note = null)

        assertEquals(2, repo.loadAll().size)
    }

    @Test
    fun setEnabledRoundTrips() = runTest {
        val repo = rules()
        val rule = repo.addMarkedRule(siteId = null, pattern = "/ads/", kind = AdRuleKind.URL_SUBSTRING, note = "全局")
        repo.setEnabled(rule.id, false)

        val reloaded = repo.loadAll().single()
        assertTrue(!reloaded.enabled)
        assertEquals("全局", reloaded.note)
    }

    // ---------- 标签会话 ----------

    @Test
    fun sessionRoundTripKeepsTabDetails() = runTest {
        val repo = tabs()
        val session = TabSession(
            activeTabId = "tab-a",
            tabs = listOf(
                SiteTab(
                    id = "tab-a",
                    siteId = "a",
                    entryUrl = "https://a/",
                    url = "https://a/watch/1",
                    title = "第1集",
                    state = TabState.ACTIVE,
                    lastActiveAt = 5L,
                    navigation = TabNavigationSnapshot(listOf("https://a/", "https://a/watch/1"), currentIndex = 1),
                ),
                SiteTab(id = "tab-b", entryUrl = "https://b/", url = "https://b/", state = TabState.BACKGROUND, lastActiveAt = 4L),
            ),
        )
        repo.save(session)

        val loaded = repo.load()
        assertEquals("tab-a", loaded.activeTabId)
        assertEquals(2, loaded.tabs.size)
        // 导航快照必须原样带回：它是 UI 判断「还能不能后退」的唯一依据
        assertEquals(1, loaded.tabs.first().navigation?.currentIndex)
        assertEquals(2, loaded.tabs.first().navigation?.urls?.size)
        assertEquals("第1集", loaded.tabs.first().title)
        assertTrue(loaded.updatedAt > 0L)
    }

    @Test
    fun corruptedSessionFileIsOverwrittenInsteadOfBlocking() = runTest {
        // 会话是便利数据：坏了就直接重写，不能像站点配置那样拒绝保存
        store.files["tabs.json"] = "{ not json"
        val repo = tabs()

        assertTrue(repo.load().tabs.isEmpty())
        repo.save(TabSession(activeTabId = "tab-a", tabs = listOf(SiteTab(id = "tab-a", entryUrl = "https://a/", url = "https://a/"))))
        assertEquals(1, repo.load().tabs.size)
    }

    @Test
    fun clearEmptiesSession() = runTest {
        val repo = tabs()
        repo.save(TabSession(activeTabId = "tab-a", tabs = listOf(SiteTab(id = "tab-a", entryUrl = "https://a/", url = "https://a/"))))
        repo.clear()

        assertTrue(repo.load().tabs.isEmpty())
        assertNull(repo.load().activeTabId)
    }

    // ---------- 下载 ----------

    @Test
    fun progressUpdateKeepsUserFieldsAndTimestamps() = runTest {
        val repo = downloads()
        val task = DownloadTask(id = "d1", mediaId = "m1", url = "https://a/x.mp4", fileName = "x.mp4", headers = mapOf("Referer" to "https://a/"), kind = MediaKind.DIRECT)
        repo.upsert(task)

        val updated = assertNotNull(
            repo.updateProgress("d1", state = DownloadState.RUNNING, progressBytes = 500L, totalBytes = 1000L),
        )
        // 进度回传不能丢掉 headers（里面有防盗链信息）与创建时间
        assertEquals("https://a/", updated.headers["Referer"])
        assertEquals(500L, updated.progressBytes)
        assertEquals(0.5f, updated.fraction)
        assertTrue(updated.createdAt > 0L && updated.updatedAt >= updated.createdAt)
    }

    @Test
    fun progressForUnknownTaskIsReportedNotSwallowed() = runTest {
        val repo = downloads()

        assertNull(repo.updateProgress("ghost", state = DownloadState.RUNNING, progressBytes = 1L, totalBytes = 2L))
        // 引擎回调了一个不存在的任务：写盘是白写的，日志必须留下痕迹，否则排障无从下手
        assertTrue(logger.entries.any { it.level == FakeLogger.Level.WARN })
    }

    @Test
    fun deleteTerminalDropsOnlyFinishedTasks() = runTest {
        val repo = downloads()
        repo.upsert(DownloadTask(id = "d1", mediaId = "m1", url = "https://a/1", fileName = "1", state = DownloadState.SUCCESS))
        repo.upsert(DownloadTask(id = "d2", mediaId = "m2", url = "https://a/2", fileName = "2", state = DownloadState.RUNNING))
        repo.upsert(DownloadTask(id = "d3", mediaId = "m3", url = "https://a/3", fileName = "3", state = DownloadState.FAILED, errorMessage = "403"))

        assertEquals(2, repo.deleteTerminal())
        assertEquals(listOf("d2"), repo.loadAll().map { it.id })
    }

    @Test
    fun adRuleSerializerWritesSchemaVersion() = runTest {
        rules().upsert(AdRule(id = "r1", pattern = "/x.js", kind = AdRuleKind.URL_SUBSTRING))

        val raw = assertNotNull(store.files["ad_rules.json"])
        assertTrue(raw.contains("\"schemaVersion\""))
        assertTrue(raw.contains("\"pattern\""))
    }

    // ---------- 设置 ----------

    @Test
    fun missingSettingsFileReturnsDefaults() = runTest {
        val loaded = settings().load()
        assertEquals(AppSettings(), loaded)
        assertTrue(loaded.floatingTriggerEnabled)
        assertEquals(TriggerDock.TOP_LEFT, loaded.triggerDock)
        assertEquals(TriggerSize.MEDIUM, loaded.triggerSize)
    }

    @Test
    fun settingsRoundTripKeepsEveryField() = runTest {
        val repo = settings()
        val custom = AppSettings(
            floatingTriggerEnabled = false,
            edgeSwipeDrawerEnabled = false,
            triggerDock = TriggerDock.TOP_RIGHT,
            idleDimmingEnabled = false,
            tapPageCollapseEnabled = false,
            triggerSize = TriggerSize.LARGE,
            triggerOffsetXDp = -120.5f,
            triggerOffsetYDp = 64f,
        )
        repo.save(custom)

        assertEquals(custom, repo.load())
    }

    @Test
    fun corruptedSettingsFileIsOverwrittenInsteadOfBlocking() = runTest {
        // 设置与会话同为便利数据：坏了回落默认值并可重写，不能像站点配置那样拒绝保存
        store.files["settings.json"] = "{ not json"
        val repo = settings()

        assertEquals(AppSettings(), repo.load())
        repo.save(AppSettings(triggerSize = TriggerSize.SMALL))
        assertEquals(TriggerSize.SMALL, repo.load().triggerSize)
    }
}
