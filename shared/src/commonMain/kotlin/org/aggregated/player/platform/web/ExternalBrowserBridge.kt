package org.aggregated.player.platform.web

/**
 * 外部浏览器桥：桌面端尚无内嵌页面时，把 URL 交给系统默认浏览器打开。
 *
 * 与 [AdBlockingBridge] 同款解耦思路：共享层不引用任何平台类型，只在「没有内嵌页面承载」
 * 的宿主上请求打开。Android 端不注册（页面由内嵌 WebView 承载），因此共享层调用 [open]
 * 在移动端是零副作用的 no-op；桌面端在启动时注册实现（见 `installDesktopExternalBrowser`）。
 */
object ExternalBrowserBridge {

    /** 平台注入的打开实现（桌面端 = `java.awt.Desktop.browse`）。未注册时 [open] 直接返回 false。 */
    @Volatile
    var onOpen: ((String) -> Unit)? = null

    /** 打开失败时的回调（参数为可读原因），由宿主用于日志或提示。 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /**
     * 请求宿主用系统浏览器打开 [url]。
     *
     * @return true 表示已交给宿主处理；false 表示宿主未注册或打开失败。
     *         调用方**不应**据此报错——移动端本就靠内嵌 WebView 展示页面。
     */
    // 平台异常五花八门（Headless/IO/URI/状态），桥接层必须全部兜住：
    // 异常一旦穿透到共享层，会把「打开站点」这个无害动作变成崩溃。
    @Suppress("TooGenericExceptionCaught")
    fun open(url: String): Boolean {
        val handler = onOpen ?: return false
        return try {
            handler(url)
            true
        } catch (e: Exception) {
            onError?.invoke(e.message ?: url)
            false
        }
    }
}
