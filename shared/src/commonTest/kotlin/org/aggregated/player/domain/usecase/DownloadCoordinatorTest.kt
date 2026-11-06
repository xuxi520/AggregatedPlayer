package org.aggregated.player.domain.usecase

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.aggregated.player.domain.model.DownloadProgress
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.repository.DownloadHandle
import org.aggregated.player.domain.repository.DownloadRepository
import org.aggregated.player.domain.repository.MediaDownloadEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DownloadCoordinator] 是纯逻辑状态机：重点验证凭证脱敏、进度节流、终态不可回退。
 * 用内存 Fake 仓储/引擎，不碰网络与文件系统。
 */
class DownloadCoordinatorTest {

    private fun media(kind: MediaKind = MediaKind.DIRECT): SniffedMedia = SniffedMedia(
        id = "media-1",
        tabId = "tab-1",
        siteId = null,
        url = "https://cdn.example.com/movie.mp4",
        kind = kind,
        headers = mapOf(
            "Referer" to "https://site.example.com",
            "Cookie" to "sid=secret",
            "User-Agent" to "UA",
        ),
    )

    @Test
    fun `入队后内存保留完整头 但落盘剔除 Cookie`() = runTest {
        var now = 0L
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { now }
        advanceUntilIdle()

        coordinator.enqueue(media())
        advanceUntilIdle()

        val inMemory = coordinator.tasks.value.single()
        assertEquals(DownloadState.RUNNING, inMemory.state)
        assertEquals("sid=secret", inMemory.headers["Cookie"], "下载线程需要原始 Cookie 才能过防盗链")
        val persisted = assertNotNull(repo.stored[inMemory.id])
        assertNull(persisted.headers["Cookie"], "Cookie 绝不能明文写进 downloads.json")
        assertNull(persisted.headers["Authorization"])
        assertEquals("https://site.example.com", persisted.headers["Referer"])
        assertEquals("UA", persisted.headers["User-Agent"])
        assertEquals("movie.mp4", persisted.fileName)
    }

    @Test
    fun `进度更新状态 但节流窗口内不重复落盘`() = runTest {
        var now = 0L
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { now }
        advanceUntilIdle()
        coordinator.enqueue(media())
        advanceUntilIdle()
        val baseline = repo.upsertCount

        now = 100L
        engine.progress?.invoke(DownloadProgress(256L, 1024L, DownloadState.RUNNING))
        advanceUntilIdle()
        assertEquals(DownloadState.RUNNING, coordinator.tasks.value.single().state)
        assertEquals(256L, coordinator.tasks.value.single().progressBytes)
        assertEquals(baseline, repo.upsertCount, "节流窗口内不应整表重写")

        now = 1_200L
        engine.progress?.invoke(DownloadProgress(512L, 1024L, DownloadState.RUNNING))
        advanceUntilIdle()
        assertEquals(baseline + 1, repo.upsertCount, "超过 1s 应落一次盘")
    }

    @Test
    fun `终态立即落盘 清内存头 且透传保存路径`() = runTest {
        var now = 0L
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { now }
        advanceUntilIdle()
        coordinator.enqueue(media())
        advanceUntilIdle()
        val baseline = repo.upsertCount

        now = 10L
        engine.progress?.invoke(
            DownloadProgress(
                bytesWritten = 1024L,
                totalBytes = 1024L,
                state = DownloadState.SUCCESS,
                savePath = "/data/user/0/app/files/downloads/movie.mp4",
            ),
        )
        advanceUntilIdle()

        val task = coordinator.tasks.value.single()
        assertEquals(DownloadState.SUCCESS, task.state)
        assertTrue(task.isTerminal)
        assertTrue(task.headers.isEmpty(), "终态后内存里也不应保留 Cookie")
        assertEquals("/data/user/0/app/files/downloads/movie.mp4", task.savePath)
        assertEquals(1f, task.fraction)
        assertEquals(baseline + 1, repo.upsertCount, "终态必须立即落盘，不能等节流窗口")
    }

    @Test
    fun `终态后的迟到进度不能把状态改回去`() = runTest {
        var now = 0L
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { now }
        advanceUntilIdle()
        coordinator.enqueue(media())
        advanceUntilIdle()

        now = 10L
        engine.progress?.invoke(DownloadProgress(1024L, 1024L, DownloadState.SUCCESS))
        engine.progress?.invoke(DownloadProgress(1L, 1024L, DownloadState.RUNNING))
        advanceUntilIdle()
        assertEquals(DownloadState.SUCCESS, coordinator.tasks.value.single().state)
        assertEquals(1024L, coordinator.tasks.value.single().progressBytes)
    }

    @Test
    fun `取消下载会中止引擎清残留并删记录 删除会删产物与记录`() = runTest {
        var now = 0L
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { now }
        advanceUntilIdle()
        coordinator.enqueue(media())
        advanceUntilIdle()
        val taskId = coordinator.tasks.value.single().id

        // 取消下载：中止引擎 + 清理未完成残留 + 删记录（列表里直接消失）
        coordinator.cancel(taskId)
        advanceUntilIdle()
        assertTrue(engine.cancelled)
        assertEquals(listOf(taskId), engine.discarded, "取消下载必须清掉未完成残留")
        assertTrue(coordinator.tasks.value.isEmpty())
        assertFalse(repo.stored.containsKey(taskId))
        assertTrue(repo.deleted.contains(taskId))

        // 删除（含文件）：删产物（直链文件/缓存分片）+ 删记录
        now = 10L
        coordinator.enqueue(media())
        advanceUntilIdle()
        val doneId = coordinator.tasks.value.single().id
        coordinator.delete(doneId)
        advanceUntilIdle()
        assertEquals(listOf(doneId), engine.deletedArtifacts, "删除含文件必须删掉产物")
        assertTrue(coordinator.tasks.value.isEmpty())
        assertTrue(repo.deleted.contains(doneId))
    }

    @Test
    fun `内存流不支持下载 不应入队`() = runTest {
        val repo = FakeDownloadRepository()
        val engine = FakeEngine()
        val coordinator = DownloadCoordinator(repo, engine, this) { 0L }
        advanceUntilIdle()
        coordinator.enqueue(media(MediaKind.MSE_BLOB))
        advanceUntilIdle()
        assertTrue(coordinator.tasks.value.isEmpty())
        assertNull(engine.lastTask)
    }

    @Test
    fun `重启后 RUNNING 任务对账为 FAILED`() = runTest {
        val repo = FakeDownloadRepository()
        repo.stored["stale"] = DownloadTask(
            id = "stale",
            mediaId = "m",
            url = "https://cdn.example.com/a.mp4",
            fileName = "a.mp4",
            state = DownloadState.RUNNING,
            progressBytes = 100L,
            totalBytes = 1000L,
        )
        val coordinator = DownloadCoordinator(repo, FakeEngine(), this) { 0L }
        advanceUntilIdle()
        assertEquals(DownloadState.FAILED, coordinator.tasks.value.single().state)
        assertEquals("进程重启，下载未继续", coordinator.tasks.value.single().errorMessage)
    }

    private class FakeDownloadRepository : DownloadRepository {
        val stored = LinkedHashMap<String, DownloadTask>()
        val deleted = mutableListOf<String>()
        var upsertCount = 0
            private set

        override suspend fun loadAll(): List<DownloadTask> = stored.values.toList()

        override suspend fun upsert(task: DownloadTask) {
            stored[task.id] = task
            upsertCount++
        }

        override suspend fun updateProgress(
            taskId: String,
            state: DownloadState,
            progressBytes: Long,
            totalBytes: Long,
            errorMessage: String?,
        ): DownloadTask? {
            val current = stored[taskId] ?: return null
            val updated = current.copy(
                state = state,
                progressBytes = progressBytes,
                totalBytes = totalBytes,
                errorMessage = errorMessage,
            )
            stored[taskId] = updated
            return updated
        }

        override suspend fun delete(taskId: String) {
            stored.remove(taskId)
            deleted += taskId
        }

        override suspend fun deleteTerminal(): Int {
            val before = stored.size
            stored.keys.toList().forEach { key ->
                if (stored[key]?.isTerminal == true) stored.remove(key)
            }
            return before - stored.size
        }
    }

    private class FakeEngine : MediaDownloadEngine {
        var lastTask: DownloadTask? = null
        var progress: ((DownloadProgress) -> Unit)? = null
        var cancelled = false

        /** 被清理未完成残留的任务 id（「取消下载」）。 */
        val discarded = mutableListOf<String>()

        /** 被删除已完成产物的任务 id（「删除（含文件）」）。 */
        val deletedArtifacts = mutableListOf<String>()

        override fun start(task: DownloadTask, onProgress: (DownloadProgress) -> Unit): DownloadHandle {
            lastTask = task
            progress = onProgress
            return object : DownloadHandle {
                override fun cancel() {
                    cancelled = true
                }
            }
        }

        override fun discardPartialArtifacts(task: DownloadTask) {
            discarded += task.id
        }

        override fun deleteCompletedArtifacts(task: DownloadTask) {
            deletedArtifacts += task.id
        }
    }
}
