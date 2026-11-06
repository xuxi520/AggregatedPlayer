package org.aggregated.player.data

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.AppSettings
import org.aggregated.player.domain.repository.SettingsRepository
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 基于 `settings.json` 的设置仓储（设计文档 §4.7）。
 *
 * 复用 [JsonRecordStore] 的「items 数组」结构，以**单元素数组**存放整个 [AppSettings]：
 * 与 [JsonTabSessionRepository] 同理，让原子写、损坏判定、版本兼容只有一份实现。
 * 设置属便利数据，坏了直接重写（`allowRewriteWhenUnreadable = true`），读不出来回落默认值。
 */
class JsonSettingsRepository(
    documentStore: JsonDocumentStore,
    logger: Logger,
) : SettingsRepository {

    private val records = JsonRecordStore(
        documentStore = documentStore,
        relativePath = FILE_NAME,
        itemSerializer = AppSettings.serializer(),
        logger = logger,
        allowRewriteWhenUnreadable = true,
    )

    override suspend fun load(): AppSettings = records.readAll().firstOrNull() ?: AppSettings()

    override suspend fun save(settings: AppSettings) {
        records.writeAll(listOf(settings))
    }

    private companion object {

        const val FILE_NAME = "settings.json"
    }
}
