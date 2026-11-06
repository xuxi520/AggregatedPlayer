package org.aggregated.player.data

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.repository.DownloadRepository
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 基于 `downloads.json` 的下载任务仓储（设计文档 §4.6）。
 *
 * 阶段 0 只提供通道，没有引擎写入。实现走「整表重写」，因此调用方必须节流
 * （见接口 KDoc）；等任务量级上升或需要断点续传对账时，本类是唯一需要替换的地方。
 */
class JsonDownloadRepository(
    documentStore: JsonDocumentStore,
    private val logger: Logger,
    private val clock: () -> Long,
) : DownloadRepository {

    private val records = JsonRecordStore(
        documentStore = documentStore,
        relativePath = FILE_NAME,
        itemSerializer = DownloadTask.serializer(),
        logger = logger,
    )

    override suspend fun loadAll(): List<DownloadTask> =
        // 新任务在前：用户回到下载页最关心「刚才那个下完了没」
        records.readAll().sortedByDescending { it.createdAt }

    override suspend fun upsert(task: DownloadTask) {
        records.mutate { current ->
            val previous = current.firstOrNull { it.id == task.id }
            val createdAt = task.createdAt.takeIf { it > 0L } ?: previous?.createdAt ?: clock()
            current.filterNot { it.id == task.id } + task.copy(createdAt = createdAt, updatedAt = clock())
        }
    }

    override suspend fun updateProgress(
        taskId: String,
        state: DownloadState,
        progressBytes: Long,
        totalBytes: Long,
        errorMessage: String?,
    ): DownloadTask? {
        var updated: DownloadTask? = null
        val now = clock()
        val changed = records.mutate { current ->
            current.map { task ->
                if (task.id != taskId) {
                    task
                } else {
                    // copy 而非重建：headers 里带着防盗链信息，进度回传不该丢字段
                    task.copy(
                        state = state,
                        progressBytes = progressBytes,
                        totalBytes = if (totalBytes > 0L) totalBytes else task.totalBytes,
                        errorMessage = errorMessage,
                        updatedAt = now,
                    ).also { updated = it }
                }
            }
        }
        // 任务不存在时不能静默返回 null：整表被白写一遍，日志里也看不出来
        if (updated == null) logger.warn("更新进度失败，任务不存在：$taskId（现有 ${changed.size} 条）")
        return updated
    }

    override suspend fun delete(taskId: String) {
        records.mutate { current -> current.filterNot { it.id == taskId } }
    }

    override suspend fun deleteTerminal(): Int {
        var removed = 0
        records.mutate { current ->
            val keep = current.filterNot { it.isTerminal }
            removed = current.size - keep.size
            keep
        }
        return removed
    }

    private companion object {

        const val FILE_NAME = "downloads.json"
    }
}
