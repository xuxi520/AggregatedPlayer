package org.aggregated.player.platform

import org.aggregated.player.domain.model.MediaKind

/**
 * 一次「页面内覆盖层播放」的输入（阶段 3-B）。
 *
 * 与旧的 `ExternalPlayer` 契约不同：这里**不启动新界面**，而是由宿主把一个 Compose
 * 覆盖层插槽（`MainScreen` 的 `enhancedPlayerOverlay`）叠在 WebView 之上；关闭即回到原页面，
 * 页面滚动位置、标签会话、后台保活状态都不受影响。
 *
 * 平台侧实现由 UI 插槽提供，共享层不依赖任何播放器库：
 * Android 用 Media3 ExoPlayer + `AndroidView(PlayerView)`，桌面端阶段 5 接 mpv。
 * 这样即便日后替换播放引擎，ViewModel 与设置页都无需改动。
 *
 * [headers] 不是可选项而是能力前提：多数站点用 Referer/Cookie 校验防盗链，
 * 缺了它播放器拿到的往往是 403（设计文档 §4.4）。**禁止把它的值写进日志**。
 */
data class EmbeddedPlaybackRequest(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),

    /** 决定平台层用 `DefaultMediaSourceFactory`（直链）还是 `HlsMediaSource.Factory`（HLS）。默认直链保持兼容。 */
    val kind: MediaKind = MediaKind.DIRECT,
)
