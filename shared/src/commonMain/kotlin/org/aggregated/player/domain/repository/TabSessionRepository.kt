package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.TabSession

/**
 * 标签会话仓储（设计文档 §4.1、§4.2 第 4 条）。
 *
 * 保存时机由调用方控制，而不是每次状态跳动都落盘：
 * `lastActiveAt`、`title` 变化极频繁，直写磁盘会污染主线程并磨损闪存。
 * 约定在「切换标签后空闲、宿主 onPause、内存压力降级、关闭标签」四类时机调用 [save]。
 */
interface TabSessionRepository {

    /**
     * 读取上次会话。
     *
     * @return 无记录时返回 [TabSession] 的空值形态；解析失败同样返回空并记 `warn` 日志——
     * 会话是便利用途，绝不允许因为它读不出来而阻塞应用启动（§2.3 可回退）。
     */
    suspend fun load(): TabSession

    suspend fun save(session: TabSession)

    suspend fun clear()
}
