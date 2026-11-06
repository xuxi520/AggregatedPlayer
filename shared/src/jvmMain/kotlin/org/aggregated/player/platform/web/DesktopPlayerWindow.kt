package org.aggregated.player.platform.web

import java.awt.Component
import java.io.File
import org.aggregated.player.core.Logger
import org.aggregated.player.platform.EmbeddedPlaybackRequest
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/** 桌面端增强播放窗口：独立 CEF 实例播放 HTML5 video（直链）。 */
class DesktopPlayerWindow(
    private val request: EmbeddedPlaybackRequest,
    private val logger: Logger,
) {
    private val pageFile: File = File.createTempFile("agg-player-", ".html")
    private val browser: CefBrowser

    init {
        pageFile.writeText(buildPage(request.url))
        val client = JcefRuntime.createClient()
        client.addRequestHandler(HeaderGate())
        // OSR 组件交给 CefPanelHost 延迟挂载（与页面区同一套机制，画面在应用内）
        browser = client.createBrowser(pageFile.toURI().toString(), false, false)
    }

    /** OSR 画面组件，供 Compose `CefPanelHost` 承载。 */
    val view: Component get() = browser.uiComponent

    fun play() = execute("try{window.__aggPlayer.play()}catch(e){}")
    fun pause() = execute("try{window.__aggPlayer.pause()}catch(e){}")
    fun seekBy(seconds: Double) = execute("try{window.__aggPlayer.seek($seconds)}catch(e){}")
    fun setRate(rate: Float) = execute("try{window.__aggPlayer.rate($rate)}catch(e){}")
    fun setMuted(muted: Boolean) = execute("try{window.__aggPlayer.mute($muted)}catch(e){}")
    fun setLoop(loop: Boolean) = execute("try{window.__aggPlayer.loop($loop)}catch(e){}")
    fun close() {
        runCatching { browser.close(true) }.onFailure { logger.warn("关闭播放窗口失败", it) }
        runCatching { pageFile.delete() }
    }

    private fun execute(script: String) {
        runCatching { browser.executeJavaScript(script, browser.url, 0) }
            .onFailure { logger.warn("播放器控制失败", it) }
    }
    /** 防盗链头闸门：媒体请求发出前把嗅探到的头补回去。 */
    private inner class HeaderGate : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            b: CefBrowser?, f: CefFrame?, r: CefRequest?,
            nav: Boolean, dl: Boolean, initiator: String?, ref: BoolRef?,
        ): CefResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(b: CefBrowser?, f: CefFrame?, r: CefRequest?): Boolean {
                val req = r ?: return false
                val url = req.url ?: return false
                if (url == request.url || url.startsWith(request.url.substringBefore('?'))) {
                    request.headers.forEach { (k, v) -> runCatching { req.setHeaderByName(k, v, true) } }
                }
                return false
            }
        }
    }

    private companion object {
        fun buildPage(url: String): String {
            val safe = url.replace("\\", "\\\\").replace("'", "\\'")
            return "<!doctype html><html><head><meta charset='utf-8'>" +
                "<style>html,body{margin:0;background:#000;height:100%;overflow:hidden}" +
                "video{width:100%;height:100%;object-fit:contain;background:#000}</style>" +
                "</head><body><video id='v' controls autoplay></video><script>" +
                "var v=document.getElementById('v');v.src='$safe';" +
                "window.__aggPlayer={play:function(){v.play()},pause:function(){v.pause()}," +
                "seek:function(d){try{v.currentTime+=d}catch(e){}},rate:function(r){v.playbackRate=r}," +
                "mute:function(m){v.muted=!!m},loop:function(l){v.loop=!!l}};" +
                "</script></body></html>"
        }
    }
}
