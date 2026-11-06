package org.aggregated.player.platform.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.aggregated.player.core.Logger
import org.aggregated.player.core.createLogger
import org.aggregated.player.di.AppGraph
import org.aggregated.player.domain.model.KeepAlivePolicy
import org.aggregated.player.domain.repository.TabSessionRepository
import org.aggregated.player.domain.usecase.CompositeAdBlockEngine
import org.aggregated.player.domain.usecase.FilterListEngine
import org.aggregated.player.domain.usecase.TabEffect
import org.aggregated.player.domain.usecase.TabManager
import org.aggregated.player.platform.BrowserTabController
import org.aggregated.player.platform.BrowserTabControllerFactory
import org.aggregated.player.platform.DefaultBrowserTabPool
import org.aggregated.player.platform.ImmersiveSignals
import org.aggregated.player.domain.model.MemoryPressureLevel

/**
 * 创建 Android WebView 控制器，并保留「任意一个活着的 WebView」作为进程级定时器的操作入口。
 *
 * 为什么需要 anchor：`WebView.pauseTimers()` 是**实例方法**却作用于**整个进程**（API 就这么设计的），
 * 所以必须借某个 WebView 来调它。这里不长期持有引用，而是在需要时向池现取——
 * 池里只放存活实例，因此不会出现「对着已 destroy 的 WebView 调方法」的情况。
 */
class AndroidTabWebViewFactory(
    private val context: Context,
    private val profile: WebViewProfile,
    private val logger: Logger,
    private val stateListener: TabStateListener?,
    private val onScroll: () -> Unit,
    private val onPageTap: () -> Unit,
) : BrowserTabControllerFactory {

    /** 只创建实例，**不加载页面**（加载由池在收到 Load 时统一发起，见接口注释）。 */
    override fun create(tabId: String, entryUrl: String): BrowserTabController =
        AndroidTabWebViewController(
            context = context,
            tabId = tabId,
            profile = profile,
            logger = logger,
            listener = stateListener,
            onScroll = onScroll,
            onPageTap = onPageTap,
        )
}

/**
 * 滚动事件的节流器：把「一帧一次的滚动回调」收敛成 [ImmersiveSignals] 上的一次滚动/静默切换。
 *
 * 每来一个滚动事件就置为滚动中并重置倒计时；连续 [IDLE_MILLIS] 无新事件才判定为停止。
 * 倒计时用可取消的 [Job]，避免旧任务把新一次滚动误置回静默。回调来自 UI 线程，
 * 但 [kotlinx.coroutines.flow.StateFlow] 线程安全，故在 [Dispatchers.Default] 上等延时即可。
 */
class ScrollThrottle(
    private val signals: ImmersiveSignals,
    private val scope: CoroutineScope,
    private val idleMillis: Long = IDLE_MILLIS,
) {

    private var idleJob: Job? = null

    /** 一次页面滚动。 */
    fun notifyScroll() {
        signals.markScrolling()
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMillis)
            signals.markIdle()
        }
    }

    private companion object {

        /** 停手到此时长视为滚动结束，悬浮控件重现。取略大于惯性滚动的帧间隔，避免闪烁。 */
        const val IDLE_MILLIS = 900L
    }
}

/**
 * Android 端的浏览器运行时：把共享层的标签决策接到真实 WebView 上（设计文档 §4.2）。
 *
 * 线程前提：所有方法都必须在主线程调用（WebView 的约束）。
 * 调用方是 `rememberCoroutineScope()` 里的效果派发与 Compose 更新块，二者都在 UI 线程上，
 * 因此这里不额外 post；违例由 [AndroidMainThreadChecker] 立刻抛出，而不是拖到渲染层去复现。
 *
 * @param pool 实例池，同时也是共享层效果的执行端。
 * @param tabManager 决策中心：内存压力时「释放谁」由它算，本类只负责转达系统事件。
 */
class AndroidBrowserRuntime(
    val pool: DefaultBrowserTabPool,
    private val tabManager: TabManager,
    private val sessionRepository: TabSessionRepository,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {

    private var activeTabId: String? = null

    /**
     * 宿主就绪时绑定容器并同步可见性。
     *
     * 每次组合更新都会调用：容器可能刚被重建（Activity 重启），可见性也必须以视图层为准，
     * 不能只信共享层状态（§4.1 的 `TabEffect` 是「做什么」，可见性是「现在长什么样」）。
     */
    fun bindHost(container: android.view.ViewGroup, activeTabId: String?) {
        this.activeTabId = activeTabId
        pool.setHostContainer(container)
        pool.syncVisibility(activeTabId)
    }

    /** 当前活动标签能否后退（宿主的返回键要先走页面历史，再退出应用）。 */
    fun canGoBackOnActiveTab(): Boolean = activeTabId?.let { pool.peek(it) }?.canGoBack == true

    /** 活动标签后退一页。 */
    fun goBackOnActiveTab() {
        activeTabId?.let { pool.peek(it) }?.goBack()
    }

    fun reloadActiveTab() {
        activeTabId?.let { pool.peek(it) }?.reload()
    }

    /**
     * 系统内存压力入口。
     *
     * `TRIM_MEMORY_UI_HIDDEN` 的语义与其它几个完全不同：**不是内存不够，而是没人看了**。
     * 此时对所有实例降载并停一次进程级定时器；反过来，`MODERATE` / `BACKGROUND`
     * 在 `Unbounded` 策略下**一个都不释放**（Q2 定档：切回去必须还在）。
     *
     * 关于废弃：API 35 起 `TRIM_MEMORY_RUNNING_*` 不再下发（只给后台进程发 COMPLETE/MODERATE/BACKGROUND），
     * 所以前台进程的严重压力信号主要靠 [onLowMemory]。这里保留这些常量而不是删掉：
     * 它们在 minSdk 24 起的存量系统上仍然有效，少处理一个等级就会少一个降级时机，
     * 而应用要兼容的正是这些存量设备。
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> onAppBackground()

            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            -> degrade(MemoryPressureLevel.CRITICAL)

            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> degrade(MemoryPressureLevel.MODERATE)

            // BACKGROUND / RUNNING_LOW：前台还看得见内容，动实例只会让用户立刻感到「页面没了」
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            -> logger.info("收到内存压力信号 $level，按 Unbounded 策略不释放实例")

            else -> Unit
        }
    }

    /** `onLowMemory()` 回调入口，等价于最严重的压力。 */
    fun onLowMemory() = degrade(MemoryPressureLevel.CRITICAL)

    /** App 退到后台：逐实例降载 + 停一次进程级 JS 定时器。
     *
     * 这里**是** `pauseTimers()` 的唯一合法使用场景（设计文档 §4.2 第 2 条）：
     * 此刻没有任何标签在给用户看，冻住全部定时器不会造成体验损失，却能明显省电。
     * 顺带落一次会话：后台是「最可能将被回收」的时刻，不存就等于丢用户标签。
     */
    fun onAppBackground() {
        val anchor = liveWebView()
        tabManager.session.value.tabs.filter { it.isLive }.forEach { tab ->
            pool.peek(tab.id)?.pause()
        }
        runCatching { anchor?.pauseTimers() }
            .onFailure { logger.warn("暂停进程级定时器失败", it) }
        logger.info("App 退后台：已降载 ${tabManager.session.value.liveTabCount} 个实例")
        ioScope.launch {
            runCatching { sessionRepository.save(tabManager.snapshot()) }
                .onFailure { logger.warn("退后台时保存会话失败", it) }
        }
    }

    /** App 回前台：恢复定时器与活动标签。后台标签维持降载，等用户点进去再恢复。 */
    fun onAppForeground() {
        runCatching { liveWebView()?.resumeTimers() }
            .onFailure { logger.warn("恢复进程级定时器失败", it) }
        activeTabId?.let { pool.peek(it) }?.resume()
    }

    /** 宿主销毁：释放全部实例。保活的前提是「同一次进程生命周期」，进程要走了就不该留。 */
    fun shutdown() {
        // 先恢复定时器再销毁实例：否则可能出现「全局仍被暂停、下一个 Activity 却忘了恢复」
        runCatching { liveWebView()?.resumeTimers() }
        pool.releaseAll()
    }

    private fun degrade(level: MemoryPressureLevel) {
        val effects = tabManager.onMemoryPressure(level)
        if (effects.isEmpty()) return

        pool.execute(effects)
        logger.info("内存压力 $level：已降级 ${effects.count { it is TabEffect.Evict }} 个标签")
        // 降级不经过 UI 意图（用户什么也没做），所以会话落盘必须由运行时自己负责
        ioScope.launch {
            runCatching { sessionRepository.save(tabManager.snapshot()) }
                .onFailure { logger.warn("降级后保存会话失败", it) }
        }
    }

    /**
     * 取一个活着的 WebView 作为进程级 API 的调用入口。
     *
     * 返回 `null` 时**不需要**兜底创建实例：一个 WebView 都没有时，本来就没有定时器可暂停。
     */
    private fun liveWebView(): WebView? {
        val candidateIds = listOfNotNull(activeTabId) + tabManager.session.value.tabs.map { it.id }
        return candidateIds.firstNotNullOfOrNull { tabId -> pool.peek(tabId)?.platformView as? WebView }
    }
}

/**
 * 延迟绑定的页面状态转发器。
 *
 * 为什么需要它：WebView 工厂在 **Application 级**创建（实例池必须活得比 Activity 久），
 * 而 `AppViewModel` 要到组合期才存 在。如果工厂直接持有一个写死的监听器，
 * 宿主创建时要么拿到 null（状态永远不回写），要么反向依赖 Compose（错层）。
 * 这里用一层转发：池创建时就能装好监听器，真实目标由组合在 `DisposableEffect` 里装卸。
 */
class LazyTabStateRelay : TabStateListener {

    @Volatile
    var target: TabStateListener? = null

    override fun onTabState(
        tabId: String,
        url: String?,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        navigation: org.aggregated.player.domain.model.TabNavigationSnapshot?,
    ) {
        // 目标不存在时丢弃即可：页面状态下一次回调就会补上，不值得为它弄复杂缓存
        target?.onTabState(tabId, url, title, canGoBack, canGoForward, isLoading, navigation)
    }
}

/**
 * Android 宿主的装配结果：一个进程生命周期内只应有一个实例（由 [AndroidBrowserHosts] 保证）。
 *
 * 为什么挂在 Application 而不是 Activity：WebView 池一旦跟着 Activity 走，
 * 「配置变更 / 进程内重进」就会把用户所有后台页面打回冷标签，F2 直接失效。
 */
class AndroidBrowserHost(
    val graph: AppGraph,
    val runtime: AndroidBrowserRuntime,
    val profile: WebViewProfile,
    private val relay: LazyTabStateRelay,
) {

    /**
     * 把页面状态回写给 UI 层（本质上是接到 `AppViewModel.onPlatformTabState`）。
     *
     * @return 解绑函数，必须在 `DisposableEffect.onDispose` 里调用：
     *   不拆掉就会让组合外的 ViewModel 被 Application 级池长期持有，等于直接泄漏。
     */
    fun bindTabState(target: TabStateListener): () -> Unit {
        relay.target = target
        return { relay.target = null }
    }
}

/**
 * 进程级宿主持有者。
 *
 * 生命周期与约定：
 * - 由 `PlayerApplication.onCreate()` 之后、Compose 之前的任意时机调用 [obtain]（内部幂等）；
 * - [shutdown] 只在宿主销毁且确定不再回来时调用；
 * - 不做 DI 框架：这里只有两三个对象，显式持有比引入框架更容易看清创建顺序
 *   （平台上下文 → 池 → AppGraph → 运行时），而创建顺序正是本项目最容易出错的地方。
 */
object AndroidBrowserHosts {

    @Volatile
    private var host: AndroidBrowserHost? = null

    /**
     * 取得（或首次创建）宿主。
     *
     * @param context 传 Application 或 Activity 都可以：内部只使用 `applicationContext`，
     *   WebView 也因此用 Application Context + 主题包裹创建，避免隐式持有 Activity（§4.2 第 7 条）。
     */
    fun obtain(
        context: Context,
        profile: WebViewProfile = WebViewProfile.DEFAULT,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioBlockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): AndroidBrowserHost {
        host?.let { return it }
        val created = synchronized(this) {
            host ?: buildHost(context, profile, ioDispatcher, ioBlockingDispatcher).also { host = it }
        }
        return created
    }

    private fun buildHost(
        context: Context,
        profile: WebViewProfile,
        ioDispatcher: CoroutineDispatcher,
        ioBlockingDispatcher: CoroutineDispatcher,
    ): AndroidBrowserHost {
        val appContext = context.applicationContext ?: context
        val logger = createLogger("AndroidBrowser")
        val relay = LazyTabStateRelay()
        // 沉浸式信号先于图创建：工厂（要推滚动）与 AppGraph（要读）共用同一份，破除构造环。
        // 滚动节流用独立作用域，与 runtime 的落盘 ioScope 分开，互不影响取消语义。
        val immersive = ImmersiveSignals()
        val scrollScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        val throttle = ScrollThrottle(immersive, scrollScope)
        val factory = AndroidTabWebViewFactory(
            context = appContext,
            profile = profile,
            logger = logger,
            stateListener = relay,
            onScroll = { throttle.notifyScroll() },
            onPageTap = { immersive.notifyPageTap() },
        )
        val pool = DefaultBrowserTabPool(factory, logger, AndroidMainThreadChecker())
        val graph = AppGraph.create(policy = KeepAlivePolicy.DEFAULT, tabExecutor = pool, immersive = immersive)
        val runtime = AndroidBrowserRuntime(
            pool = pool,
            tabManager = graph.tabManager,
            sessionRepository = graph.tabSessionRepository,
            logger = logger,
            ioDispatcher = ioDispatcher,
        )
        // 注入广告拦截桥接：引擎与「标签→站点上下文」查询。放在图建好后，规避构造环
        // （AppGraph → 池 → 工厂 → 引擎）。siteContext 内部读会话内存态拿 siteId、
        // 用仓储拿 Site.adBlockEnabled；仅页面级调用，文件 IO 可接受。
        // 并联「第三方过滤列表引擎」与「用户规则引擎（手动/标记层）」：任一拦截即拦，元素隐藏合并注入。
        AdBlockingBridge.engine = CompositeAdBlockEngine(listOf(FilterListEngine(logger), graph.adRuleEngine))
        AdBlockingBridge.siteContext = { tabId ->
            val siteId = graph.tabManager.session.value.tabs.firstOrNull { it.id == tabId }?.siteId
            val enabled = siteId?.let { id ->
                runBlocking(ioBlockingDispatcher) {
                    graph.siteRepository.loadAll()
                }.firstOrNull { it.id == id }?.adBlockEnabled
            } ?: true
            siteId to enabled
        }
        // 标记模式 / 规则变更需要「广播到每个存活页面」：由运行时遍历池下发，避免 UI 层直接持有控制器。
        AdBlockingBridge.onMarkingModeChanged = { enabled -> pool.forEachActive { it.setAdMarkingMode(enabled) } }
        AdBlockingBridge.onReapplyStyles = { pool.forEachActive { it.reapplyAdBlockStyles() } }
        AdBlockingBridge.onPickParent = { pool.forEachActive { it.pickParent() } }
        AdBlockingBridge.onPickChild = { pool.forEachActive { it.pickChild() } }
        return AndroidBrowserHost(graph, runtime, profile, relay).also {
            logger.info("Android 浏览器宿主已创建：策略=${profile.userAgentMode}")
        }
    }

    fun peek(): AndroidBrowserHost? = host

    fun shutdown() {
        val current = host ?: return
        runCatching { current.runtime.shutdown() }
        host = null
    }
}

/**
 * 组合内取宿主的便捷写法。
 *
 * 内部走 [AndroidBrowserHosts.obtain]，所以不管从哪个 Composable 调用都拿到同一个进程级宿主；
 * `LocalContext` 只用于拿 `applicationContext`，不会被实例池持有。
 */
@Composable
fun rememberAndroidBrowserHost(profile: WebViewProfile = WebViewProfile.DEFAULT): AndroidBrowserHost =
    AndroidBrowserHosts.obtain(LocalContext.current, profile)
