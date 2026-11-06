package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.Site

/**
 * 站点列表仓储。
 *
 * 接口里不出现任何序列化 / 文件细节（实现见 `data.JsonSiteRepository`），
 * 这是 §3.1 依赖方向约束的落点：`domain` 不得反向依赖 `data`。
 *
 * 线程约定：全部为挂起函数，实现内部负责切到后台线程做阻塞 IO。
 */
interface SiteRepository {

    /** 读取全部站点，按 [Site.sortIndex] 升序、同权重按名称排序。文件缺失时返回空列表。 */
    suspend fun loadAll(): List<Site>

    /** 新增或按 id 覆盖。 */
    suspend fun upsert(site: Site)

    suspend fun delete(siteId: String)

    /** 拖拽排序等整体重写场景。 */
    suspend fun saveAll(sites: List<Site>)
}
