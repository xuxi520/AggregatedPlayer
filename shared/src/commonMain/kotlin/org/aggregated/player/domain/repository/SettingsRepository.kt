package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.AppSettings

/**
 * 应用设置仓储（设计文档 §4.7）。
 *
 * 与标签会话同属「便利数据」：读不出来时用默认值兜底、允许重写，绝不因设置坏掉而阻塞启动。
 */
interface SettingsRepository {

    /** 读取设置；文件缺失或损坏时返回 [AppSettings] 默认值。 */
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}
