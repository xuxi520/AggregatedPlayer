package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadTask

/**
 * 媒体下载引擎的执行端口（设计文档 §4.6，阶段 4-A）。
 *
 * 放在 `domain/repository` 而不是 `platform`：[org.aggregated.player.domain.usecase.DownloadCoordinator]
 * 属于 domain，不能反向依赖 platform 包；而它本质就是一个「外部能力端口」，与 [DownloadRepository] 同层。
 *
 * 下载与播放引擎彻底解耦：第一阶段 Android 用 `HttpURLConnection` 直写 `filesDir`（直链单文件），
 * 阶段 4-B 的 HLS/DASH 用 Media3 `DownloadManager`，阶段 5 桌面用 N_m3u8DL-RE 子进程——
 * 只要各自实现本接口，共享层的状态机、进度 UI、持久化一行都不用改。
 */
interface MediaDownloadEngine {

    /**
     * 启动一次下载。[onProgress] 可能由后台线程高频回调，实现**不得抛异常穿透**，
     * 所有失败都要收敛成 [DownloadProgress.state] = `FAILED` 并附上可读原因。
     */
    fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle

    /** 离线缓存已用字节（HLS/DASH 分片缓存；直链单文件下载不计入）。无缓存能力时返回 0。 */
    fun getOfflineCacheBytes(): Long = 0L

    /** 清空离线缓存：取消进行中的下载并删除已缓存分片。无缓存能力时 no-op。 */
    fun clearOfflineCache() = Unit

    /**
     * 删除该任务**已完成**的产物：直链下载的文件、HLS/DASH 的缓存分片。
     * 用户在列表点「删除（含文件）」时调用；无产物可删时 no-op。
     */
    fun deleteCompletedArtifacts(task: DownloadTask) = Unit

    /**
     * 清理**未完成**下载的残留：直链的部分文件、HLS/DASH 的未完成分片。
     * 用户点「取消下载」时调用——取消即放弃：直链的部分文件会被删掉，
     * 下次重新下载从头开始，不会续传旧片段。
     */
    fun discardPartialArtifacts(task: DownloadTask) = Unit
}

/** 正在运行的下载句柄，用于取消。取消是尽力而为，最终状态由进度回调收敛。 */
interface DownloadHandle {

    fun cancel()
}
