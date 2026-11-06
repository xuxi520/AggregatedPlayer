package org.aggregated.player.data

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.repository.SiteRepository
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 基于 `sites.json` 的站点仓储实现（设计文档 §4.7）。
 *
 * 排序在**读侧**完成而不是写入时固化：`sortIndex` 允许重复（批量导入时没人会去编号），
 * 让展示顺序成为查询的一部分，比强制每次写入都维护一个全局唯一的连续序号简单可靠。
 *
 * @param clock 时间来源，由 `di.AppGraph` 注入平台时钟；注入而非直接调 expect 函数是为了可单测。
 */
class JsonSiteRepository(
    documentStore: JsonDocumentStore,
    logger: Logger,
    private val clock: () -> Long,
) : SiteRepository {

    private val records = JsonRecordStore(
        documentStore = documentStore,
        relativePath = FILE_NAME,
        itemSerializer = Site.serializer(),
        logger = logger,
    )

    override suspend fun loadAll(): List<Site> =
        records.readAll().sortedWith(compareBy<Site> { it.sortIndex }.thenBy { it.name })

    override suspend fun upsert(site: Site) {
        records.mutate { current ->
            val previous = current.firstOrNull { it.id == site.id }
            // 保留既有创建时间：新建时用时钟填充。UI 更新站点信息时不应该把 createdAt 抹成 0
            val createdAt = site.createdAt.takeIf { it > 0L } ?: previous?.createdAt ?: clock()
            current.filterNot { it.id == site.id } + site.copy(createdAt = createdAt)
        }
    }

    override suspend fun delete(siteId: String) {
        records.mutate { current -> current.filterNot { it.id == siteId } }
    }

    override suspend fun saveAll(sites: List<Site>) {
        records.writeAll(sites)
    }

    private companion object {

        /** 用户资产，损坏时拒绝覆盖写（使用 [JsonRecordStore] 默认的保守策略）。 */
        const val FILE_NAME = "sites.json"
    }
}
