package org.aggregated.player.domain.model

/**
 * 媒体类型，由嗅探器根据 URL 特征与（若可用）响应头判定。
 */
enum class MediaKind {

    /** 直链文件：mp4 / webm / mkv / ts 等，外部播放与下载成功率最高。 */
    DIRECT,

    /** HLS 播放列表（`.m3u8`），外部播放可直接交给 Media3/mpv，下载需解析分片。 */
    HLS,

    /** MPEG-DASH 清单（`.mpd`）。 */
    DASH,

    /**
     * MSE / Blob 生成的内存流（`blob:` 前缀）。
     *
     * 这类地址只在产生它的页面内有效，**外部播放和下载都拿不到它**（§4.4、§4.6）；
     * 保留该类型是为了让 UI 能如实说明「为什么这个站点没有外部播放选项」。
     */
    MSE_BLOB,

    /** 未能判定，仅记录不展示，避免把噪声推给用户。 */
    UNKNOWN,
}

/**
 * 一次嗅探到的媒体流。
 *
 * 定位是**内存态**数据（`MediaSniffer` 的队列），不入库：它随页面生命周期波动、
 * 时效性极强（多数站点给的是带签名的临时地址），持久化的价值低于误导的风险。
 * 需要长期保存的是下载任务，见 [DownloadTask]。
 */
data class SniffedMedia(
    val id: String,
    val tabId: String,
    val siteId: String?,

    /** 媒体地址本身。 */
    val url: String,

    /** 判定出的页面地址，与 [url] 区分开：外部播放需要两者，Referer 取页面地址。 */
    val pageUrl: String? = null,

    val kind: MediaKind = MediaKind.UNKNOWN,

    /** 观察到的 MIME 类型；走 URL 特征通道（C1）时可能为 `null`。 */
    val mimeType: String? = null,

    /**
     * 复放该流所需的请求头快照（Referer / Origin / Cookie / User-Agent）。
     *
     * 这是外部播放能否成功的关键，见设计文档 §4.4；值由平台侧抓取后传入，
     * 共享层不做任何过滤，但**不得**把它写进日志（[headersForLog] 只输出键名）。
     */
    val headers: Map<String, String> = emptyMap(),

    /** 从 `Content-Length` 估得的字节数；未知为 `null`。仅用于 UI 提示，不做校验依据。 */
    val estimatedSizeBytes: Long? = null,

    /** `1920x1080` 形式的分辨率；清单增强解析到才填（HLS 主清单 / DASH MPD）。 */
    val resolution: String? = null,

    /** 总时长（秒）；清单增强解析到才填。用户借此区分预告片与正片。 */
    val durationSeconds: Long? = null,

    /** 清单增强是否已尝试过（成败都算）：队列每次变化都会重放，此标志防重复拉清单。 */
    val enriched: Boolean = false,

    /** 发现时间（epoch millis），用于队列排序与过期清理。 */
    val discoveredAt: Long = 0L,

    /** 是否来自 JS Hook 通道（C3）。C1/C2 抓到的流可靠性更高，UI 可据此区分置信度。 */
    val viaJsHook: Boolean = false,

    /** 关联的视频标题（来自页面 `<video>` 标签或 `document.title`），可为空。 */
    val title: String? = null,
) {

    /** MSE/Blob 是内存地址，外部引擎与下载器都无法使用。 */
    val supportsExternalPlayback: Boolean
        get() = kind != MediaKind.MSE_BLOB && kind != MediaKind.UNKNOWN

    val supportsDownload: Boolean
        get() = supportsExternalPlayback

    /**
     * 是否需要清单增强：只对 HLS/DASH 且尚未尝试过的条目成立。
     * 直链的体积已由 C2 响应头给出（Content-Length），无需再探测。
     */
    val needsEnrichment: Boolean
        get() = !enriched && (kind == MediaKind.HLS || kind == MediaKind.DASH)

    /** 日志安全形式：只暴露键名，避免把 Cookie 打印到 Logcat。 */
    fun headersForLog(): String = headers.keys.sorted().joinToString(prefix = "[", postfix = "]")
}
