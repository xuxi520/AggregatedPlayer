package org.aggregated.player.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.repository.SniffProbeEngine

/**
 * 嗅探增强器：对队列里的 HLS/DASH 条目主动拉一次清单，把「分辨率 / 时长 / 估算体积」
 * 挖出来写回嗅探行，让用户不点开就能判断这是不是视频本体。
 *
 * **只做增强、绝不阻塞**：清单拉取失败、超时、防盗链 403 都静默放弃（标记已尝试），
 * 嗅探行退回 C1/C2 通道原有的显示；整个过程跑在自己的协程里，与嗅探/下载完全解耦。
 *
 * 网络纪律：一条清单最多触发两次请求（主清单 + 最高带宽子清单），队列变化不重试
 * （成败都由 [MediaSniffer.enrich] 标记），并发由单一 collect 顺序消费保证。
 */
class SniffEnricher(
    private val sniffer: MediaSniffer,
    private val probe: SniffProbeEngine,
    private val logger: Logger,
) {

    /**
     * 挂到进程级作用域上（与 DownloadCoordinator 同生命周期）。
     * 放 AppGraph 而非 ViewModel：增强是领域行为，不该跟随某个 Compose 组合存亡。
     */
    fun observe(scope: CoroutineScope) {
        scope.launch {
            sniffer.media.collect { queue -> process(queue) }
        }
    }

    private suspend fun process(queue: List<SniffedMedia>) {
        for (media in queue) {
            if (!media.needsEnrichment) continue
            inspect(media)
        }
    }

    private suspend fun inspect(media: SniffedMedia) {
        val info = runCatching { inspectManifest(media) }.getOrNull()
        sniffer.enrich(
            id = media.id,
            resolution = info?.resolution,
            durationSeconds = info?.durationSeconds,
            estimatedSizeBytes = info?.estimatedBytes,
        )
        if (info == null) {
            logger.debug("清单增强未果：${media.url.take(URL_LOG_LIMIT)}")
        }
    }

    private suspend fun inspectManifest(media: SniffedMedia): ManifestInfo? {
        val text = probe.fetchText(media.url, media.headers) ?: return null
        return when (media.kind) {
            MediaKind.HLS -> inspectHls(media, text)
            MediaKind.DASH -> ManifestInspector.parseMpd(text)
            else -> null
        }
    }

    /**
     * HLS 两级解析：主清单给出分辨率/码率/子清单地址，再拉子清单累加 `EXTINF` 得总时长，
     * 用「码率 × 时长」估出本体大小。直接嗅探到媒体清单时只有时长、无体积，如实显示。
     */
    private suspend fun inspectHls(media: SniffedMedia, masterText: String): ManifestInfo? {
        val master = ManifestInspector.parseHls(masterText, media.url)
        val variant = master.variantUrl ?: return master
        val subText = probe.fetchText(variant, media.headers) ?: return master
        val sub = ManifestInspector.parseHls(subText, variant)
        return master.copy(durationSeconds = sub.durationSeconds ?: master.durationSeconds)
    }

    private companion object {
        const val URL_LOG_LIMIT = 120
    }
}
