package org.aggregated.player.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.DownloadRepository
import org.aggregated.player.domain.repository.MediaDownloadEngine

/** 进度落盘节流：JSON 仓储是整表重写，高频回调会把磁盘 IO 盖过下载本身。 */
private const val PERSIST_INTERVAL_MS = 1_000L

/** 文件名最大长度，避免某些站点把整个查询串塞进 URL 末段。 */
private const val FILE_NAME_LIMIT = 80

/** 只允许这些字符进文件名，其余替换为下划线（含中文/空格/控制字符）。 */
private val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")

/** 落盘时必须剔除的敏感头：Cookie 是会话凭证，明文写进 `downloads.json` 等于泄露登录态。 */
private val SENSITIVE_HEADERS = setOf("Cookie", "Set-Cookie", "Authorization")

/**
 * 下载任务协调器（阶段 4-A）。共享层只维护状态机与进度模型，**平台层负责执行下载动作**。
 *
 * 职责边界：判定入库、节流落盘、终态清理、取消/删除、重启对账。
 * 不碰网络、不碰文件路径（由 [MediaDownloadEngine] 回报 [DownloadProgress.savePath]），
 * 因此纯逻辑可单测。UI 像读 `mediaSniffer.media` 一样直接读 [tasks]，
 * ViewModel 只转发意图，不会变成上帝组件。
 *
 * 线程：[onProgress] 由下载线程回调，所有共享状态都走 `MutableStateFlow.update` 的 CAS；
 * 只有 [lastPersistAt] 是节流用的近似值，竞态最多多写一次盘，不影响正确性。
 */
class DownloadCoordinator(
    private val repository: DownloadRepository,
    private val engine: MediaDownloadEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 当前任务列表（最新在前），UI 直接 collect。 */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)

    /** 离线缓存已用字节（HLS/DASH 分片缓存）。变化不频繁，打开设置页时刷新即可。 */
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    /** 刷新缓存占用计数（读引擎 `getOfflineCacheBytes`，失败兜底 0）。 */
    fun refreshCacheBytes() {
        _cacheBytes.value = runCatching { engine.getOfflineCacheBytes() }.getOrDefault(0L)
    }

    /**
     * 清空离线缓存并刷新占用：进行中的下载会被取消、已缓存分片删除。
     * 返回释放的字节数（供 UI 提示「已释放 XX 空间」）。
     */
    fun clearOfflineCache(): Long {
        val before = engine.getOfflineCacheBytes()
        engine.clearOfflineCache()
        val after = engine.getOfflineCacheBytes()
        refreshCacheBytes()
        return (before - after).coerceAtLeast(0L)
    }

    private val handles = MutableStateFlow<Map<String, DownloadHandle>>(emptyMap())

    @Volatile
    private var lastPersistAt = 0L

    init {
        scope.launch {
            val restored = runCatching { repository.loadAll() }.getOrDefault(emptyList())
            // 进程被杀时 RUNNING/PENDING 的任务背后已经没有引擎在跑（DownloadTask KDoc 明确要求容忍），
            // 标成 FAILED 让用户知道要重新点一次，而不是永远显示一个不动的进度条。
            val reconciled = restored.map { task ->
                if (task.state == DownloadState.RUNNING || task.state == DownloadState.PENDING) {
                    task.copy(state = DownloadState.FAILED, errorMessage = "进程重启，下载未继续")
                } else {
                    task
                }
            }
            _tasks.value = reconciled
            reconciled.forEach { persist(it) }
        }
    }

    /** 从一条嗅探结果创建任务并启动下载；内存流/未知类型直接忽略。 */
    fun enqueue(media: SniffedMedia) {
        if (!media.supportsDownload) return
        val now = clock()
        val task = DownloadTask(
            id = Ids.next("dl", now),
            mediaId = media.id,
            siteId = media.siteId,
            url = media.url,
            fileName = fileNameOf(media),
            mimeType = media.mimeType,
            headers = media.headers,
            state = DownloadState.RUNNING,
            kind = media.kind,
            createdAt = now,
            updatedAt = now,
        )
        _tasks.update { listOf(task) + it }
        persist(task)
        val handle = engine.start(task) { progress -> onProgress(task.id, progress) }
        handles.update { it + (task.id to handle) }
    }

    /**
     * 取消下载（列表里的「取消下载」）：中止引擎 + 清理未完成残留 + 删除任务记录。
     *
     * 取消即放弃：直链的部分文件会被删掉，下次重新下载从头开始（不再 `Range` 续传旧片段）——
     * 否则残留白占空间，还可能让下一次下载续到一个不完整的旧文件。
     */
    fun cancel(taskId: String) {
        handles.value[taskId]?.cancel()
        handles.update { it - taskId }
        val current = _tasks.value.firstOrNull { it.id == taskId }
        if (current != null) engine.discardPartialArtifacts(current)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        scope.launch { runCatching { repository.delete(taskId) } }
    }

    /**
     * 删除已完成的下载（列表里的「删除（含文件）」）：删除产物（直链文件 / 缓存分片）+ 删除记录。
     *
     * 与 [cancel] 的差别只在清理对象：这里删的是**已完成的产物**（含直链文件本体）。
     */
    fun delete(taskId: String) {
        handles.value[taskId]?.cancel()
        handles.update { it - taskId }
        val current = _tasks.value.firstOrNull { it.id == taskId }
        if (current != null) engine.deleteCompletedArtifacts(current)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        scope.launch { runCatching { repository.delete(taskId) } }
    }

    private fun onProgress(taskId: String, progress: DownloadProgress) {
        val current = _tasks.value.firstOrNull { it.id == taskId } ?: return
        // 取消/失败后引擎线程可能还在收尾，迟到的 RUNNING 进度不能把终态改回去
        if (current.isTerminal) return
        val terminal = progress.state.isTerminalState()
        val now = clock()
        val updated = current.copy(
            state = progress.state,
            progressBytes = progress.bytesWritten,
            totalBytes = progress.totalBytes,
            errorMessage = progress.errorMessage,
            savePath = progress.savePath ?: current.savePath,
            updatedAt = now,
            // 终态后内存里也不再留 Cookie（重试会重新从嗅探结果拿）
            headers = if (terminal) emptyMap() else current.headers,
        )
        _tasks.update { list -> list.map { if (it.id == taskId) updated else it } }
        if (terminal || now - lastPersistAt >= PERSIST_INTERVAL_MS) {
            lastPersistAt = now
            persist(updated)
        }
    }

    /** 落盘一律走这里，保证任何路径写进 `downloads.json` 的都不含凭证。 */
    private fun persist(task: DownloadTask) {
        scope.launch {
            runCatching {
                repository.upsert(
                    task.copy(
                        headers = task.headers.filterNot { (name, _) ->
                            SENSITIVE_HEADERS.any { it.equals(name, ignoreCase = true) }
                        },
                    ),
                )
            }
        }
    }

    private fun fileNameOf(media: SniffedMedia): String {
        val raw = media.url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val cleaned = UNSAFE_FILE_NAME.replace(raw, "_")
        return cleaned.take(FILE_NAME_LIMIT).ifBlank { "media_${media.id}" }
    }
}

private fun DownloadState.isTerminalState(): Boolean =
    this == DownloadState.SUCCESS || this == DownloadState.FAILED || this == DownloadState.CANCELED
