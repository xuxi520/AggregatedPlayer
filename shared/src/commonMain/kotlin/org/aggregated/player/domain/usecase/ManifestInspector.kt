package org.aggregated.player.domain.usecase

/** 体积估算换算：码率（bps）× 时长（s）÷ 8 = 字节。 */
private const val BITS_PER_BYTE = 8L

/**
 * 清单解析结果：嗅探增强要从「清单本身」挖出用户判断资源真伪所需的三要素——
 * 分辨率（是不是正片画质）、时长（是预告片还是整部电影）、估算体积（是不是视频本体）。
 */
data class ManifestInfo(

    /** `1920x1080` 形式；仅 HLS 主清单 / DASH MPD 提供，媒体清单没有。 */
    val resolution: String? = null,

    /** 总时长（秒，向下取整）；来自 HLS `EXTINF` 累加或 DASH `mediaPresentationDuration`。 */
    val durationSeconds: Long? = null,

    /** 峰值码率（bps）；HLS 主清单取最高带宽变体，DASH 取最大 `bandwidth`。 */
    val bandwidthBps: Long? = null,

    /** HLS 主清单里最高带宽变体的子清单 URL（已解析为绝对地址）；媒体清单为 `null`。 */
    val variantUrl: String? = null,
) {
    /** 体积估算 = 码率 × 时长 / 8；两者齐备才可信，缺一返回 `null`（宁缺毋滥，别误导用户）。 */
    val estimatedBytes: Long?
        get() {
            val bw = bandwidthBps ?: return null
            val dur = durationSeconds ?: return null
            return if (bw > 0 && dur > 0) bw * dur / BITS_PER_BYTE else null
        }
}

/**
 * 播放清单解析器（纯逻辑，无网络无平台依赖，可单测）。
 *
 * 只提取「判断资源真伪」所需的粗粒度信息，不做完整清单建模：
 * HLS 主清单取 `EXT-X-STREAM-INF` 的最高带宽变体（用户要的通常是最高画质那条），
 * 媒体清单累加 `EXTINF` 得总时长；DASH 取根属性与最大 `bandwidth`。
 * 解析失败不是错误——返回字段为空的 [ManifestInfo]，嗅探行退回原样显示。
 */
object ManifestInspector {

    private const val STREAM_INF_TAG = "#EXT-X-STREAM-INF:"
    private const val EXTINF_TAG = "#EXTINF:"

    /** 200 Mbps：超出视为脏数据（解析错位/加密清单），丢弃以免估出天文数字误导用户。 */
    private const val MAX_BANDWIDTH = 200_000_000L

    // ISO 8601 年/月/日按平均长度折算成秒（时长估算只需量级正确，不追求历法精确）
    private const val SECONDS_PER_YEAR = 31_536_000.0
    private const val SECONDS_PER_MONTH = 2_592_000.0
    private const val SECONDS_PER_DAY = 86_400.0
    private const val SECONDS_PER_HOUR = 3_600.0
    private const val SECONDS_PER_MINUTE = 60.0

    private val ISO_DURATION = Regex(
        """^P(?!$)(?:(?<years>\d+)Y)?(?:(?<months>\d+)M)?(?:(?<days>\d+)D)?""" +
            """(?:T(?:(?<hours>\d+)H)?(?:(?<minutes>\d+)M)?(?:(?<seconds>[\d.]+)S)?)?$""",
    )

    /**
     * 解析 m3u8：同时兼容主清单（有 STREAM-INF）与媒体清单（有 EXTINF）。
     * [baseUrl] 用于把主清单里的相对子清单地址解析成绝对地址。
     */
    fun parseHls(text: String, baseUrl: String): ManifestInfo {
        var bestBandwidth = -1L
        var bestResolution: String? = null
        var variantUrl: String? = null
        var durationSeconds = 0L
        var hasExtInf = false

        val lines = text.lines()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.startsWith(STREAM_INF_TAG) -> {
                    val attrs = line.removePrefix(STREAM_INF_TAG)
                    val bandwidth = attrs.attribute("BANDWIDTH")?.toLongOrNull()
                    // 同清单内多变体按行成对出现：STREAM-INF 的下一行非注释行就是子清单 URL
                    val uri = lines.getOrNull(index + 1)?.trim()
                        ?.takeUnless { it.isEmpty() || it.startsWith("#") }
                        ?.let { resolveUrl(it, baseUrl) }
                    if (isBetterVariant(bandwidth, uri, bestBandwidth)) {
                        bestBandwidth = bandwidth!!
                        bestResolution = attrs.attribute("RESOLUTION")
                        variantUrl = uri
                    }
                }

                line.startsWith(EXTINF_TAG) -> {
                    // #EXTINF:6.006,<标题>：取冒号后第一段浮点数累加（秒级精度足够估算）
                    hasExtInf = true
                    val seconds = line.removePrefix(EXTINF_TAG)
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull()
                    if (seconds != null && seconds > 0) durationSeconds += seconds.toLong()
                }
            }
        }

        return ManifestInfo(
            resolution = bestResolution,
            // 纯媒体清单：累加值即总时长；主清单：顶层无 EXTINF，保持 null 等子清单补
            durationSeconds = if (hasExtInf) durationSeconds.takeIf { it > 0 } else null,
            bandwidthBps = bestBandwidth.takeIf { it > 0 },
            variantUrl = variantUrl,
        )
    }

    /** 解析 DASH MPD：根属性 `mediaPresentationDuration`（ISO 8601）+ 最大 `bandwidth`。 */
    fun parseMpd(text: String): ManifestInfo {
        val duration = text.durationAttribute("mediaPresentationDuration")
            ?: text.durationAttribute("duration")
        val bandwidth = Regex("""bandwidth="(\d+)""").findAll(text)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .filter { it in 1..MAX_BANDWIDTH }
            .maxOrNull()
        return ManifestInfo(
            durationSeconds = duration,
            bandwidthBps = bandwidth,
        )
    }

    /** 变体择优：带宽有效（0 < bw ≤ 上限）且比当前最佳更高、子清单地址可解析。 */
    private fun isBetterVariant(bandwidth: Long?, uri: String?, currentBest: Long): Boolean {
        if (bandwidth == null || uri == null) return false
        return bandwidth in 1..MAX_BANDWIDTH && bandwidth > currentBest
    }

    /** 取 `NAME="VALUE"` 形式的属性值（大小写不敏感，容忍引号缺失）。 */
    private fun String.attribute(name: String): String? =
        Regex("""$name\s*=\s*"?([^",\s]+)"?""", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)

    /** 取根元素上 `NAME="VALUE"` 的时长属性并按 ISO 8601 解析（`PT1H2M3.5S`）。 */
    private fun String.durationAttribute(name: String): Long? =
        Regex("""$name\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)
            ?.let { parseIsoDuration(it) }

    /** ISO 8601 时长 → 秒。只支持 `PT[nH][nM][n.S]`（视频时长足够），解析失败返回 null。 */
    private fun parseIsoDuration(value: String): Long? {
        // 注意 "M" 在 T 前是月、在 T 后是分，用命名组区分（也顺便避开魔法数字索引）
        val match = ISO_DURATION.find(value.trim()) ?: return null
        fun group(name: String): Double = match.groups[name]?.value?.toDoubleOrNull() ?: 0.0
        val seconds = group("years") * SECONDS_PER_YEAR +
            group("months") * SECONDS_PER_MONTH +
            group("days") * SECONDS_PER_DAY +
            group("hours") * SECONDS_PER_HOUR +
            group("minutes") * SECONDS_PER_MINUTE +
            group("seconds")
        return seconds.toLong().takeIf { it > 0 }
    }

    /** 相对地址 → 绝对地址：只处理清单里最常见的「同目录相对」与「绝对 URL」两种形态。 */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = baseUrl.substringBefore('?')
        val dir = base.substringBeforeLast('/', "")
        if (dir.isEmpty()) return url
        val normalized = if (url.startsWith("/")) {
            dir.substringBefore('/', "").let { root -> if (root.isEmpty()) url else "$root$url" }
        } else {
            "$dir/$url"
        }
        return normalized
    }
}
