package org.aggregated.player

import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteSource
import org.aggregated.player.domain.usecase.BuiltInSiteSync
import org.aggregated.player.domain.usecase.BuiltInSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BuiltInSiteSync] 的用例：内置目录以 URL 为键对齐，核心不变量是
 * 「用户站绝不被删、内置站随目录增删、重复执行幂等」。
 */
class BuiltInSiteSyncTest {

    private fun clock(): () -> Long {
        var t = 1_000L
        return { t += 10; t }
    }

    private fun user(id: String, url: String) =
        Site(id = id, name = "U$id", url = url, source = SiteSource.USER)

    private fun builtin(id: String, url: String) =
        Site(id = id, name = "B$id", url = url, source = SiteSource.BUILTIN)

    @Test
    fun `空表按目录新建全部内置站`() {
        val catalog = listOf(BuiltInSpec("A", "https://a/"), BuiltInSpec("B", "https://b/"))
        val plan = BuiltInSiteSync.plan(emptyList(), catalog, clock())

        assertEquals(2, plan.upsert.size)
        assertTrue(plan.deleteIds.isEmpty())
        assertTrue(plan.upsert.all { it.source == SiteSource.BUILTIN })
        assertEquals(listOf(0, 1), plan.upsert.map { it.sortIndex })
    }

    @Test
    fun `用户站不在目录则保留不删`() {
        val existing = listOf(user("u1", "https://mine/"))
        val plan = BuiltInSiteSync.plan(existing, listOf(BuiltInSpec("A", "https://a/")), clock())

        assertTrue(plan.deleteIds.isEmpty())
        assertEquals(listOf("https://a/"), plan.upsert.map { it.url })
    }

    @Test
    fun `目录已移除的内置站被删除`() {
        val existing = listOf(builtin("b1", "https://gone/"))
        val plan = BuiltInSiteSync.plan(existing, emptyList(), clock())

        assertEquals(listOf("b1"), plan.deleteIds)
    }

    @Test
    fun `同址用户站被归位为内置且复用原id`() {
        val existing = listOf(user("u1", "https://a/"))
        val plan = BuiltInSiteSync.plan(existing, listOf(BuiltInSpec("新名", "https://a/")), clock())

        val upserted = plan.upsert.single()
        assertEquals("u1", upserted.id) // 复用 id，不打断已开标签
        assertEquals(SiteSource.BUILTIN, upserted.source)
        assertEquals("新名", upserted.name)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `目录未变时幂等不产生写盘`() {
        val catalog = listOf(BuiltInSpec("A", "https://a/"))
        val existing = BuiltInSiteSync.plan(emptyList(), catalog, clock()).upsert
        // 用上一轮的落盘结果作为本轮 existing，应无任何改动
        val second = BuiltInSiteSync.plan(existing, catalog, clock())

        assertTrue(second.isEmpty)
    }

    @Test
    fun `同址重复记录去重只保留一条`() {
        val existing = listOf(user("u1", "https://a/"), builtin("b1", "https://a/"))
        val plan = BuiltInSiteSync.plan(existing, listOf(BuiltInSpec("A", "https://a/")), clock())

        // 第一条匹配项被保留并归位，另一条同址记录作为重复被删
        assertEquals(1, plan.upsert.size)
        assertEquals(1, plan.deleteIds.size)
        assertTrue(plan.deleteIds.all { it == "b1" })
    }
}
