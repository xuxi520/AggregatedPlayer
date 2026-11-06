package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask

/**
 * 下载任务仓储（设计文档 §4.6，P1）。
 *
 * 阶段 0 只提供持久化通道；下载引擎（Media3）的进度回传由平台适配层调用 [updateProgress] 写入。
 * 注意：进度回调频率很高，而 JSON 实现是「整表重写」，任务量级为十所以可用，
 * 但适配层必须做节流（建议 ≥ 1 秒或每 5% 进度才落一次盘），否则磁盘 IO 会盖过下载本身。
 */
interface DownloadRepository {

    suspend fun loadAll(): List<DownloadTask>

    /** 新增或按 id 覆盖。实现内部负责刷新 [DownloadTask.updatedAt]。 */
    suspend fun upsert(task: DownloadTask)

    /** 引擎回调专用：只改进度与状态，不动用户可编辑字段。 */
    suspend fun updateProgress(
        taskId: String,
        state: DownloadState,
        progressBytes: Long,
        totalBytes: Long,
        errorMessage: String? = null,
    ): DownloadTask?

    suspend fun delete(taskId: String)

    /** 清理已进入终态的记录，避免 `downloads.json` 无限增长。 */
    suspend fun deleteTerminal(): Int
}
