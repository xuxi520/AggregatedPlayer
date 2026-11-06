package org.aggregated.player.platform.web

import android.view.View
import android.webkit.WebChromeClient

/**
 * WebView 全屏视频的桥接（阶段 1.5 体验补全：让各站点的网页播放器能全屏）。
 *
 * [TabWebChromeClient] 在任意标签的页面请求进入全屏时被调用，但它只持有 Application 上下文、
 * 拿不到 Activity/Window——真正能把自定义视图（视频表面）铺满屏的是 Android 宿主层。这里用进程级
 * 单例把「请求」转发给当前注册的 [VideoFullscreenHost]（即 `androidApp` 的 [MainActivity]），
 * 从而**不让共享层反向依赖 app 层**。
 *
 * 若此刻没有宿主（例如页面在后台请求），直接回调 [WebChromeClient.CustomViewCallback.onCustomViewHidden]
 * 拒绝全屏，保证主路径不死。
 */
interface VideoFullscreenHost {
    /** 显示一个用于全屏的自定义视图（视频表面），`requestedOrientation` 为页面期望的屏幕方向（未指定时为 `SCREEN_ORIENTATION_UNSPECIFIED`）。 */
    fun showCustomView(view: View, requestedOrientation: Int, callback: WebChromeClient.CustomViewCallback)

    /** 收起全屏。实现方需在内部调用 [WebChromeClient.CustomViewCallback.onCustomViewHidden]。 */
    fun hideCustomView()
}

object VideoFullscreenBridge {
    @Volatile
    private var host: VideoFullscreenHost? = null

    fun register(host: VideoFullscreenHost) {
        this.host = host
    }

    fun unregister(host: VideoFullscreenHost) {
        if (this.host === host) this.host = null
    }

    fun showCustomView(view: View, requestedOrientation: Int, callback: WebChromeClient.CustomViewCallback) {
        val h = host
        if (h != null) h.showCustomView(view, requestedOrientation, callback)
        else callback.onCustomViewHidden()
    }

    fun hideCustomView() {
        host?.hideCustomView()
    }
}
