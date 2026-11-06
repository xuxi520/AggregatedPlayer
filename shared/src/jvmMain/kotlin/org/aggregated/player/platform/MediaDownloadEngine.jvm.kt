package org.aggregated.player.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.MediaDownloadEngine

private const val BUFFER_SIZE = 8 * 1024
private const val PROGRESS_REPORT_BYTES = 256 * 1024L
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private const val DOWNLOAD_DIR_NAME = "AggregatedPlayer"

/**
 * 桌面端直链下载器：`HttpURLConnection` 流式写盘。
 *
 * 与 Android 侧同款语义：透传防盗链头（Referer/Cookie/UA）、`Range` 续传、进度按字节节流、
 * 取消用 `disconnect()`（下载跑在守护线程上，切标签/关窗口都不中断）。
 * 差别只在保存目录：桌面端落在用户「下载」目录下的 `AggregatedPlayer` 子目录。
 *
 * HLS/DASH 分片流需要外部下载工具（N_m3u8DL-RE），桌面端暂不支持，如实回报失败。
 */
private class JvmProgressiveDownloader : MediaDownloadEngine {

    override fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle {
        if (task.kind != MediaKind.DIRECT) {
            onProgress(
                DownloadProgress(
                    bytesWritten = 0L,
                    state = DownloadState.FAILED,
                    errorMessage = "桌面端暂不支持 HLS/DASH 分片下载（需 N_m3u8DL-RE），请改用直链资源",
                ),
            )
            return object : DownloadHandle { override fun cancel() = Unit }
        }
        val cancelled = AtomicBoolean(false)
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val thread = Thread(
            {
                runCatching { download(task, onProgress, cancelled, connectionRef) }
                    .onFailure { error ->
                        onProgress(
                            DownloadProgress(
                                bytesWritten = 0L,
                                state = DownloadState.FAILED,
                                errorMessage = error.message ?: "下载失败",
                            ),
                        )
                    }
            },
            "agg-dl-${task.id}",
        )
        thread.isDaemon = true
        thread.start()
        return object : DownloadHandle {
            override fun cancel() {
                cancelled.set(true)
                runCatching { connectionRef.get()?.disconnect() }
            }
        }
    }

    private fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit,
        cancelled: AtomicBoolean,
        connectionRef: AtomicReference<HttpURLConnection?>,
    ) {
        val target = resolveTarget(task)
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = open(task.url, task.headers, existing)
        connectionRef.set(connection)
        val status = connection.responseCode
        if (status != HTTP_OK && status != HTTP_PARTIAL_CONTENT) {
            error("下载失败：HTTP $status")
        }
        // 服务器真的按 Range 续传（206）且本地已有部分内容 → 追加写；否则从头覆盖写
        val append = status == HTTP_PARTIAL_CONTENT && existing > 0L
        var written = if (append) existing else 0L
        var reported = written
        val total = connection.contentLengthLong.takeIf { it > 0L }?.let { it + written } ?: 0L
        connection.inputStream.use { input ->
            FileOutputStream(target, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancelled.get()) throw IOException("下载已取消")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - reported >= PROGRESS_REPORT_BYTES) {
                        reported = written
                        onProgress(
                            DownloadProgress(
                                bytesWritten = written,
                                totalBytes = total,
                                state = DownloadState.RUNNING,
                            ),
                        )
                    }
                }
            }
        }
        onProgress(
            DownloadProgress(
                bytesWritten = written,
                totalBytes = total,
                state = DownloadState.SUCCESS,
                savePath = target.absolutePath,
            ),
        )
    }

    private fun open(url: String, headers: Map<String, String>, existing: Long): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        return connection
    }

    private fun resolveTarget(task: DownloadTask): File {
        val explicit = task.savePath
        val file = if (explicit != null) File(explicit) else File(downloadDir(), task.fileName)
        file.parentFile?.mkdirs()
        return file
    }

    private fun downloadDir(): File =
        Path.of(System.getProperty("user.home"), "Downloads", DOWNLOAD_DIR_NAME).toFile()
}

actual fun createMediaDownloadEngine(): MediaDownloadEngine = JvmProgressiveDownloader()
