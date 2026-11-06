package org.aggregated.player.domain.model

/**
 * 下载引擎回传的一次进度快照（设计文档 §4.6）。
 *
 * 平台层只负责「把字节写进文件」，状态机与落盘节流都归 [org.aggregated.player.domain.usecase.DownloadCoordinator]，
 * 因此这里刻意不携带任何平台类型，也不携带完整请求头（避免把 Cookie 带到日志/回调链里）。
 *
 * @param bytesWritten 累计已写入字节（含续传起点偏移）。
 * @param totalBytes 总字节数；`0` 表示服务器未给 Content-Length，UI 应显示不确定进度。
 * @param savePath 成功时的本地绝对路径；由平台层填写，共享层只透传到 [DownloadTask.savePath]。
 */
data class DownloadProgress(
    val bytesWritten: Long,
    val totalBytes: Long = 0L,
    val state: DownloadState = DownloadState.RUNNING,
    val errorMessage: String? = null,
    val savePath: String? = null,
)
