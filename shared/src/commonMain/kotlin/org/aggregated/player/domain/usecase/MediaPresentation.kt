package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.SniffedMedia

/** displayName 里前导路径段的截断上限（签名 hash 动辄 40+ 字符，全显没有信息价值）。 */
private const val TAIL_SEGMENT_LIMIT = 24

/** 时长格式化换算常量。 */
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/**
 * 展示排序的类型顺序：越靠前越像「可直接播放的完整视频」。
 * 用列表而不是 ordinal，避免把展示权重耦合进 [MediaKind] 的枚举声明顺序。
 */
private val DISPLAY_ORDER = listOf(
    MediaKind.DIRECT,
    MediaKind.HLS,
    MediaKind.DASH,
    MediaKind.MSE_BLOB,
    MediaKind.UNKNOWN,
)

private fun displayRankOf(kind: MediaKind): Int = DISPLAY_ORDER.indexOf(kind)

/**
 * 嗅探结果的**展示排序**（纯函数，供面板与单测共用）。
 *
 * 用户的真实问题是「列表里哪一条才是能播的视频」：实测里同一页面会同时出现
 * 主清单、子清单、分片与广告碎片，按发现时间排根本分不出来。这里按置信度排序：
 * 类型（直链 > HLS > DASH > 内存流）→ 是否有响应头确认（C2 拿到的 Content-Type
 * 是「真视频」最硬的证据）→ 是否网络层捕获（C1/C2 优于 JS Hook 兜底）→ 发现时间。
 */
fun List<SniffedMedia>.sortedForDisplay(): List<SniffedMedia> = sortedWith(
    compareBy(
        { displayRankOf(it.kind) },
        { if (it.mimeType != null) 0 else 1 },
        { if (it.viaJsHook) 1 else 0 },
        { -it.discoveredAt },
    ),
)

/** 视为「文件名段」的扩展名；命中即认为这段路径有辨识度（`index.m3u8`、`bigfile.mp4`…）。 */
private val NAME_EXTENSIONS = setOf(
    "m3u8", "mpd", "mp4", "webm", "mkv", "ts", "m4s", "flv", "mov", "aac", "m4a", "mp3",
)

/**
 * 嗅探条目的**可辨识名称**（纯函数）。
 *
 * 首行展示不能直接用 URL：实测站点（如 `…/848882ba…/index.m3u8?sign=…`）末段
 * 不是签名 hash 就是泛用的 `index.m3u8`。这里取「含媒体扩展名的路径段 + 它的前一段」，
 * 让用户至少能看到 `848882ba…/index.m3u8` 这样的形态；页面标题可用时优先标题。
 */
fun mediaDisplayName(media: SniffedMedia): String {
    media.title?.takeIf { it.isNotBlank() }?.let { return it }
    if (media.kind == MediaKind.MSE_BLOB) return "网页内存流"
    val segments = media.url.substringBefore('#').substringBefore('?')
        .split('/').filter { it.isNotBlank() }
    val extIndex = segments.indexOfLast { it.substringAfterLast('.', "").lowercase() in NAME_EXTENSIONS }
    return when {
        extIndex > 0 -> segments[extIndex - 1].take(TAIL_SEGMENT_LIMIT) + "/" + segments[extIndex]
        extIndex == 0 -> segments[extIndex]
        else -> segments.lastOrNull() ?: media.url
    }
}

/**
 * 来源置信度提示：告诉用户这条结果是怎么被发现的。
 * 「响应头确认」>「URL 特征」>「JS 捕获」，配合 [sortedForDisplay] 使用。
 */
fun mediaSourceHint(media: SniffedMedia): String = when {
    media.mimeType != null -> "响应头确认"
    media.viaJsHook -> "JS 捕获"
    else -> "URL 特征"
}

/** 类型提示：优先展示观察到的 MIME（`video/mp4` 一眼即真），否则退化为类型说明。 */
fun mediaTypeHint(media: SniffedMedia): String =
    media.mimeType ?: when (media.kind) {
        MediaKind.DIRECT -> "直链文件"
        MediaKind.HLS -> "HLS 清单"
        MediaKind.DASH -> "DASH 清单"
        MediaKind.MSE_BLOB -> "blob 内存流"
        MediaKind.UNKNOWN -> "未判定"
    }

/**
 * 清单增强挖出的指标摘要（`1080p · 42分13秒` 形式），一项都没有则空串。
 *
 * 与 [mediaSourceHint] 的「来源置信度」互补：指标帮用户判断**资源内容**（是不是正片本体），
 * 来源帮用户判断**证据强度**（响应头确认还是 URL 猜的）。
 */
fun mediaMetricsHint(media: SniffedMedia): String =
    listOfNotNull(shortResolution(media.resolution), readableDuration(media.durationSeconds))
        .joinToString(" · ")

/** `1920x1080` → `1080p`：取两个维度里较小的一边（竖屏视频同样成立）。 */
fun shortResolution(resolution: String?): String {
    if (resolution.isNullOrBlank()) return ""
    val sides = resolution.lowercase().split("x")
        .map { it.trim().toIntOrNull() }
        .filterNotNull()
        .filter { it > 0 }
    val shortSide = sides.minOrNull() ?: return ""
    return "${shortSide}p"
}

/** 秒 → 人类可读时长（`1小时3分` / `5分20秒` / `45秒`）；空/非正数返回空串。 */
fun readableDuration(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return ""
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = seconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${secs}秒"
        else -> "${secs}秒"
    }
}
