package org.aggregated.player.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.MediaDownloadEngine

private const val MIME_HLS = "application/vnd.apple.mpegurl"
private const val MIME_DASH = "application/dash+xml"
private const val NOTIFICATION_PERMISSION_HINT = "需授予通知权限才能后台下载（Android 13+ 设置里开启）"
private const val TAG = "MPOffline"

/**
 * HLS/DASH 离线下载器（阶段 4-D）：封装 Media3 `DownloadManager`。
 *
 * 与直链下载器（写单文件）不同，这里产出的是**离线缓存**：分片落到 [Media3Offline.cache]，
 * 播放器复用同一 cache 即离线可用（见 README §4.6）。进度由 `DownloadManager.Listener` 回传，
 * 终态后自动移除监听以免泄漏；下载/删除都经 `DownloadService`，由其管理前台服务与通知。
 */
@UnstableApi
internal class ExoStreamDownloader(
    private val context: Context,
) : MediaDownloadEngine {

    private val noopHandle = object : DownloadHandle { override fun cancel() = Unit }

    @Suppress("TooGenericExceptionCaught")
    override fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle =
        try {
            startOrThrow(task, onProgress)
        } catch (e: Exception) {
            // 接口契约：不得抛异常穿透。任何 Media3 初始化/调度异常都收敛成 FAILED 状态并打日志，
            // 避免未捕获异常冒泡到 UI 线程直接闪退；日志含完整栈便于定位根因。
            Log.e(TAG, "ExoStreamDownloader 启动离线下载失败: ${task.id} url=${task.url}", e)
            onProgress(
                DownloadProgress(
                    0L,
                    state = DownloadState.FAILED,
                    errorMessage = "离线下载启动失败：${e.message ?: e.javaClass.simpleName}",
                ),
            )
            noopHandle
        }

    private fun startOrThrow(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle {
        // Android 13+ 需 POST_NOTIFICATIONS 才能 startForeground；未授权时这里兜底回报失败，
        // 由 UI 引导用户去系统设置开启（入口不做运行时申请，因权限 API 是 Android 专属、不能进 commonMain）
        if (!canPostNotifications()) {
            onProgress(DownloadProgress(0L, state = DownloadState.FAILED, errorMessage = NOTIFICATION_PERMISSION_HINT))
            return noopHandle
        }
        val builder = DownloadRequest.Builder(task.id, Uri.parse(task.url))
        mimeOf(task.kind)?.let { builder.setMimeType(it) }
        val request = builder.build()
        // 登记防盗链头：下载的清单/分片请求按 host 注入（见 HeaderInjectingDataSourceFactory）
        Media3Offline.headerInjectingFactory.registerHeaders(task.url, task.headers)
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.request.id != task.id) return
                onProgress(mapDownload(download))
                if (download.state.isTerminal()) manager.removeListener(this)
            }
        }
        Media3Offline.downloadManager.addListener(listener)
        // foreground=true：用户从 App 前台点击触发，按 Media3 约定显式前台启动。
        // 若传 false，Media3 走普通 startService，在 Android 12+ 其内部再调 startForeground 会被系统
        // 以「非前台服务启动」拒绝而抛 ForegroundServiceStartNotAllowedException，且异常发生在
        // Service 主线程、sendAddDownload 返回之后，UI 层 try/catch 兜不住 —— 表现为点击下载直接闪退。
        DownloadService.sendAddDownload(context, MediaDownloadService::class.java, request, true)
        return object : DownloadHandle {
            override fun cancel() {
                Media3Offline.downloadManager.removeListener(listener)
                Media3Offline.downloadManager.removeDownload(task.id)
            }
        }
    }

    override fun deleteCompletedArtifacts(task: DownloadTask) {
        removeCacheOf(task)
    }

    override fun discardPartialArtifacts(task: DownloadTask) {
        removeCacheOf(task)
    }

    /**
     * HLS/DASH 的产物在 Media3 缓存里，所以「删产物」与「清残留」是同一个动作：
     * `removeDownload` 既停掉进行中的下载，也清掉该任务已落盘的分片。
     */
    private fun removeCacheOf(task: DownloadTask) {
        runCatching { Media3Offline.downloadManager.removeDownload(task.id) }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun mimeOf(kind: MediaKind): String? = when (kind) {
        MediaKind.HLS -> MIME_HLS
        MediaKind.DASH -> MIME_DASH
        else -> null
    }

    private fun mapDownload(download: Download): DownloadProgress {
        val state = when (download.state) {
            Download.STATE_COMPLETED -> DownloadState.SUCCESS
            Download.STATE_FAILED -> DownloadState.FAILED
            Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_REMOVING, Download.STATE_RESTARTING -> DownloadState.RUNNING
            Download.STATE_STOPPED -> DownloadState.PAUSED
            else -> DownloadState.RUNNING
        }
        val total = if (download.contentLength > 0) download.contentLength else 0L
        val errorMessage = if (download.state == Download.STATE_FAILED) "离线下载失败" else null
        return DownloadProgress(download.bytesDownloaded, total, state, errorMessage)
    }
}

/** 下载终态：完成后不再变化；失败由用户重新触发。 */
private fun Int.isTerminal(): Boolean = this == Download.STATE_COMPLETED || this == Download.STATE_FAILED
