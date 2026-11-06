package org.aggregated.player.platform.web

import java.awt.Desktop
import java.net.URI
import org.aggregated.player.core.createLogger

/**
 * 桌面端「用系统默认浏览器打开站点」的实现（阶段 5）。
 *
 * 桌面端当前尚未接入内嵌浏览器内核（JCEF），浏览动作交给系统浏览器；应用内仍保留
 * 站点列表、广告规则、嗅探记录与设置等管理能力。
 *
 * 失败一律通过 [ExternalBrowserBridge.onError] 上报（无桌面环境 / 无关联程序时
 * `Desktop.browse` 会抛异常），不把异常抛给共享层。
 */
fun installDesktopExternalBrowser() {
    val logger = createLogger("DesktopBrowser")
    ExternalBrowserBridge.onOpen = { url ->
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            error("当前环境不支持打开浏览器")
        }
        desktop.browse(URI(url))
    }
    ExternalBrowserBridge.onError = { reason -> logger.warn("打开系统浏览器失败：$reason") }
}
