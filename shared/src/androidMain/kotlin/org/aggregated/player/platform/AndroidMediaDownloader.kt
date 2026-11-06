package org.aggregated.player.platform

import android.content.Context
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.MediaDownloadEngine

private const val UNSUPPORTED_KIND_HINT = "该媒体类型不支持下载"

/**
 * Android 下载引擎的**策略分派**（阶段 4-B / 4-D）。
 *
 * 按 [DownloadTask.kind] 分派到不同引擎：
 * - 直链：`AndroidProgressiveDownloader`（`HttpURLConnection` 单文件，产出可打开的本地文件）；
 * - HLS / DASH：`ExoStreamDownloader`（Media3 `DownloadManager`，产出**离线缓存**而非单文件）；
 *
 * 早期 bug（`.m3u8` 被当单文件下载成几百字节清单却标 `SUCCESS`）正是因为没有按 kind 分派，
 * 这里显式隔离；内存流/未知类型明确回报 FAILED，绝不假装成功。
 */
internal class AndroidMediaDownloader(
    context: Context,
) : MediaDownloadEngine {

    private val progressive = AndroidProgressiveDownloader(context)
    private val stream = ExoStreamDownloader(context)
    private val noopHandle = object : DownloadHandle {
        override fun cancel() = Unit
    }

    override fun getOfflineCacheBytes(): Long = Media3Offline.cacheBytesUsed

    override fun deleteCompletedArtifacts(task: DownloadTask) {
        when (task.kind) {
            MediaKind.DIRECT -> progressive.deleteCompletedArtifacts(task)
            MediaKind.HLS, MediaKind.DASH -> stream.deleteCompletedArtifacts(task)
            else -> Unit
        }
    }

    override fun discardPartialArtifacts(task: DownloadTask) {
        when (task.kind) {
            MediaKind.DIRECT -> progressive.discardPartialArtifacts(task)
            MediaKind.HLS, MediaKind.DASH -> stream.discardPartialArtifacts(task)
            else -> Unit
        }
    }

    override fun clearOfflineCache() = Media3Offline.clearCache()

    override fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle =
        when (task.kind) {
            MediaKind.DIRECT -> progressive.start(task, onProgress)
            MediaKind.HLS, MediaKind.DASH -> stream.start(task, onProgress)
            else -> {
                onProgress(DownloadProgress(0L, state = DownloadState.FAILED, errorMessage = UNSUPPORTED_KIND_HINT))
                noopHandle
            }
        }
}
