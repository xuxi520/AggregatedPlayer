package org.aggregated.player.platform.web

import java.awt.Component
import java.awt.Container
import java.io.File
import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.ResourceType
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.domain.usecase.InterceptionDecision
import org.aggregated.player.domain.usecase.MediaCandidateFactory
import org.aggregated.player.platform.BrowserTabController
import org.aggregated.player.platform.buildUserAgent
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/**
 * CEF 运行时（桌面端内嵌浏览的进程级单例）。
 *
 * ⚠️ **CEF 的原生二进制（natives）不在 Maven 构件里**：`dev.datlag:jcef` 只提供 Java API。
 * 使用方需自行下载 jcef-bin 的 windows 包（含 `libcef.dll`、`locales/`、`Resources/`），
 * 并用环境变量 `JCEF_BIN_DIR` 指向该目录——本对象据此设置 `resources_dir_path` /
 * `locales_dir_path`，并把目录写进 `jna.library.path` 供 JNA 加载 `libcef.dll`。
 */
internal object JcefRuntime {

    /** 环境变量名：指向 jcef-bin 的 windows 目录。 */
    private const val BIN_DIR_ENV = "JCEF_BIN_DIR"

    private var app: CefApp? = null

    /**
     * 每个浏览器实例独立 [CefClient]：JCEF 的 `addXxxHandler` 是**设置**而非叠加，
     * 共用单例会让后创建的标签覆盖前面的拦截/UA/标题回调（多标签保活下必然踩）。
     *
     * 但 `CefApp` 是进程级单例，且 **settings 只能在首次 createClient 之前传入**——
     * 每次都调 `CefApp.getInstance(settings())` 会在创建第二个标签时抛
     * `IllegalStateException: Settings can only be passed ... before createClient is called the first time`
     * （GUI 里表现为 Error 弹窗 + 页面黑屏）。因此这里缓存 app，只初始化一次。
     */
    fun createClient(): CefClient {
        val cef = synchronized(this) {
            if (app == null && !handlerRegistered) {
                CefApp.addAppHandler(object : org.cef.handler.CefAppHandlerAdapter(arrayOf<String>()) {
                    override fun onBeforeCommandLineProcessing(
                        processType: String?,
                        commandLine: org.cef.callback.CefCommandLine?,
                    ) {
                        // Windows 下 Java 应用的主程序是 java.exe，CEF 子进程（jcef_helper）默认按主程序
                        // 目录找，起不来 → 浏览器白屏/弹 native 报错。--no-sandbox 提高子进程启动成功率。
                        commandLine?.appendSwitch("no-sandbox")
                    }
                })
                handlerRegistered = true
            }
            app ?: CefApp.getInstance(settings()).also { app = it }
        }
        return cef.createClient()
    }

    private var handlerRegistered = false

    /** CEF 版本描述（自检日志用）。 */
    fun version(): String? = runCatching { CefApp.getInstance()?.version?.toString() }.getOrNull()

    /** 读取环境变量指向的 natives 目录；未配置或找不到 `libcef.dll` 时返回 null。 */
    fun binDir(): File? {
        val raw = System.getenv(BIN_DIR_ENV)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val dir = File(raw)
        if (!dir.isDirectory) return null
        // 接受三种常见给法：直接指 natives 目录 / 指 jcef_bin 根 / 指 jcef_bin 的 bin
        val candidates = listOf(
            dir,
            File(dir, "lib/win64"),
            File(dir, "bin/lib/win64"),
            File(dir, "bin"),
        )
        return candidates.firstOrNull { File(it, "libcef.dll").isFile }
    }

    private fun settings(): CefSettings {
        val settings = CefSettings()
        // windowed 模式：不要开启离屏渲染。该全局开关会让 windowed 的 AWT Canvas 不画任何内容
        // （CEF 以为要走 OSR 但 windowed browser 没有 GLCanvas 承载）→ 白屏 + 启动弹窗。
        settings.cache_path = cacheDir().absolutePath
        binDir()?.let { dir ->
            // Resources 目录并非所有发行版都有，缺失时保持默认（同目录资源）
            File(dir, "Resources").takeIf { it.isDirectory }?.let {
                settings.resources_dir_path = it.absolutePath
            }
            File(dir, "locales").takeIf { it.isDirectory }?.let {
                settings.locales_dir_path = it.absolutePath
            }
            // ⚠️ 关键：CEF 默认按「主程序目录」找浏览器子进程 jcef_helper.exe，而 Java 应用的主程序是
            // java.exe（JDK/bin），那里没有该文件 → 子进程起不来 → 浏览器白屏/弹 native 报错。
            // 必须显式指向 natives 目录里的 jcef_helper.exe。
            File(dir, "jcef_helper.exe").takeIf { it.isFile }?.let {
                settings.browser_subprocess_path = it.absolutePath
            }
            // JNA 加载 libcef.dll 的搜索路径（与 jcef_bin 的 run.bat 一致）
            System.setProperty("jna.library.path", dir.absolutePath)
            // gluegen（JOGL 加载 CEF 的 JNI 绑定库 chrome_elf.dll）读的是 **java.library.path**，
            // 且是在加载时才读该属性——因此运行时追加同样有效
            val current = System.getProperty("java.library.path").orEmpty()
            if (!current.contains(dir.absolutePath)) {
                val merged = listOf(dir.absolutePath, current).filter { it.isNotEmpty() }.joinToString(";")
                System.setProperty("java.library.path", merged)
            }
        }
        return settings
    }

    private fun cacheDir(): File =
        File(System.getProperty("java.io.tmpdir"), "aggregated-jcef-cache").apply { mkdirs() }
}

/**
 * 桌面端标签控制器（JCEF，阶段 5）：把 CEF 实例包成共享层的 [BrowserTabController]，
 * 与 Android 的 WebView 控制器平级——共享层完全不需要知道桌面端用的是什么内核。
 *
 * 已覆盖：加载 / 导航 / 可见性 / 生命周期 / 标题与加载状态 / **页面脚本注入**
 * （元素隐藏、弹窗守卫、点击拾取，复用 commonMain 的 [AdBlockPageScript]，与移动端同一份脚本）。
 *
 * 尚未覆盖（后续迭代）：网络层请求拦截（`getResourceRequestHandler`）、per-tab UA 切换
 * （CEF 的 UA 是 `CefSettings` 全局值，只能在运行时初始化时设定）。
 */
internal class JcefTabController(
    override val tabId: String,
    private val logger: Logger,
) : BrowserTabController {

    /**
     * CEF 实例：**OSR 模式**（`isOffscreen=true`），`uiComponent` 是 JOGL `GLCanvas`。
     *
     * windowed 模式在本机不可用：CEF 在 createBrowser 时就把原生窗口建成顶层 HWND（Canvas 当时未
     * displayable），之后无法重父化 → 浏览器起不来、整屏白 + native 弹窗（实测 url 始终 about:blank，
     * `createImmediately()` 也无法重父化）。
     * OSR 渲染进单个 GLCanvas，由 `CefPanelHost` 在 host 已 displayable 后单次挂载、并在 dispose 时
     * removeAll 以保证 removeNotify 触发，避开 JOGL 的 `GLException` 与 `WindowClosingListener already set`。
     */
    private val browser: CefBrowser = JcefRuntime.createClient().createBrowser(ABOUT_BLANK, true, false)

    /** 浏览器画面的 AWT Canvas，交给 `CefPanelHost` 嵌进应用窗口。 */
    private val view: Component get() = browser.uiComponent

    @Volatile
    private var alive = true

    @Volatile
    private var loading = false

    @Volatile
    private var currentTitle: String? = null

    /** 本标签的 UA 模式（CEF 全局 UA 不可按标签改，但可逐请求覆盖 User-Agent 头）。 */
    @Volatile
    private var uaMode: UserAgentMode = UserAgentMode.MOBILE

    /** JCEF 未暴露可序列化的历史栈，这里自维护访问序列（与 Android 同款取舍：只用于展示）。 */
    private val visited = ArrayDeque<String>()

    init {
        browser.client.addLoadHandler(LoadHandler())
        browser.client.addDisplayHandler(DisplayHandler())
        browser.client.addRequestHandler(RequestHandler())
    }

    override val isAlive: Boolean get() = alive
    override val currentUrl: String? get() = if (alive) browser.url else null
    override val title: String? get() = if (alive) currentTitle else null
    override val canGoBack: Boolean get() = alive && browser.canGoBack()
    override val canGoForward: Boolean get() = alive && browser.canGoForward()
    override val isLoading: Boolean get() = loading
    override val platformView: Any get() = view

    override fun loadUrl(url: String) {
        if (!alive) return
        browser.loadURL(url)
        visited.addLast(url)
    }

    override fun reload() {
        if (alive) browser.reload()
    }

    override fun stopLoading() {
        if (alive) browser.stopLoad()
    }

    override fun goBack() {
        if (alive) browser.goBack()
    }

    override fun goForward() {
        if (alive) browser.goForward()
    }

    /** 后台标签只暂停页面媒体；离屏模式没有可隐藏的窗口，动可见性只会把页面整片抹白。 */
    override fun pause() {
        if (alive) execute(PAUSE_MEDIA_SCRIPT)
    }

    override fun resume() = Unit

    override fun setVisibility(visible: Boolean) = Unit

    override fun attachTo(container: Any) {
        // 原生窗口模式下页面不挂在宿主容器里（CEF 自己管窗口），此处无需搬视图
    }

    override fun detachFromParent() {
        // 同上：视图不由宿主容器承载
    }

    override fun navigationSnapshot(): TabNavigationSnapshot {
        val urls = visited.toList()
        return TabNavigationSnapshot(urls = urls, currentIndex = urls.lastIndex)
    }

    override fun setAdMarkingMode(enabled: Boolean) {
        if (!alive) return
        execute("${AdBlockPageScript.ADBLOCK_BOOTSTRAP};window.__aggregatedMarkingEnabled=$enabled;")
    }

    override fun reapplyAdBlockStyles() {
        if (!alive) return
        val engine = AdBlockingBridge.engine ?: return
        val context = AdBlockingBridge.siteContext?.invoke(tabId)
        val effective = AdBlockingBridge.globalEnabled && (context?.second ?: true)
        val css = if (effective) ElementHidingCss.build(engine.elementRulesFor(context?.first)) else ""
        execute(AdBlockPageScript.buildApplyAdblockScript(css))
    }

    /**
     * per-tab UA 切换（§4.4）：CEF 的 `CefSettings.user_agent` 是全局值，但**每个请求的
     * `User-Agent` 头可以在资源闸门里改**（见 [ResourceGate]），JS 层 `navigator.userAgent`
     * 由 [applyUaAndViewport] 注入覆盖——因此无需重建实例即可按标签切换，与移动端对等。
     */
    override fun updateUserAgentMode(mode: UserAgentMode, reload: Boolean) {
        if (!alive) return
        uaMode = mode
        if (reload) browser.reload()
    }

    override fun pickParent() {
        execute("try{window.__aggregatedPickParent&&window.__aggregatedPickParent();}catch(e){}")
    }

    override fun pickChild() {
        execute("try{window.__aggregatedPickChild&&window.__aggregatedPickChild();}catch(e){}")
    }

    override fun release() {
        if (!alive) return
        alive = false
        runCatching { browser.close(true) }
            .onFailure { logger.warn("关闭浏览器实例失败：$tabId", it) }
    }

    /**
     * 让页面里的 JS 也看到当前标签的 UA，并在桌面模式下覆盖视口
     * （与 Android 的 `injectDesktopViewport` 同款：站点 meta 恒为 `width=device-width`，
     * 不改写就仍是手机单列版式）。切回移动时 reload 会重新解析站点原 meta，无需回滚。
     */
    private fun applyUaAndViewport() {
        val ua = buildUserAgent(DEFAULT_BASE_UA, uaMode, APP_TOKEN).replace("'", "\\'")
        val viewport = if (uaMode == UserAgentMode.DESKTOP_SIMULATE) {
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=1024');"
        } else {
            ""
        }
        execute(
            "(function(){try{Object.defineProperty(navigator,'userAgent'," +
                "{get:function(){return '$ua';},configurable:true});}catch(e){}" +
                "try{$viewport}catch(e){}})()",
        )
    }

    private fun execute(script: String) {
        runCatching { browser.executeJavaScript(script, browser.url, 0) }
            .onFailure { logger.warn("执行页面脚本失败：$tabId", it) }
    }

    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
            loading = isLoading
            // 加载完成即注入隐藏样式与拾取引导脚本（与 Android 的 onPageFinished 同款时机）
            if (!isLoading) {
                reapplyAdBlockStyles()
                applyUaAndViewport()
            }
        }
    }

    private inner class DisplayHandler : CefDisplayHandlerAdapter() {
        override fun onTitleChange(browser: CefBrowser?, title: String?) {
            currentTitle = title
        }
    }

    /**
     * 请求处理器：把 CEF 的请求交给资源闸门（datlag 版 JCEF 的资源级拦截入口）。
     *
     * 命中拦截规则 → 闸门返回 true 掐断该请求；放行 → 顺带把媒体特征请求交给共享层嗅探队列，
     * 与 Android 的 `shouldInterceptRequest` 完全同款语义（同一引擎、同一门控、同一嗅探次序）。
     */
    private inner class RequestHandler : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler = ResourceGate()
    }

    /** 资源闸门：真正做判定与嗅探上报的地方。 */
    private inner class ResourceGate : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val req = request ?: return false
            val url = req.url ?: return false
            // per-tab UA：覆盖请求头（服务端据此返回对应版本的页面与码率）。
            // 只读请求（如已发出的重定向）改不了，失败静默即可，不影响其它拦截逻辑。
            runCatching { req.setHeaderByName("User-Agent", buildUserAgent(DEFAULT_BASE_UA, uaMode, APP_TOKEN), true) }
            val context = AdBlockingBridge.siteContext?.invoke(tabId)
            val decision = AdBlockingBridge.engine?.decide(
                url = url,
                siteId = context?.first,
                siteAdBlockEnabled = context?.second ?: true,
                resourceType = mapResourceType(req.resourceType),
                documentUrl = currentUrl,
            ) ?: InterceptionDecision.Pass
            if (decision is InterceptionDecision.Blocked) return true
            reportMediaCandidate(req, url, context?.first)
            return false
        }
    }

    /** CEF 的资源类型 → 共享层 [ResourceType]（按枚举名映射，避免两套枚举顺序耦合）。 */
    private fun mapResourceType(type: CefRequest.ResourceType?): ResourceType = when (type?.name?.uppercase()) {
        "MAIN_FRAME" -> ResourceType.DOCUMENT
        "SUB_FRAME" -> ResourceType.SUBDOCUMENT
        "STYLESHEET" -> ResourceType.STYLESHEET
        "SCRIPT" -> ResourceType.SCRIPT
        "IMAGE" -> ResourceType.IMAGE
        "FONT" -> ResourceType.FONT
        "XHR" -> ResourceType.XMLHTTPREQUEST
        "MEDIA" -> ResourceType.MEDIA
        else -> ResourceType.OTHER
    }

    /** 媒体特征请求 → 交给共享层嗅探队列（复用 commonMain 的判定与请求头合并规则）。 */
    private fun reportMediaCandidate(request: CefRequest, url: String, siteId: String?) {
        val headers = HashMap<String, String>()
        request.getHeaderMap(headers)
        val candidate = MediaCandidateFactory.sniff(
            tabId = tabId,
            siteId = siteId,
            url = url,
            requestHeaders = headers,
            cookieHeader = headers["Cookie"],
            userAgent = headers["User-Agent"],
            pageUrl = currentUrl,
        ) ?: return
        MediaCaptureBridge.onCandidate?.invoke(candidate)
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
        // 桌面端 UA 基准（与移动端同理：在系统默认 Chrome UA 上去掉/保留 Mobile 词）
        const val DEFAULT_BASE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
        const val APP_TOKEN = "AggregatedPlayer/1.0"
        const val PAUSE_MEDIA_SCRIPT =
            "try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});}catch(e){}"
    }
}
