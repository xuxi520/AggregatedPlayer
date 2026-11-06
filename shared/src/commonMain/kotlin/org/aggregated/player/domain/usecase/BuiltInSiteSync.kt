package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteSource

/** 内置目录里的一条声明：只关心展示名与 URL（URL 是跨版本对齐的唯一键）。 */
data class BuiltInSpec(val name: String, val url: String)

/** 同步计划：需要写入的站点 + 需要删除的站点 id。二者都为空表示本次无需改动磁盘。 */
data class BuiltInSyncPlan(
    val upsert: List<Site>,
    val deleteIds: List<String>,
) {
    val isEmpty: Boolean
        get() = upsert.isEmpty() && deleteIds.isEmpty()
}

/**
 * 内置站点同步（纯逻辑，可单测；设计文档 §4.7、来源决策见 [SiteSource]）。
 *
 * 以 **URL 为键**把本地站点表对齐到当前版本的内置目录，规则：
 * 1. 目录里有、本地没有 → 新建为 [SiteSource.BUILTIN]；
 * 2. 目录里有、本地也有（无论原来源）→ 归位为 [SiteSource.BUILTIN]，**复用原 id**（不打断已开的标签），
 *    并同步名称与排序；仅当字段确有变化才产出一条 upsert（幂等，避免每次启动都写盘）；
 * 3. 本地是内置、但目录已移除 → 删除（这就是「随版本删内置站」）；
 * 4. 本地是用户站且 URL 不在目录 → **永不删除**（跨版本保留）；
 * 5. 与某目录 URL 同址、却没被选中为匹配项的重复记录 → 删除（去重，含被内置顶替的同址用户站）。
 *
 * @param now 时间源，用于新建站点的 id 与 createdAt；注入而非直接调时钟以保证可单测。
 */
object BuiltInSiteSync {

    fun plan(
        existing: List<Site>,
        catalog: List<BuiltInSpec>,
        now: () -> Long,
    ): BuiltInSyncPlan {
        val catalogUrls = catalog.mapTo(mutableSetOf()) { it.url }
        val upsert = mutableListOf<Site>()
        val keepIds = mutableSetOf<String>()

        catalog.forEachIndexed { index, spec ->
            val match = existing.firstOrNull { it.url == spec.url }
            if (match != null) {
                keepIds += match.id
                val desired = match.copy(
                    name = spec.name,
                    url = spec.url,
                    source = SiteSource.BUILTIN,
                    sortIndex = index,
                )
                if (desired != match) upsert += desired
            } else {
                val timestamp = now()
                val created = Site(
                    id = Ids.next("site", timestamp),
                    name = spec.name,
                    url = spec.url,
                    sortIndex = index,
                    createdAt = timestamp,
                    source = SiteSource.BUILTIN,
                )
                keepIds += created.id
                upsert += created
            }
        }

        val deleteIds = existing
            .filter { it.id !in keepIds && (it.source == SiteSource.BUILTIN || it.url in catalogUrls) }
            .map { it.id }

        return BuiltInSyncPlan(upsert = upsert, deleteIds = deleteIds)
    }
}
