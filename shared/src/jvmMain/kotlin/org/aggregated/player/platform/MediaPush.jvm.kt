package org.aggregated.player.platform

import java.awt.Desktop
import java.net.URI
import org.aggregated.player.core.createLogger

/**
 * 桌面端「推送到外部播放器」：交给**系统关联程序**打开地址。
 *
 * Windows 上用 `rundll32 url.dll,FileProtocolHandler <url>`——媒体地址通常关联到播放器
 * 而不是浏览器，这比 `Desktop.browse`（必然走浏览器）更贴近「推给外部播放器」的语义；
 * 命令不可用时回退 [Desktop.browse]。
 *
 * 已知局限（与共享层 KDoc 一致）：防盗链头（Referer/Cookie）**无法**随系统关联程序传递，
 * 强防盗链站点在外部播放器里可能 403——这类流建议用内置增强播放。
 */
actual fun pushMediaToExternalPlayer(request: PushMediaRequest) {
    val logger = createLogger("MediaPush")
    val openedByShell = runCatching {
        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", request.url).start()
        true
    }.getOrElse { error ->
        logger.warn("rundll32 打开失败：${error.message}")
        false
    }
    if (openedByShell) return
    runCatching { Desktop.getDesktop().browse(URI(request.url)) }
        .onFailure { error -> logger.warn("系统浏览器打开失败：${error.message}") }
}
