package org.aggregated.player.platform

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

/** 离线缓存上限：2GB LRU，超出自动淘汰最久未用的分片。 */
private const val OFFLINE_CACHE_BYTES = 2L * 1024 * 1024 * 1024
private const val OFFLINE_CACHE_DIR = "offline_media"
private const val DOWNLOAD_THREADS = 4

/**
 * Media3 离线下载/播放共享的单例基础设施（阶段 4-D，HLS/DASH 离线缓存）。
 *
 * `DownloadManager` 与播放器共用同一个 [Cache]：下载的分片写进缓存，播放时 `CacheDataSource`
 * 优先命中缓存（离线可用），未命中再在线拉取并回填——这正是「离线缓存」而非「导出单文件」的语义（见 README §4.6）。
 * 初始化依赖 [AndroidPlatformContext]（Application 级），必须在 `AppGraph.create()` 之后访问。
 *
 * 注意 Media3 1.11 的 `DownloadManager` 构造签名：`(Context, DatabaseProvider, Cache, DataSource.Factory upstream, Executor)`，
 * 内部用 `Cache + upstream` 自动拼出 `CacheDataSource.Factory`，因此播放侧 [cacheDataSourceFactory] 用同样的
 * cache 与 upstream 即可零成本复用离线数据。
 */
@UnstableApi
object Media3Offline {

    private val context: Context = AndroidPlatformContext.requireContext()
    private val databaseProvider: DatabaseProvider = ExoDatabaseProvider(context)
    private val cacheDir = File(context.cacheDir, OFFLINE_CACHE_DIR)
    private val evictor = LeastRecentlyUsedCacheEvictor(OFFLINE_CACHE_BYTES)

    // 以下组件可重建：清空缓存时需整条链路重新初始化（Cache 释放后实例不能复用）。
    // 全部为 private set 的 var，仅本单例在 clearCache() 内重置，外部只读。
    // 下载线程池：必须声明在 downloadManager 之前（属性按声明顺序初始化，buildManager 会取它）
    private var downloadExecutor = newExecutor()

    var cache: Cache = buildCache()
        private set
    internal var headerInjectingFactory: HeaderInjectingDataSourceFactory = buildHeaderFactory()
        private set
    var downloadManager: DownloadManager = buildManager()
        private set
    var cacheDataSourceFactory: CacheDataSource.Factory = buildCacheFactory()
        private set

    private fun buildCache() = SimpleCache(cacheDir, evictor, databaseProvider)
    private fun buildHeaderFactory() =
        HeaderInjectingDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
    private fun buildManager() =
        DownloadManager(context, databaseProvider, cache, headerInjectingFactory, downloadExecutor)
    private fun buildCacheFactory() =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(headerInjectingFactory)
    private fun newExecutor() = Executors.newFixedThreadPool(DOWNLOAD_THREADS)

    /** 当前离线缓存占用（字节），读取失败兜底为 0。 */
    val cacheBytesUsed: Long
        get() = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * 清空离线缓存：取消所有下载、释放并重建整条链路。
     * 必须在主线程调用（Media3 组件构造依赖 Looper，与首次初始化一致）。
     * 重建后播放器/下载器下一次访问会拿到新实例，因此清空后无需重启进程。
     */
    fun clearCache() {
        // removeAllDownloads 清理下载索引；release 释放 Cache 内部锁；delete 清掉目录与数据库
        runCatching { downloadManager.removeAllDownloads() }
        runCatching { (cache as? SimpleCache)?.release() }
        runCatching { SimpleCache.delete(cacheDir, databaseProvider) }
        // 旧下载线程池随旧 DownloadManager 一并废弃，避免线程泄漏
        runCatching { downloadExecutor.shutdownNow() }
        downloadExecutor = newExecutor()
        cache = buildCache()
        headerInjectingFactory = buildHeaderFactory()
        downloadManager = buildManager()
        cacheDataSourceFactory = buildCacheFactory()
    }
}
