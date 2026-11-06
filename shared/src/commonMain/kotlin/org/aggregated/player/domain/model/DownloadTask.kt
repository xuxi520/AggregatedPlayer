package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 下载任务状态。命名与 Media3 的 `Download.STATE_*` 不做强一致映射，
 * 由 Android 侧适配层负责翻译，这样换下载实现时共享层不动。
 */
enum class DownloadState {
    PENDING,
    RUNNING,
    PAUSED,

    /** 终态：成功。 */
    SUCCESS,

    /** 终态：失败，原因见 [DownloadTask.errorMessage]。 */
    FAILED,

    /** 终态：用户取消。 */
    CANCELED,
}

/**
 * 一次嗅探媒体的后台下载任务（设计文档 §4.6，优先级 P1）。
 *
 * 持久化位置：`downloads.json`。
 * 这里存的是**任务的期望状态与最近一次进度快照**，不是下载引擎的内部状态；
 * 引擎（Media3 / OkHttp）重启后需要按 [url] 重新对账，所以对账逻辑必须容忍
 * 「文件里是 RUNNING，实际引擎里没有这个任务」的情况（进程被杀后必然出现）。
 */
@Serializable
data class DownloadTask(
    val id: String,
    val mediaId: String,
    val siteId: String? = null,

    /** 下载地址。带签名的临时地址会过期，重试时可能需要重新嗅探。 */
    val url: String,

    /** 目标文件名（不含目录）。 */
    val fileName: String,

    /** 保存路径；`null` 表示由平台使用默认下载目录。 */
    val savePath: String? = null,

    val mimeType: String? = null,

    /** 复放所需的请求头快照，与 [SniffedMedia.headers] 同源。 */
    val headers: Map<String, String> = emptyMap(),

    val state: DownloadState = DownloadState.PENDING,

    val progressBytes: Long = 0L,

    /** 总字节数；HLS 分片流往往拿不到准确值，用 `0` 表示未知。 */
    val totalBytes: Long = 0L,

    /** 面向日志的失败原因，UI 展示时需配可读文案。 */
    val errorMessage: String? = null,

    /** 本地媒体类型，决定下载策略（直链单文件 vs HLS 分片）。 */
    val kind: MediaKind = MediaKind.DIRECT,

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** 是否已进入不再变化的终态；终态任务不参与「恢复所有下载」。 */
    val isTerminal: Boolean
        get() = state == DownloadState.SUCCESS || state == DownloadState.FAILED || state == DownloadState.CANCELED

    /** 进度比例 0f..1f；总量未知时为 `null`，UI 应显示转圈而非百分比。 */
    val fraction: Float?
        get() = if (totalBytes <= 0L) null else (progressBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}
