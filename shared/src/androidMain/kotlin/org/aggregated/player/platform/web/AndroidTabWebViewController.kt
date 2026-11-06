package org.aggregated.player.platform.web

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.usecase.InterceptionDecision
import org.aggregated.player.domain.usecase.ResourceTypeGuesser
import org.aggregated.player.platform.BrowserTabController
import org.aggregated.player.platform.MainThreadChecker
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.platform.buildUserAgent
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Android WebView 实例的参数集。
 *
 * 每一项都会影响「站点原生播放器能不能跑起来」，所以按实测必要性设定，不做批量打开：
 * 参数越多，出问题时越难定位到「是哪一项改变了站点行为」。
 */
data class WebViewProfile(

    /** UA 模式，见 [UserAgentMode]：部分站点在桌面 UA 下会给出更高码率的流。 */
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,

    /** 追加到 UA 末尾的应用标识，便于站点侧排查；`null` 表示不追加。 */
    val appToken: String? = DEFAULT_APP_TOKEN,

    /**
     * 是否允许无手势自动播放。
     *
     * 设 `false` 会让大量站点的「点封面即播」变成点了没反应——这属于破坏原生体验（§2.3），
     * 因此默认放开；后台标签的串音问题由 [AndroidTabWebViewController.pause] 里显式暂停媒体解决。
     */
    val mediaPlaybackRequiresUserGesture: Boolean = false,

    /** 整页缩放。留给可访问性需求用，默认不动。 */
    val textZoom: Int = 100,

    /** 导航快照最多保留多少条，防止 `tabs.json` 被长历史撑大（§4.7）。 */
    val navigationSnapshotLimit: Int = DEFAULT_SNAPSHOT_LIMIT,
) {

    companion object {

        val DEFAULT = WebViewProfile()

        private const val DEFAULT_APP_TOKEN = "AggregatedPlayer/1.0"

        /** 30 条足够覆盖「往回数几页」的展示需求；再多只会让会话文件变大。 */
        private const val DEFAULT_SNAPSHOT_LIMIT = 30
    }
}

/**
 * 页面状态回调。
 *
 * 由宿主转交给 `AppViewModel.onPlatformTabState`。回调可能来自 WebView 的 UI 线程（正常情况），
 * 消费方不要假设它来自协程调度线程。
 */
fun interface TabStateListener {

    fun onTabState(
        tabId: String,
        url: String?,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,

        /** 导航快照：仅在加载完成时计算（`copyBackForwardList()` 有成本，不能每步都取）；加载中为 `null`。 */
        navigation: TabNavigationSnapshot?,
    )
}

/**
 * 主线程检查器的 Android 实现。
 *
 * 直接抛而不是打日志：跨线程操作 WebView 的后果通常在**渲染层**才显现（页面空白、
 * 偶发 native crash），那时日志里已经查不到是谁在错误线程上调的。
 */
class AndroidMainThreadChecker : MainThreadChecker {

    override fun assertMainThread(callSite: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "$callSite 必须在主线程调用（WebView 的线程约束），当前线程=${Thread.currentThread().name}"
        }
    }
}

/**
 * 一个标签页背后的真实 WebView（设计文档 §4.2）。
 *
 * 关于 Context：用 `ContextThemeWrapper(applicationContext, …)` 而不是 Activity——
 * 实例要跨 Activity 重建而存活（保活的前提），持有 Activity 会导致整棵视图树泄漏；
 * 而完全裸用 application context 会让 WebView 内部的选择浮层（ActionMode）缺少主题属性而崩溃，
 * 所以套一层框架主题。
 *
 * 关于 JS 弹窗：`alert`/`confirm`/`prompt` 需要窗口 token，非 Activity 上下文里创建对话框容易崩，
 * 因此这里直接放行并关闭弹窗（返回 `true` 表示已处理）。
 * TODO(阶段2: 用应用内 Compose 弹层替代，把消息内容展示出来)
 */
internal class AndroidTabWebViewController(
    private val context: Context,
    override val tabId: String,
    private val profile: WebViewProfile,
    private val logger: Logger,
    private val listener: TabStateListener?,
    private val onScroll: () -> Unit,
    private val onPageTap: () -> Unit,
) : BrowserTabController {

    private val webView: WebView = WebView(
        // 主题包裹：TextSelection ActionMode、日期选择器等内部 UI 都需要主题属性，
        // 裸用 application context 会在这些时刻崩（而包裹 Activity 又会带来泄漏）
        ContextThemeWrapper(context, android.R.style.Theme_Material_Light),
    ).apply {
        applyProfile(profile)
        webViewClient = TabWebViewClient()
        webChromeClient = TabWebChromeClient()
        // 元素隐藏的 SPA 钩子：站内路由（pushState/replaceState/popstate/hashchange）发生时，
        // 由 JS 经此桥回调 Kotlin 重新注入隐藏样式（onPageFinished 在 SPA 下不触发，见 applyElementHiding）
        addJavascriptInterface(
            AdBlockJsBridge(
                onRouteChangedAction = { this@AndroidTabWebViewController.applyElementHiding() },
                onMediaFoundAction = { url -> this@AndroidTabWebViewController.onJsMediaFound(url) },
                tabId = tabId,
            ),
            AdBlockPageScript.ADBLOCK_JS_BRIDGE,
        )
        // 页面滚动上报：悬浮控件据此在滚动时淡出（API 23+，minSdk 24 满足）。
        // 回调在 UI 线程触发，与 WebView 同线程，无需额外切换。
        setOnScrollChangeListener { _, _, _, _, _ -> onScroll() }
        // 页面按下上报：用于「点击网页自动收起悬浮面板」。返回 false 让事件继续交给 WebView 自身处理。
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onPageTap()
            false
        }
        // 不做 setLayerType(HARDWARE)：WebView 自身已走硬件加速，再强制一层次会额外占用 GPU 内存，
        // 与本项目「保活多个实例」的内存预算直接相冲
    }

    private var alive = true

    /**
     * 站点级拦截上下文缓存：按页面解析一次（[resolveSiteContext]），供 [TabWebViewClient.shouldInterceptRequest]
     * 在后台线程无锁读取，避免每请求查会话 / 仓储。
     *
     * [cachedDocumentUrl] 同理：主帧 URL 只在 [TabWebViewClient.onPageStarted]（主线程）从回调参数缓存。
     * **严禁在 shouldInterceptRequest 里调 `view.url`**——该回调在 WebView 后台线程，而 WebView 方法只允许在
     * 创建它的线程调用，跨线程访问会抛 RuntimeException 并冒泡成闪退。
     */
    @Volatile
    private var cachedSiteId: String? = null
    @Volatile
    private var cachedAdBlockEnabled = true
    @Volatile
    private var cachedDocumentUrl: String? = null

    /** 同 [cachedDocumentUrl]：UA 只在 applyProfile（主线程）写入，供后台线程嗅探读快照。 */
    @Volatile
    private var cachedUserAgent: String? = null

    /** 当前 UA 模式快照：onPageFinished 据此决定是否覆盖桌面视口（见 [injectDesktopViewport]）。 */
    @Volatile
    private var cachedUaMode: UserAgentMode = UserAgentMode.MOBILE

    override val isAlive: Boolean
        get() = alive

    override val platformView: Any
        get() = webView

    override val currentUrl: String?
        get() = webView.url

    override val title: String?
        get() = webView.title

    override val canGoBack: Boolean
        get() = webView.canGoBack()

    override val canGoForward: Boolean
        get() = webView.canGoForward()

    override val isLoading: Boolean
        // WebView 没有「是否正在加载」的直接 API，用 URL + 进度近似：
        // 进度 100 视为完成，冷实例（还没加载过）也算完成
        get() = webView.progress < PAGE_LOADED_PROGRESS && alive

    override fun loadUrl(url: String) {
        guard("loadUrl") { webView.loadUrl(url) }
    }

    override fun reload() {
        guard("reload") { webView.reload() }
    }

    override fun updateUserAgentMode(mode: UserAgentMode, reload: Boolean) {
        guard("updateUserAgentMode") {
            // 必须以系统默认 UA 为基础改写（与 applyProfile 同理），避免多次切换后 token 叠加
            val ua = buildUserAgent(
                baseUserAgent = WebSettings.getDefaultUserAgent(context),
                mode = mode,
                appToken = profile.appToken,
            )
            webView.settings.userAgentString = ua
            // 嗅探在后台线程读这份快照，必须与 settings 同步
            cachedUserAgent = ua
            cachedUaMode = mode
            if (reload) webView.reload()
        }
    }

    /**
     * 桌面伪装的视口覆盖：移动页面的 `meta viewport` 恒为 `width=device-width`，布局宽度锁死在手机上，
     * 只换 UA「桌面样式看不出来」；这里把视口改写为固定桌面宽度（1024px），配合 `useWideViewPort`
     * 缩放适配，呈现接近真桌面的版式。只改 DOM 属性——下次以移动 UA reload 时服务器返回的 meta
     * 重新生效，无需回滚逻辑。SPA 路由不重建 document，覆盖一次持续有效。
     */
    private fun injectDesktopViewport() {
        if (!alive) return
        runCatching {
            webView.evaluateJavascript(
                "(function(){var m=document.querySelector('meta[name=\"viewport\"]');" +
                    "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                    "(document.head||document.documentElement).appendChild(m);}" +
                    "m.setAttribute('content','width=1024');})()",
                null,
            )
        }.onFailure { logger.warn("注入桌面视口失败：$tabId", it) }
    }

    override fun stopLoading() {
        guard("stopLoading") { webView.stopLoading() }
    }

    override fun goBack() {
        guard("goBack") { if (webView.canGoBack()) webView.goBack() }
    }

    override fun goForward() {
        guard("goForward") { if (webView.canGoForward()) webView.goForward() }
    }

    /**
     * 降载 = 逐实例 `onPause()` + **显式暂停媒体**。
     *
     * 官方文档明确 `WebView.onPause()` 是 best-effort 且**不暂停 JavaScript**，
     * 所以只调 `onPause()` 会出现「后台标签还在出声」的直接可见 bug（§4.2 第 2 条）。
     * 这里绝不调用 `pauseTimers()`：那是进程级全局开关，会把当前标签一起冻住。
     */
    override fun pause() {
        guard("pause") {
            runCatching { webView.onPause() }
                .onFailure { logger.warn("标签 $tabId onPause 失败", it) }
            pauseMedia()
        }
    }

    override fun resume() {
        guard("resume") {
            runCatching { webView.onResume() }
                .onFailure { logger.warn("标签 $tabId onResume 失败", it) }
            // 故意不自动续播：让声音由用户的手势重新触发，避免切回标签突然出声
        }
    }

    override fun setVisibility(visible: Boolean) {
        guard("setVisibility") { webView.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
        // 遮罩只应覆盖「当前可见」的 WebView：容器被多标签共享，隐藏标签的遮罩必须撤掉，
        // 否则它会盖在可见标签之上拦截交互（见 updatePickMask 内的可见性判断）。
        updatePickMask()
    }

    override fun attachTo(container: Any) {
        guard("attachTo") {
            val parent = container as? ViewGroup ?: return@guard
            parentContainer = parent
            if (webView.parent === parent) { updatePickMask(); return@guard }
            // 先脱离旧父容器再入新容器：Android 的 addView 对「已有父视图」直接抛异常
            (webView.parent as? ViewGroup)?.removeView(webView)
            parent.addView(webView)
            updatePickMask()
        }
    }

    override fun detachFromParent() {
        guard("detachFromParent") {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
        removePickMask()
        parentContainer = null
    }

    override fun navigationSnapshot(): TabNavigationSnapshot {
        if (!alive) return TabNavigationSnapshot.EMPTY
        val list = runCatching { webView.copyBackForwardList() }.getOrNull()
        if (list == null) return TabNavigationSnapshot.EMPTY
        val urls = ArrayList<String>(list.size)
        for (index in 0 until list.size) {
            urls += list.getItemAtIndex(index)?.url.orEmpty()
        }
        return TabNavigationSnapshot(urls = urls, currentIndex = list.currentIndex)
            .trimmed(profile.navigationSnapshotLimit)
    }

    /**
     * 释放实例。
     *
     * 顺序要求：先停加载、清回调，再从父容器摘掉，最后 `destroy()`。
     * `destroy()` 之后任何对 WebView 的调用都是未定义行为，所以同时把 [isAlive] 置假。
     */
    override fun release() {
        if (!alive) return
        alive = false
        runCatching {
            webView.stopLoading()
            // 清掉自定义 client：防止 destroy 过程中还有异步回调打到这个已废弃的控制器上
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.loadUrl("about:blank")
            webView.destroy()
        }.onFailure { logger.error("销毁标签 $tabId 的 WebView 失败", it) }
        removePickMask()
        logger.info("标签 $tabId 实例已销毁")
    }

    /** 统一守卫：已释放的实例吞掉调用（不抛），其它异常记录后再抛出交给池处理。 */
    private inline fun guard(callSite: String, block: () -> Unit) {
        if (!alive) {
            logger.warn("忽略已释放实例 $tabId 的调用：$callSite")
            return
        }
        // 用 runCatching 而非 catch(Exception)：保留「全捕获 + 记录 + 重抛」语义，又避免过于宽泛的 catch 被静态检查标记
        runCatching(block).onFailure { error ->
            logger.error("WebView 调用失败：$callSite ($tabId)", error)
            throw error
        }
    }

    /** 暂停页内所有媒体元素。用 ES5 写法：站点可能运行在老旧的内核或严格模式下。 */
    private fun pauseMedia() {
        val script = "(function(){var m=document.querySelectorAll('video,audio');" +
            "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}return m.length;})();"
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { logger.warn("暂停媒体失败：$tabId", it) }
    }

    private fun WebView.applyProfile(profile: WebViewProfile) {
        val appContext = context.applicationContext ?: context
        settings.apply {
            // 站点的 H5 播放器基本都依赖 JS + localStorage，关掉等于放弃原生播放路径
            javaScriptEnabled = true
            domStorageEnabled = true
            this.mediaPlaybackRequiresUserGesture = profile.mediaPlaybackRequiresUserGesture
            // 很多站点在 https 页面里引用 http 的播放器脚本/封面图，禁止混合内容会直接白屏
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 兼容视口声明：桌面 UA 下不按页面宽度缩放会看到「只有一列」的移动版布局
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = profile.textZoom
            // 整页缩放：开启 pinch 与双击缩放（主流浏览体验），并隐藏默认缩放按钮保持界面干净。
            // 桌面 UA 下视口被改写为 width=1024（§4.4），配合 pinch 才能看清窄视口内容。
            // 注意：元素拾取遮罩仅在标记模式挂载，正常浏览不受影响，pinch 由 WebView 原生处理。
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 保持默认缓存策略：LOAD_NO_CACHE 会让每次切回标签都走网络（等于取消保活），
            // LOAD_CACHE_ELSE_NETWORK 则可能让用户看到旧版本页面
            cacheMode = WebSettings.LOAD_DEFAULT
            // UA 必须以**系统默认 UA** 为基础改写：取 webView.settings.userAgentString 会拿到已被
            // 上一轮改写污染的字符串，多次创建标签后 UA 会叠加成一堆重复 token
            val ua = buildUserAgent(
                baseUserAgent = WebSettings.getDefaultUserAgent(appContext),
                mode = profile.userAgentMode,
                appToken = profile.appToken,
            )
            userAgentString = ua
            // 嗅探回调在后台线程，不能读 WebView；UA 在这里（主线程）缓存一份快照供其无锁读取
            cachedUserAgent = ua
            cachedUaMode = profile.userAgentMode
        }
    }

    /** 页面状态回调：把 WebView 的观察点收敛到一处，方便阶段 2/3 复用。 */
    private inner class TabWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            // url 来自主线程回调参数，直接缓存；后台线程的 decide 只许读这个快照
            cachedDocumentUrl = url
            resolveSiteContext()
            emitState(isLoading = true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            emitState(isLoading = false)
            // 页面（含首屏）加载完成，注入元素隐藏样式；SPA 后续路由由 ADBLOCK_JS_BRIDGE 触发重注入
            applyElementHiding()
            // 桌面伪装需覆盖视口：站点 meta 恒为 width=device-width，只换 UA 布局仍是手机宽度（§4.4）
            if (cachedUaMode == UserAgentMode.DESKTOP_SIMULATE) injectDesktopViewport()
            // 同步元素标记模式（长按拾取开关 + 抑制网页原生长按选择），使切换后首次导航即生效
            syncMarkingMode()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            // 站内前进/后退不会触发 onPageStarted，只有这里能拿到「历史栈变了」的信号
            emitState(isLoading = view?.progress?.let { it < PAGE_LOADED_PROGRESS } ?: false)
        }

        /**
         * 拦截点：广告判定与媒体嗅探共用同一个请求上下文（URL / headers / 站点 / 页面地址），不另起通道。
         * 命中广告规则即返回 0 字节空响应；**只有放行的请求**才进入嗅探，避免把广告视频混进候选列表。
         *
         * 主文档本身不拦截，避免误拦页面导航；[cachedSiteId] / [cachedAdBlockEnabled] /
         * [cachedDocumentUrl] / [cachedUserAgent] 由主线程回调解析一次，这里只在后台线程无锁读取。
         * 站点级开关关闭时 decide 直接放行，是「增强而非替代」原则下的逃生口（§2.3）。
         */
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            // 主文档本身不拦截，避免误拦页面导航（即便它巧合命中规则）
            if (request?.isForMainFrame == true) return null
            val url = request?.url?.toString() ?: return null
            val engine = AdBlockingBridge.engine
            if (engine != null) {
                // 全局总开关 × 站点级开关：任一关闭即放行（逃生口，§2.3）
                val effectiveEnabled = AdBlockingBridge.globalEnabled && cachedAdBlockEnabled
                // Android 的 WebResourceRequest 不提供资源类型，用 URL 扩展名启发式推断（猜不出返回 UNKNOWN，
                // 由引擎「少拦优先」兜底）；documentUrl 读 onPageStarted 缓存的主帧 URL 快照，供 $third-party 与
                // $domain= 判定。此处严禁调 view.url 等任何 WebView 方法（本回调在后台线程）。
                val decision = engine.decide(
                    url = url,
                    siteId = cachedSiteId,
                    siteAdBlockEnabled = effectiveEnabled,
                    resourceType = ResourceTypeGuesser.guess(url),
                    documentUrl = cachedDocumentUrl,
                )
                if (decision is InterceptionDecision.Blocked) {
                    logger.debug("拦截广告请求：${url.take(LOG_URL_LIMIT)}（规则 ${decision.rule.id}）")
                    return blockedResponse()
                }
            }
            // 引擎未注入时降级放行，但嗅探仍可工作（它不依赖广告引擎）。
            // 候选构造是纯函数，去重/入队由共享层 MediaSniffer 的 CAS 负责，本线程无共享状态。
            AndroidRequestSniffer.observe(
                tabId = tabId,
                siteId = cachedSiteId,
                url = url,
                requestHeaders = request.requestHeaders,
                userAgent = cachedUserAgent,
                pageUrl = cachedDocumentUrl,
            )
            return null
        }
    }

    private inner class TabWebChromeClient : WebChromeClient() {

        override fun onReceivedTitle(view: WebView?, title: String?) {
            emitState(isLoading = view?.progress?.let { it < PAGE_LOADED_PROGRESS } ?: false)
        }

        // 非 Activity 上下文里创建系统对话框有崩溃风险，先吞掉保证主路径不死（见类注释）
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            logger.warn("已抑制 alert（阶段 2 改为应用内弹层）：$tabId")
            result?.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            logger.warn("已抑制 confirm（阶段 2 改为应用内弹层）：$tabId")
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            logger.warn("已抑制 prompt（阶段 2 改为应用内弹层）：$tabId")
            result?.cancel()
            return true
        }

        /** 摄像头/麦克风授权一律拒绝：本项目只做「看」，不给站点采集能力。 */
        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            logger.warn("已拒绝站点权限请求：${request?.resources?.joinToString()}")
            request?.deny()
        }

        // 网页 <video> 进入/退出全屏：本控制器只持有 Application 上下文，拿不到 Window，
        // 经共享层桥接转发给 Android 宿主（MainActivity）去铺满屏（见 VideoFullscreenBridge）。
        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null || callback == null) {
                callback?.onCustomViewHidden()
                return
            }
            VideoFullscreenBridge.showCustomView(view, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, callback)
        }

        override fun onShowCustomView(
            view: View?,
            requestedOrientation: Int,
            callback: WebChromeClient.CustomViewCallback?,
        ) {
            if (view == null || callback == null) {
                callback?.onCustomViewHidden()
                return
            }
            VideoFullscreenBridge.showCustomView(view, requestedOrientation, callback)
        }

        override fun onHideCustomView() {
            VideoFullscreenBridge.hideCustomView()
        }
    }

    /**
     * 解析当前标签的站点级拦截上下文（siteId + 站点开关），缓存供请求拦截读取。
     *
     * 只在 [TabWebViewClient.onPageStarted] 调用——每次页面导航解析一次即可，站点上下文在页面内不会变；
     * 站点级开关中途改动会在下一次导航生效（逃生口的合理代价）。
     */
    private fun resolveSiteContext() {
        AdBlockingBridge.siteContext?.invoke(tabId)?.let { (siteId, enabled) ->
            cachedSiteId = siteId
            cachedAdBlockEnabled = enabled
        }
    }

    /** 命中广告规则时返回的 0 字节空响应，使该资源「加载」为空而非报错。 */
    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))

    /**
     * 注入元素隐藏样式。
     *
     * 把当前站点适用的 ELEMENT 规则（[AdRuleEngine.elementRulesFor]）的 CSS 选择器拼成
     * `selector{display:none!important}`，经 [AdBlockPageScript.ADBLOCK_BOOTSTRAP] 定义的 `window.__aggregatedApplyAdblock`
     * 写入一个固定 id 的 `<style>` 标签（幂等，重复调用只会更新内容）。
     * 与网络拦截同一套门控（[AdBlockingBridge.globalEnabled] × [cachedAdBlockEnabled]）：
     * 任一关闭时注入空串，等于清空已注入样式，保证两类拦截行为一致。
     */
    private fun applyElementHiding() {
        if (!alive) return
        val engine = AdBlockingBridge.engine ?: return
        val effective = AdBlockingBridge.globalEnabled && cachedAdBlockEnabled
        val css = if (effective) buildElementHidingCss(engine.elementRulesFor(cachedSiteId)) else ""
        applyElementHidingCss(css, effective)
    }

    /** 把 ELEMENT 规则的选择器拼成一条 CSS 文本（每条 `selector{display:none!important}`）。 */
    private fun buildElementHidingCss(rules: List<AdRule>): String {
        if (rules.isEmpty()) return ""
        val sb = StringBuilder()
        for (rule in rules) {
            val selector = rule.pattern.trim()
            if (selector.isEmpty()) continue
            sb.append(selector).append("{display:none!important}")
        }
        return sb.toString()
    }

    /**
     * 经桥接脚本把 CSS 写入页面（空串表示清空），并同步弹窗守卫开关（见 [AdBlockPageScript]）。
     * 守卫与 CSS 同一份脚本注入：先装 bootstrap（定义 `__aggSetGuardEnabled`），再切开关，
     * 保证关闭广告拦截时动态弹窗守卫也一并停用，两类拦截行为一致。
     */
    private fun applyElementHidingCss(css: String, guardEnabled: Boolean) {
        val script =
            "${AdBlockPageScript.buildApplyAdblockScript(css)};window.__aggSetGuardEnabled&&window.__aggSetGuardEnabled($guardEnabled);"
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { logger.warn("注入元素隐藏样式失败：$tabId", it) }
    }

    /**
     * 同步元素标记模式到页面：设置 `window.__aggregatedMarkingEnabled` 标志（JS 长按侦测据此启停），
     * 并在开启时给 `body` 加 `user-select:none` 抑制网页原生文本选择，关闭时还原。
     */
    private fun syncMarkingMode() {
        if (!alive) return
        val enabled = AdBlockingBridge.markingEnabled
        val none = if (enabled) "none" else ""
        val script = "window.__aggregatedMarkingEnabled=$enabled;" +
            "if(document.body){document.body.style.webkitUserSelect='$none';" +
            "document.body.style.userSelect='$none';}" +
            // 退出标记模式时清掉遗留的红色描边与实时预览框（bootstrap 定义这些函数，未注入时容错跳过）
            (if (!enabled) "if(window.__aggregatedClearPickHighlight)window.__aggregatedClearPickHighlight();" +
                "if(window.__aggregatedHideHover)window.__aggregatedHideHover();" else "")
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { logger.warn("同步标记模式失败：$tabId", it) }
    }

    override fun setAdMarkingMode(enabled: Boolean) {
        if (!alive) return
        // 更新桥接总开关，并立即同步到当前页面（无需 reload）
        AdBlockingBridge.markingEnabled = enabled
        // 开启时先确保页面端拾取脚本（bootstrap，含点击拾取监听）已就位：它随 onPageFinished 注入，
        // 但用户可能在页面就绪前就开了标记模式，或页面被 SPA 局部刷新过而没再注入；这里幂等重注一次，
        // 保证点击拾取一定可用（bootstrap 内有 __aggregatedPickInstalled 守卫，重复注入无害）。
        if (enabled) applyElementHiding()
        syncMarkingMode()
        updatePickMask()
    }

    // === 元素标记的原生透明遮罩 ===
    // 仅靠页面 JS 的 preventDefault 拦不住跳转：不少站点用 JS 点击劫持 / target=_blank / 程序化导航，
    // 捕获阶段 preventDefault 拦不住 window.open 与代码触发的跳转。因此用一层覆盖在 WebView 之上的
    // 原生透明 View 消费全部触摸——事件根本到不了 WebView，滚动与跳转必然被拦截；点选则按坐标经
    // document.elementFromPoint 命中元素。遮罩位于容器（FrameLayout）内部，Compose 层的悬浮控件在其之上，不受影响。
    private var parentContainer: ViewGroup? = null
    private var pickMask: View? = null
    // 预览框去抖：坐标变化不足 1px 时跳过一次 evaluateJavascript，降低 MOVE 高频调用开销
    private var lastHoverX = -1f
    private var lastHoverY = -1f

    private fun updatePickMask() {
        if (!alive) { removePickMask(); return }
        val want = AdBlockingBridge.markingEnabled && webView.visibility == View.VISIBLE
        if (want) {
            if (pickMask == null) {
                val parent = parentContainer ?: return
                val mask = createPickMask()
                // 插到当前 webView 之后（之上），容器里其它（隐藏的）webView 仍在下方，不被遮挡判断干扰
                parent.addView(mask, (parent.indexOfChild(webView) + 1).coerceAtLeast(0))
                pickMask = mask
            }
        } else {
            removePickMask()
        }
    }

    private fun removePickMask() {
        val mask = pickMask ?: return
        (mask.parent as? ViewGroup)?.removeView(mask)
        pickMask = null
    }

    private fun createPickMask(): View {
        // 用 this.context 限定成员访问：Kotlin 2.x 会把裸的 `context` 误解析为 context-parameters 函数调用
        val ctx = this.context
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        return object : View(ctx) {
            private var downX = 0f
            private var downY = 0f

            // 消费全部触摸：DOWN/MOVE/CANCEL/UP 一律返回 true，从而彻底拦截 WebView 的滚动与默认导航
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        lastHoverX = -1f
                        lastHoverY = -1f
                        onPageTap() // 点网页自动收起悬浮面板（原 webView 触摸监听已被遮罩截获，这里补上）
                        showHoverAt(ev.x, ev.y) // 按下即显示预览框
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        showHoverAt(ev.x, ev.y) // 拖动时实时预览当前手指下元素
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hideHover()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = abs(ev.x - downX)
                        val dy = abs(ev.y - downY)
                        // 移动距离小于 slop 视为点击（非滚动）：按坐标确认拾取（pickEl 会描边并隐藏预览框）
                        if (dx <= slop && dy <= slop) hitTestAt(ev.x, ev.y)
                        else hideHover() // 滚动手势，无拾取，隐藏预览框
                        return true
                    }
                    else -> return true
                }
            }

            // 满足无障碍/点击分发检查的约定实现
            override fun performClick(): Boolean = true
        }.apply {
            isClickable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun hitTestAt(x: Float, y: Float) {
        if (!alive) return
        // 物理像素 → CSS 视口坐标：除以 WebView 缩放（pinch-zoom 时≠1，未缩放时为 1）
        val scale = runCatching { @Suppress("DEPRECATION") webView.scale }.getOrDefault(1f)
        val cx = x / scale
        val cy = y / scale
        runCatching {
            webView.evaluateJavascript("window.__aggregatedHitTest($cx,$cy);", null)
        }.onFailure { logger.warn("拾取命中失败：$tabId", it) }
    }

    // 实时预览框：与 hitTestAt 同样把物理坐标换算成 CSS 视口坐标，喂给页面定位红色虚线框
    private fun showHoverAt(x: Float, y: Float) {
        if (!alive) return
        if (abs(x - lastHoverX) < 1f && abs(y - lastHoverY) < 1f) return
        lastHoverX = x
        lastHoverY = y
        val scale = runCatching { @Suppress("DEPRECATION") webView.scale }.getOrDefault(1f)
        val cx = x / scale
        val cy = y / scale
        runCatching {
            webView.evaluateJavascript("window.__aggregatedShowHover($cx,$cy);", null)
        }.onFailure { logger.warn("预览高亮失败：$tabId", it) }
    }

    private fun hideHover() {
        if (!alive) return
        runCatching { webView.evaluateJavascript("window.__aggregatedHideHover();", null) }
            .onFailure { logger.warn("隐藏预览失败：$tabId", it) }
    }

    // 层级微调：把当前高亮元素上/下钻一级，重新由 JS 描边并回传（确认框据此刷新）。
    // 由 AdBlockingBridge.onPickParent/onPickChild 经运行时遍历所有实例触发，仅当前可见页面有高亮才生效。
    override fun pickParent() {
        if (!alive) return
        runCatching { webView.evaluateJavascript("window.__aggregatedPickParent();", null) }
            .onFailure { logger.warn("上钻父级失败：$tabId", it) }
    }

    override fun pickChild() {
        if (!alive) return
        runCatching { webView.evaluateJavascript("window.__aggregatedPickChild();", null) }
            .onFailure { logger.warn("下钻子级失败：$tabId", it) }
    }

    /**
     * C3：页面 JS Hook 观察到的媒体 URL。
     *
     * 站点/页面/UA 都读 [cachedSiteId] 等主线程缓存快照——JS 桥回调在 WebView 的
     * JavaBridge 线程，不能碰 WebView 方法；headers 由共享层纯函数合并，日志不打印值。
     */
    private fun onJsMediaFound(url: String) {
        AndroidRequestSniffer.observeJs(
            tabId = tabId,
            siteId = cachedSiteId,
            url = url,
            pageUrl = cachedDocumentUrl,
            userAgent = cachedUserAgent,
        )
    }

    override fun reapplyAdBlockStyles() = applyElementHiding()

    private fun emitState(isLoading: Boolean) {
        listener?.onTabState(
            tabId,
            webView.url,
            webView.title,
            webView.canGoBack(),
            webView.canGoForward(),
            isLoading,
            // 加载中取快照会得到旧的历史列表，反而会把 UI 的「可后退」状态拨错
            navigation = if (isLoading) null else navigationSnapshot(),
        )
    }

    private companion object {

        const val LOG_URL_LIMIT = 120

        /**
         * WebView 进度达到此值即视为加载完成。WebView 没有「是否正在加载」的直接 API，
         * 用 `progress == 100` 近似（冷实例还没加载过也按完成处理）。
         */
        const val PAGE_LOADED_PROGRESS = 100

    }
}

/**
 * 页面 JS → 宿主的桥接：SPA 路由重注入、元素拾取回传、C3 媒体 URL 回传。
 *
 * 必须 `private`（文件级）且方法带 [JavascriptInterface]，否则 WebView 在 Android 4.2+ 调不到。
 * 三个回调都是「通知」而非「查询」，桥本身不持有业务状态，也不做线程切换假设。
 */
private class AdBlockJsBridge(
    private val onRouteChangedAction: () -> Unit,
    private val onMediaFoundAction: (String) -> Unit,
    private val tabId: String,
) {

    @JavascriptInterface
    fun onRouteChanged() = onRouteChangedAction()

    @JavascriptInterface
    fun onElementPicked(json: String) = AdBlockingBridge.onElementPicked?.invoke(tabId, json)

    @JavascriptInterface
    fun onMediaFound(url: String) = onMediaFoundAction(url)
}
