package org.aggregated.player.platform

import org.aggregated.player.domain.model.MediaKind

/**
 * 一次「推送」的输入：把视频地址交给系统里的**其它**播放器应用（阶段 4-C）。
 *
 * 产品边界（用户明确要求）：**不做任何投屏/发现协议**。DLNA、AirPlay、厂商私有协议
 * 都由接收方播放器自己实现，我们只负责把地址安全地交出去——「让专业应用做专业的事」，
 * 自身工作量最小，也不会引入超时/设备发现的复杂度。
 */
data class PushMediaRequest(
    val url: String,
    val title: String?,
    val kind: MediaKind,
)

/**
 * 平台桥：弹出系统级「选择应用」面板，由用户挑一个接收方。
 *
 * 已知局限（如实告知用户，不承诺全场景成功）：
 * 1. `EmbeddedPlaybackRequest.headers` 里的防盗链头（Referer/Cookie）**无法**随系统面板传递，
 *    强防盗链站点在外部播放器里可能 403——这类流建议用内置增强播放；
 * 2. [MediaKind.MSE_BLOB] 是页面内存地址，推出去没有任何接收方能拿到，调用方应先禁用入口。
 */
expect fun pushMediaToExternalPlayer(request: PushMediaRequest)
