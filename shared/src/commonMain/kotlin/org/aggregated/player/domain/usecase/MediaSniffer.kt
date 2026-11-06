package org.aggregated.player.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.model.UriParts

/**
 * 媒体类型判定结果（设计文档 §4.4）。
 *
 * 独立成对象是为了让「判定规则」可被单测直接覆盖，不必构造整个嗅探器。
 */
object MediaKindClassifier {

    /** 扩展名 → 类型。全部小写比较。 */
    private val EXTENSION_KINDS: Map<String, MediaKind> = mapOf(
        "m3u8" to MediaKind.HLS,
        "mpd" to MediaKind.DASH,
        "mp4" to MediaKind.DIRECT,
        "m4v" to MediaKind.DIRECT,
        "mov" to MediaKind.DIRECT,
        "mkv" to MediaKind.DIRECT,
        "webm" to MediaKind.DIRECT,
        "flv" to MediaKind.DIRECT,
        "avi" to MediaKind.DIRECT,
        "ts" to MediaKind.HLS,          // `.ts` 绝大多数是 HLS 分片，归到 HLS 便于走分片下载逻辑
        "m4s" to MediaKind.DASH,        // DASH 的常见分段扩展名
    )

    /**
     * 只含字母数字的扩展名才可信，其余（如 `.php` 动态出口）交给 MIME 判断。
     *
     * 判定优先级：**MIME > 扩展名 > 整串特征**。
     * 走 C1（URL 特征）通道时 [mimeType] 通常是 `null`，此时只能靠 URL 猜，
     * 猜错（例如把 `.php?file=x.mp4` 判成直链）的代价是多一个可选项，不会破坏播放，
     * 所以宁可放宽也不要在这里抛错。
     */
    fun classify(url: String, mimeType: String? = null): MediaKind {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return MediaKind.UNKNOWN

        // blob: / 内存流：外部引擎与下载器都用不了，必须单独成类（§2.2 能力边界）
        if (trimmed.startsWith("blob:", ignoreCase = true) ||
            trimmed.startsWith("mediasource:", ignoreCase = true)
        ) {
            return MediaKind.MSE_BLOB
        }

        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (mime != null) {
            when {
                mime.contains("mpegurl") || mime == "application/x-mpegurl" -> return MediaKind.HLS
                mime.contains("dash") -> return MediaKind.DASH
                mime.startsWith("video/") -> return MediaKind.DIRECT
                // audio/* 一并认下来：播客类站点的外链同样需要外部播放
                mime.startsWith("audio/") -> return MediaKind.DIRECT
            }
        }

        val parts = UriParts.parse(trimmed)
        val byExtension = EXTENSION_KINDS[parts?.fileExtension.orEmpty()]
        if (byExtension != null) return byExtension

        // 兜底：扩展名不在末段（`/video.m3u8?sign=…` 已由 parse 处理，这里覆盖
        // `/manifest.m3u8/download`、路径内含 `hls/` 之类的非标准写法）
        val lowered = trimmed.lowercase()
        return when {
            lowered.contains(".m3u8") -> MediaKind.HLS
            lowered.contains(".mpd") -> MediaKind.DASH
            else -> MediaKind.UNKNOWN
        }
    }
}

/**
 * 嗅探的输入：平台层在一次请求/响应观察中能拿到的全部线索。
 *
 * 三个通道（C1 URL 特征 / C2 响应头 / C3 JS Hook）都收敛到这一个结构，
 * 平台侧不需要关心判定与去重逻辑。
 */
data class SniffCandidate(
    val tabId: String,
    val siteId: String?,
    val url: String,
    val pageUrl: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val contentLengthBytes: Long? = null,
    val viaJsHook: Boolean = false,
    val title: String? = null,
)

/**
 * 媒体流嗅探器（设计文档 §4.4）。
 *
 * 纯逻辑：不碰网络、不碰 WebView，只把平台层观察到的线索判定、去重后放进**内存队列**，
 * 队列非空才驱动 UI 显示「使用外部引擎播放」——这是「嗅探成功才提供增强」这条需求的落点。
 *
 * 并发：[ingest] 会被 WebView 的多个工作线程调用，而 `commonMain` 没有 `synchronized`，
 * 因此用 [MutableStateFlow.update] 的 CAS 重试循环保证队列操作的原子性，而不是加锁。
 *
 * @param capacity 队列上限。超限时丢弃最旧条目：旧流地址往往已过期（带签名），
 *   留着只会让用户点了播放却失败。
 */
class MediaSniffer(
    private val logger: Logger,
    private val clock: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val _media = MutableStateFlow<List<SniffedMedia>>(emptyList())

    /** 当前标签的嗅探结果，按发现时间倒序（最新在前）。 */
    val media: StateFlow<List<SniffedMedia>> = _media.asStateFlow()

    /**
     * 投入一条候选流。
     *
     * @return 入队后的条目；被判定为 [MediaKind.UNKNOWN]（噪声）或被去重合并时返回**既有**条目，
     *   返回 `null` 只发生在候选被丢弃的情况下。调用方据此决定是否提示用户。
     */
    fun ingest(candidate: SniffCandidate): SniffedMedia? {
        val kind = MediaKindClassifier.classify(candidate.url, candidate.mimeType)
        if (kind == MediaKind.UNKNOWN) {
            // 不告警：正常浏览里绝大多数请求都不是媒体流，日志会被刷满
            logger.debug("忽略无法判定的候选流：${candidate.url.take(LOG_URL_LIMIT)}")
            return null
        }

        val discoveredAt = clock()
        var outcome: SniffedMedia? = null
        _media.update { current ->
            val existing = current.firstOrNull { it.url == candidate.url && it.tabId == candidate.tabId }
            val next = if (existing != null) {
                // 去重合并：早期只靠 URL 猜（无 MIME、无体积），后续 C2 拿到真实响应头时补全，
                // 但不能把 discoveredAt 改掉——排序与过期判断都以首次发现为准
                val merged = existing.copy(
                    mimeType = candidate.mimeType ?: existing.mimeType,
                    estimatedSizeBytes = candidate.contentLengthBytes ?: existing.estimatedSizeBytes,
                    headers = if (candidate.headers.isNotEmpty()) candidate.headers else existing.headers,
                    kind = if (existing.kind == MediaKind.UNKNOWN) kind else existing.kind,
                    title = candidate.title ?: existing.title,
                )
                outcome = merged
                current.map { if (it === existing) merged else it }
            } else {
                val created = SniffedMedia(
                    id = Ids.next("media", discoveredAt),
                    tabId = candidate.tabId,
                    siteId = candidate.siteId,
                    url = candidate.url,
                    pageUrl = candidate.pageUrl,
                    kind = kind,
                    mimeType = candidate.mimeType,
                    headers = candidate.headers,
                    estimatedSizeBytes = candidate.contentLengthBytes,
                    discoveredAt = discoveredAt,
                    viaJsHook = candidate.viaJsHook,
                    title = candidate.title,
                )
                outcome = created
                // 最新在前，超限裁掉队尾
                (listOf(created) + current).take(capacity)
            }
            next
        }
        outcome?.let { logger.info("嗅探到 ${it.kind}：${it.url.take(LOG_URL_LIMIT)}") }
        return outcome
    }

    /**
     * 写回清单增强结果（分辨率/时长/估算体积）。
     *
     * 增强是「尽力而为」：任何字段解析不到传 `null` 即保留原值；调用即视为
     * 该条目已尝试过（[SniffedMedia.enriched] 置真），失败不会在队列重放时重试——
     * 否则一条坏清单会让每次 UI 刷新都白打一次网络请求。
     */
    fun enrich(
        id: String,
        resolution: String?,
        durationSeconds: Long?,
        estimatedSizeBytes: Long?,
    ) {
        _media.update { current ->
            current.map { media ->
                if (media.id != id || media.enriched) {
                    media
                } else {
                    media.copy(
                        resolution = resolution ?: media.resolution,
                        durationSeconds = durationSeconds ?: media.durationSeconds,
                        estimatedSizeBytes = estimatedSizeBytes ?: media.estimatedSizeBytes,
                        enriched = true,
                    )
                }
            }
        }
    }

    /**
     * 清空某个标签的嗅探结果。
     *
     * 调用时机：标签关闭、或主框架发生了新的导航。老地址带签名会过期，
     * 留着只会让用户点了播放然后失败（§2.3 增强失败要可解释）。
     */
    fun clearForTab(tabId: String) {
        _media.update { current -> current.filterNot { it.tabId == tabId } }
    }

    fun clearAll() {
        _media.update { emptyList() }
    }

    /** 该标签最近一条可外部播放的流，作为「使用外部引擎播放」按钮的默认目标。 */
    fun latestPlayable(tabId: String): SniffedMedia? =
        media.value.firstOrNull { it.tabId == tabId && it.supportsExternalPlayback }

    companion object {

        /** 队列容量。10 条足够覆盖多码率/多音轨的清单，再多就是噪声。 */
        const val DEFAULT_CAPACITY = 10

        private const val LOG_URL_LIMIT = 120
    }
}

/**
 * 只取某个标签的嗅探结果（站点↔标签 1:1，因此等价「只看当前站点」）。
 *
 * 队列是全局内存态（所有标签共用），但「当前页面工具」面板与自动播放偏好都只应看到当前标签的候选：
 * 否则切站点后旧站点的临时流地址会混进列表，甚至触发新页面的 ENHANCED 自动接管。
 * 纯函数放共享层，UI 与 ViewModel 共用同一口径，避免两处各写一遍 filter 而对不上。
 *
 * @param tabId 当前活动标签 id；`null`（无标签/空状态页）一律返回空列表。
 */
fun List<SniffedMedia>.forTab(tabId: String?): List<SniffedMedia> =
    if (tabId == null) emptyList() else filter { it.tabId == tabId }
