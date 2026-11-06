package org.aggregated.player.data

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.repository.TabSessionRepository
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 基于 `tabs.json` 的标签会话仓储（设计文档 §4.1、§4.7）。
 *
 * 会话对象复用 [JsonRecordStore] 的「items 数组」结构，以**单元素数组**存放：
 * 好处是原子写、损坏判定、版本兼容只有一份实现，代价是多一层数组，可接受。
 *
 * 与站点/规则仓储不同，这里开了 `allowRewriteWhenUnreadable = true`：
 * 会话只是便利数据，坏了重写即可，不值得因此让用户再也存不了标签。
 */
class JsonTabSessionRepository(
    documentStore: JsonDocumentStore,
    logger: Logger,
    private val clock: () -> Long,
) : TabSessionRepository {

    private val records = JsonRecordStore(
        documentStore = documentStore,
        relativePath = FILE_NAME,
        itemSerializer = TabSession.serializer(),
        logger = logger,
        allowRewriteWhenUnreadable = true,
    )

    override suspend fun load(): TabSession = records.readAll().firstOrNull() ?: TabSession()

    /** 写入时刷新 [TabSession.updatedAt]；[TabSession] 的字段一律原样落盘。 */
    override suspend fun save(session: TabSession) {
        records.writeAll(listOf(session.copy(updatedAt = clock())))
    }

    override suspend fun clear() {
        records.writeAll(emptyList())
    }

    private companion object {

        const val FILE_NAME = "tabs.json"
    }
}
