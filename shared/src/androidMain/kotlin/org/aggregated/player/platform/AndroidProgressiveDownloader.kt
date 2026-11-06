package org.aggregated.player.platform

import android.content.Context
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.MediaDownloadEngine
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 保存目录（相对 `filesDir`，应用私有、无需任何存储权限）。 */
private const val DOWNLOAD_DIR = "downloads"
private const val BUFFER_SIZE = 8 * 1024
private const val PROGRESS_REPORT_BYTES = 256 * 1024L
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206

/**
 * Android 直链下载器（阶段 4-A）：`HttpURLConnection` 直接写 `filesDir/downloads`。
 *
 * 不用 Media3 `DownloadManager`：它主要为 HLS/DASH 分片设计，纯直链单文件反而笨重；
 * 它留到阶段 4-B。本实现只做三件事：透传防盗链头、支持 `Range` 续传、把结果收敛成
 * [DownloadProgress]，绝不把平台异常抛过接口边界。
 *
 * 取消用 `AtomicBoolean` + `disconnect()`：下载在独立守护线程上跑，
 * 生命周期跟随进程而非 Activity，因此切标签不会中断下载。
 */
internal class AndroidProgressiveDownloader(
    private val context: Context,
) : MediaDownloadEngine {

    override fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle {
        val cancelled = AtomicBoolean(false)
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val thread = Thread({
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
        }, "agg-dl-${task.id}")
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
        val file = outputFile(task)
        val existing = if (file.exists()) file.length() else 0L
        val connection = (URL(task.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Referer / Cookie / User-Agent 一个都不能少，否则防盗链站点直接 403
            task.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        connectionRef.set(connection)
        try {
            val code = connection.responseCode
            if (cancelled.get()) {
                onProgress(DownloadProgress(existing, state = DownloadState.CANCELED))
                return
            }
            if (code != HTTP_OK && code != HTTP_PARTIAL_CONTENT) {
                onProgress(DownloadProgress(0L, state = DownloadState.FAILED, errorMessage = "HTTP $code"))
                return
            }
            // 服务器不支持 Range 时返回 200，此时必须从头覆盖写，不能追加
            val append = code == HTTP_PARTIAL_CONTENT && existing > 0L
            val base = if (append) existing else 0L
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val total = if (contentLength > 0L) base + contentLength else 0L
            var written = base
            var lastReported = base
            connection.inputStream.use { input ->
                FileOutputStream(file, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled.get()) {
                            onProgress(DownloadProgress(written, total, DownloadState.CANCELED))
                            return
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_REPORT_BYTES) {
                            lastReported = written
                            onProgress(DownloadProgress(written, total, DownloadState.RUNNING))
                        }
                    }
                    output.flush()
                }
            }
            if (cancelled.get()) {
                onProgress(DownloadProgress(written, total, DownloadState.CANCELED))
                return
            }
            onProgress(
                DownloadProgress(
                    bytesWritten = written,
                    totalBytes = total,
                    state = DownloadState.SUCCESS,
                    savePath = file.absolutePath,
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun deleteCompletedArtifacts(task: DownloadTask) {
        deleteOutputFile(task)
    }

    override fun discardPartialArtifacts(task: DownloadTask) {
        deleteOutputFile(task)
    }

    /**
     * 直链下载**直接写目标文件**（无 .tmp 中转），所以「已完成产物」与「未完成残留」是同一个文件：
     * 删掉它既释放空间，也避免下次重新下载时被 `Range` 续传成旧的半截文件。
     */
    private fun deleteOutputFile(task: DownloadTask) {
        runCatching { outputFile(task).takeIf { it.exists() }?.delete() }
    }

    private fun outputFile(task: DownloadTask): File =
        File(File(context.filesDir, DOWNLOAD_DIR).apply { mkdirs() }, task.fileName)
}
