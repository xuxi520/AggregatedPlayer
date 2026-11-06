package org.aggregated.player.platform

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import org.aggregated.player.core.UrlPathPrefix
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 每个 host 最多保留的匹配条目（每个媒体登记 path + 目录两条）。 */
private const val MAX_ENTRIES_PER_HOST = 64

/** 最多保留多少个 host 的请求头（超出淘汰最久未访问的）。 */
private const val MAX_HOSTS = 64

/**
 * 防盗链头注入器（HLS/DASH 离线下载与离线播放共用）。
 *
 * Media3 `DownloadManager` 的 `HttpDataSource.Factory` 是全局的，无法按 `DownloadRequest` 精细设头；
 * 于是用 `ResolvingDataSource`：下载/播放前调用 [registerHeaders] 登记「URL 前缀 → 该媒体的请求头」，
 * 之后每个请求按 uri 查出最合适的头注入进 `DataSpec.httpHeaders`，过掉站点防盗链。
 *
 * **为什么按 URL 而不是只按 host**（旧实现只按 host，同 host 后写覆盖先写）：
 * 同一站点常有多个防盗链不同的媒体（如每集一个 token、每部剧一个 Referer），
 * 按 host 覆盖会让「后一次播放」把「前一次」的 token 冲掉，导致另一部下载 403。
 * 现在注册时同时登记两条前缀——**完整 path**（清单自身）与**所在目录**（分片/子清单通常在同目录），
 * 解析时取**最长前缀命中**（按路径段边界比对，`/a/b` 命中 `/a/b/c.ts` 但不命中 `/a/bc`），
 * 于是同 host 的不同剧集/不同集数各自命中自己那条，互不干扰。
 * 只有当请求路径与该 host 下所有条目都不匹配时，才回退到该 host 最"通用"（前缀最短）的一条，
 * 以保留旧行为里「同站点防盗链本就一致」的兜底可用性。
 *
 * 两个刻意的边界：
 * - **前缀只看 path、不看 query**：清单 URL 常带 `?token=`，而分片 URL 通常不带，
 *   若把 query 计入前缀会导致分片一条都匹配不上。
 * - **头是合并不是替换**：`DataSpec.withRequestHeaders` 是替换语义，会丢掉 Media3/上游已设的头，
 *   故这里改为在原有头之上叠加（同名以注入的为准）。
 *
 * 另一个刻意的**不作为**：请求落在**别的 host**（如 CDN）时不注入任何头——
 * 那意味着把 Referer/Cookie 发给第三方域名，代价大于收益，故不做跨 host 兜底。
 */
@UnstableApi
internal class HeaderInjectingDataSourceFactory(
    private val upstream: DefaultHttpDataSource.Factory,
) : DataSource.Factory {

    /** 一条「URL 前缀 → 请求头」记录；[seq] 用于 LRU 淘汰（越大越新）。 */
    private class Entry(val prefix: String, val headers: Map<String, String>, val seq: Long)

    /** 单个 host 的前缀表。 */
    private class HostGroup {

        val entries = CopyOnWriteArrayList<Entry>()

        /** 最近一次访问序号，用于整组淘汰。 */
        @Volatile
        var lastAccess: Long = 0L

        /** 同前缀覆盖（token 刷新场景），不同前缀追加。 */
        @Synchronized
        fun upsert(prefix: String, headers: Map<String, String>, seq: Long) {
            entries.removeAll { it.prefix == prefix }
            entries.add(Entry(prefix, headers, seq))
        }

        /** 超出上限时淘汰最久未更新的条目。 */
        @Synchronized
        fun trimTo(max: Int) {
            if (entries.size <= max) return
            val dropCount = entries.size - max
            val dropped = entries.sortedBy { it.seq }.take(dropCount).toSet()
            entries.removeAll(dropped)
        }
    }

    private val groups = ConcurrentHashMap<String, HostGroup>()
    private val sequence = AtomicLong()

    /**
     * 登记某媒体的请求头，供后续该媒体（清单/分片）请求复用。
     *
     * 登记两条前缀：完整 path 与所在目录；同 path 再次登记即覆盖（适配 token 刷新）。
     */
    fun registerHeaders(url: String, headers: Map<String, String>) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null) return
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return
        val path = uri.encodedPath.orEmpty()
        val seq = sequence.incrementAndGet()
        val group = groups.computeIfAbsent(host) { HostGroup() }
        group.lastAccess = seq
        group.upsert(path, headers, seq)
        // 目录前缀（不含尾斜杠）：分片/子清单通常与清单同目录
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty() && dir != path) group.upsert(dir, headers, seq)
        group.trimTo(MAX_ENTRIES_PER_HOST)
        evictOverflowHosts()
    }

    /** host 组过多时淘汰最久未访问的一组，避免长期运行内存无界增长。 */
    private fun evictOverflowHosts() {
        if (groups.size <= MAX_HOSTS) return
        val oldest = groups.entries.minByOrNull { it.value.lastAccess }?.key
        if (oldest == null) return
        groups.remove(oldest)
    }

    /**
     * 按请求 URL 选出最合适的头：无路径级命中时回退到该 host 前缀最短（最通用）的一条。
     */
    private fun resolve(uri: Uri): Map<String, String>? {
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return null
        val group = groups[host]
        if (group == null) return null
        val path = uri.encodedPath.orEmpty()
        group.lastAccess = sequence.incrementAndGet()
        val prefixes = group.entries.map { it.prefix }
        val bestPrefix = UrlPathPrefix.bestMatch(prefixes, path)
        if (bestPrefix != null) {
            return group.entries.firstOrNull { it.prefix == bestPrefix }?.headers
        }
        // 没有路径级命中：回退到该 host 下最"通用"（前缀最短）的一条
        return group.entries.minByOrNull { it.prefix.length }?.headers
    }

    override fun createDataSource(): DataSource =
        ResolvingDataSource(upstream.createDataSource()) { dataSpec ->
            val extra = resolve(dataSpec.uri)
            if (extra.isNullOrEmpty()) {
                dataSpec
            } else {
                // 用 withAdditionalHeaders（合并）而非 withRequestHeaders（替换）：
                // 后者会丢掉 Media3/上游已设的头，前者叠加且同名以注入的为准
                dataSpec.withAdditionalHeaders(extra)
            }
    }
}
