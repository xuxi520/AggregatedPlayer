package org.aggregated.player.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aggregated.player.core.ByteFormat
import org.aggregated.player.core.currentTimeMillis
import org.aggregated.player.data.CorruptDocumentException
import org.aggregated.player.di.AppGraph
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.AppSettings
import org.aggregated.player.domain.model.ElementInfo
import org.aggregated.player.domain.model.ElementPickResult
import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.model.PlaybackMode
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.domain.model.SiteSource
import org.aggregated.player.domain.model.UriParts
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.usecase.BuiltInSiteSync
import org.aggregated.player.domain.usecase.DefaultAdRules
import org.aggregated.player.domain.usecase.ElementSelectorFactory
import org.aggregated.player.domain.usecase.NavigationAction
import org.aggregated.player.domain.usecase.PlaybackPreferenceResolver
import org.aggregated.player.domain.usecase.TabEffect
import org.aggregated.player.domain.usecase.forTab
import org.aggregated.player.platform.PushMediaRequest
import org.aggregated.player.platform.pushMediaToExternalPlayer
import org.aggregated.player.platform.EmbeddedPlaybackRequest
import org.aggregated.player.platform.web.AdBlockingBridge
import org.aggregated.player.platform.web.ExternalBrowserBridge
import kotlinx.serialization.json.Json

/**
 * 主界面状态。
 *
 * 只放「UI 需要渲染但不好从别处推导」的字段。
 * 标签会话与嗅探队列**不在这里**——它们是 [AppGraph.tabManager] / [AppGraph.mediaSniffer] 的
 * `StateFlow`，被组合函数直接观察；复制一份进 UI 状态就会出现两个真相源。
 */
data class AppUiState(
    val sites: List<Site> = emptyList(),

    /** 首次配置加载是否完成。加载期间显示占位而不是空列表，避免「站点丢了」的误判。 */
    val isBootstrapping: Boolean = true,

    /** 生效中的拦截规则条数，设置页展示用。 */
    val activeRuleCount: Int = 0,

    /** 全部拦截规则（含停用），设置页规则管理用。 */
    val rules: List<AdRule> = emptyList(),

    /** 元素标记模式是否开启（工具面板即时开关）。 */
    val markingMode: Boolean = false,

    /** 待确认的元素拾取结果；非 null 时 UI 弹出选择器确认框。 */
    val pendingElementPick: ElementPickResult? = null,

    /** 最近一次标记生成的规则 id，用于 Snackbar「撤销」。 */
    val pendingUndoRuleId: String? = null,

    /** 增强播放覆盖层是否打开。按钮需要它来防止连点触发多个播放器实例。 */
    val isEnhancedPlaying: Boolean = false,

    /** 覆盖层当前播放请求；非 null 时宿主把播放器插槽叠在 WebView 之上。 */
    val activePlayback: EmbeddedPlaybackRequest? = null,

    /** 「每次询问」模式下待用户确认的播放请求；由 Snackbar 带动作按钮呈现。 */
    val pendingPlaybackAsk: EmbeddedPlaybackRequest? = null,

    /** 一次性提示文案。显示后由 [AppIntent.DismissMessage] 清空，不用 Toast 是为了跨端一致。 */
    val message: String? = null,

    /** 应用设置（悬浮控件、手势等）。加载前为默认值；UI 只读、只通过 [AppIntent.UpdateSettings] 改。 */
    val settings: AppSettings = AppSettings(),
)

/**
 * UI 发出的意图（单向数据流：UI 只发意图，不直接改状态）。
 */
sealed interface AppIntent {

    data class OpenSite(val siteId: String) : AppIntent

    data class ActivateTab(val tabId: String) : AppIntent

    data class CloseTab(val tabId: String) : AppIntent

    /**
     * 停止一个标签（不关闭）：释放 WebView 实例、状态降为冷标签，标签与会话记录保留。
     * 由侧栏站点行的「停止」按钮触发，用于手动释放前台/后台保活的资源。
     */
    data class StopTab(val tabId: String) : AppIntent

    data class AddSite(val name: String, val url: String) : AppIntent

    /** 删除一个用户自建站点；内置站无权删除，会被直接忽略（见 [SiteSource]）。 */
    data class DeleteUserSite(val siteId: String) : AppIntent

    /** 提交新的应用设置（悬浮控件开关/位置等），由 ViewModel 落盘。 */
    data class UpdateSettings(val settings: AppSettings) : AppIntent

    /** 点级标记：把一条 URL 特征加入当前站点的拦截规则（§4.3）。 */
    data class MarkUrlPattern(val pattern: String) : AppIntent

    /** 切换某站点的广告拦截开关（逃生口，见 [Site.adBlockEnabled]）。 */
    data class ToggleSiteAdBlock(val siteId: String, val enabled: Boolean) : AppIntent

    /** 删除一条拦截规则。 */
    data class DeleteAdRule(val ruleId: String) : AppIntent

    /** 启用 / 停用一条规则（排查误拦截时最高频的操作）。 */
    data class ToggleAdRule(val ruleId: String, val enabled: Boolean) : AppIntent

    /** 手动新增一条规则（站点级全局，siteId = null）。 */
    data class AddAdRule(val pattern: String, val kind: AdRuleKind) : AppIntent

    /** 切换元素标记模式（工具面板）。 */
    data class SetMarkingMode(val enabled: Boolean) : AppIntent

    /** 网页端 JS 拾取到元素后回传（tabId + 序列化后的 [ElementInfo] JSON）。 */
    data class ElementPicked(val tabId: String, val json: String) : AppIntent

    /** 确认把某条候选选择器写入元素隐藏规则。 */
    data class ConfirmElementRule(val selector: String, val withSiblings: Boolean) : AppIntent

    /** 取消元素拾取确认。 */
    data object DismissElementPick : AppIntent

    /** 切换当前活动标签的 UA 模式（§4.4「当前站点 UA 切换」）。 */
    data class SetUserAgentMode(val mode: UserAgentMode) : AppIntent

    /** 层级微调：把当前选中元素上钻到父级（解决移动端手指粗、误选子元素）。 */
    data object PickElementParent : AppIntent

    /** 层级微调：把当前选中元素下钻到第一个子元素。 */
    data object PickElementChild : AppIntent

    /** 清除待撤销标记（Snackbar 处置完或超时后）。 */
    data object ClearUndoRule : AppIntent

    /** 打开页面内增强播放覆盖层（嗅探结果行 ▶，或 ENHANCED 偏好自动触发）。 */
    data class OpenEnhancedPlayback(val mediaId: String) : AppIntent

    /** 关闭覆盖层，回到站点原生 WebView 播放器。 */
    data object CloseEnhancedPlayback : AppIntent

    /** 用户接受「每次询问」提示。 */
    data object AcceptPlaybackAsk : AppIntent

    /** 用户忽略「每次询问」提示。 */
    data object DismissPlaybackAsk : AppIntent

    /** 站点级播放引擎覆盖；`null` 表示跟随全局默认。 */
    data class SetSitePlaybackMode(val siteId: String, val mode: PlaybackMode?) : AppIntent

    /** 下载某条嗅探到的资源（阶段 4-A 仅直链生效）。 */
    data class StartDownload(val mediaId: String) : AppIntent

    /**
     * 把嗅探到的流推给系统里其它播放器应用（阶段 4-C）。
     * 投屏/发现协议由接收方实现，本应用只负责交出地址。
     */
    data class PushToExternalPlayer(val mediaId: String) : AppIntent

    /** 取消一个下载任务。 */
    data class CancelDownload(val taskId: String) : AppIntent

    /** 删除下载任务记录（同时删除其产物：直链文件 / 缓存分片）。 */
    data class DeleteDownloadTask(val taskId: String) : AppIntent

    /** 清空离线缓存（设置页「清理缓存」）。 */
    data object ClearOfflineCache : AppIntent

    /**
     * 页面内前进/后退/刷新。
     *
     * Q1 定档后这是唯一的站内导航入口：一个站点一个标签，站内跳转都在同一标签里完成。
     */
    data class Navigate(val action: NavigationAction) : AppIntent

    /** 主动要求落盘会话（宿主 `onStop` / 内存压力降级后使用）。 */
    data object PersistSession : AppIntent

    data object DismissMessage : AppIntent
}

/**
 * 主界面控制器。
 *
 * 命名保留 ViewModel，但**不是** `androidx.lifecycle.ViewModel`：
 * 本项目为避免 KotlinMP 版 lifecycle 的包名歧义，采用「组合作用域内 remember + 外部传入协程作用域」。
 * 行为等价（配置变更时随组合重建，会话已落盘所以不丢数据）；
 * TODO(阶段2: 迁到生命周期感知的 ViewModel，让 Activity 重建时保留进行中的标签切换)
 *
 * 标签效果不再通过构造参数传入，而是统一从 [AppGraph.tabExecutor] 取：
 * 这样 Android 宿主只要把实例池当执行器注入 `AppGraph`，UI 层代码一行不用改（§6）。
 *
 * 平台侧（Android 宿主）只通过两个入口与本类交互：
 * [onPlatformTabState] 回写页面真实状态、[persistSession] 在 `onStop` 时落盘会话。
 */
class AppViewModel(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    /**
     * 已自动处理过的嗅探条目。用 `StateFlow.update` 的 CAS 而非普通 `MutableSet`：
     * 队列收集协程与 UI 的手动 ▶ 可能同时写它，commonMain 没有 `synchronized`。
     * 每条候选只自动提示/打开一次，否则用户关掉覆盖层后会被反复弹出。
     */
    private val handledAutoPlaybackIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        // 播放偏好作用于「新候选到达」这一刻；判定条件全在 PlaybackPreferenceResolver（纯逻辑），
        // 这里只负责把 Action 翻译成状态变更，避免在 ViewModel 里堆业务分支。
        scope.launch {
            graph.mediaSniffer.media.collect { queue -> observeSniffedQueue(queue) }
        }
    }

    /**
     * 载入本地配置并恢复上次会话。
     *
     * 由组合里的 `LaunchedEffect` 调用一次。整体失败只置 [AppUiState.message]，
     * 绝不抛到 UI 之外——站点列表读不出来时，用户仍然应该能用手动输入的临时标签。
     */
    fun bootstrap() {
        scope.launch {
            val state = try {
                val settings = graph.settingsRepository.load()
                // 同步全局拦截总开关到桥接层，平台控制器读它做最终放行判断
                AdBlockingBridge.globalEnabled = settings.adBlockEnabled
                // 元素拾取回传：JS 拾取到元素后转交本 ViewModel（写入规则）
                AdBlockingBridge.onElementPicked = { tabId, json -> onIntent(AppIntent.ElementPicked(tabId, json)) }
                val sites = syncBuiltInSites()
                // 首次启动写入内置默认拦截规则（幂等：仅当 ad_rules.json 不存在）
                graph.adRuleRepository.seedDefaults(DefaultAdRules.all())
                val rules = graph.adRuleRepository.loadAll()
                graph.adRuleEngine.reload(rules)
                val session = graph.tabSessionRepository.load()
                val effects = graph.tabManager.restore(session)
                dispatch(effects)
                _uiState.value.copy(
                    sites = sites,
                    isBootstrapping = false,
                    activeRuleCount = graph.adRuleEngine.activeRuleCount,
                    rules = rules,
                    settings = settings,
                    message = null,
                )
            } catch (error: CorruptDocumentException) {
                graph.logger.error("本地配置已损坏，进入只读降级", error)
                _uiState.value.copy(
                    isBootstrapping = false,
                    message = "本地配置文件无法解析，已按空配置启动（请检查数据目录下的 JSON 文件）",
                )
            } catch (error: Exception) {
                graph.logger.error("初始化失败", error)
                _uiState.value.copy(
                    isBootstrapping = false,
                    message = "初始化失败：${error.message ?: error::class.simpleName}",
                )
            }
            _uiState.value = state
        }
    }

    /**
     * 每次启动以 URL 为键把本地站点表对齐到当前版本的内置目录。
     *
     * 只增删内置站、绝不碰用户站（见 BuiltInSiteSync）；无变化时不写盘。
     * 同步失败不阻断启动——退化为「按现有站点继续」，用户仍可手动管理。
     */
    private suspend fun syncBuiltInSites(): List<Site> {
        try {
            val existing = graph.siteRepository.loadAll()
            val plan = BuiltInSiteSync.plan(existing, BuiltInSites.specs()) { currentTimeMillis() }
            if (!plan.isEmpty) {
                plan.deleteIds.forEach { graph.siteRepository.delete(it) }
                plan.upsert.forEach { graph.siteRepository.upsert(it) }
                graph.logger.info("内置站点同步：新增/更新 ${plan.upsert.size}，移除 ${plan.deleteIds.size}")
            }
        } catch (error: Exception) {
            graph.logger.warn("内置站点同步失败，按现有站点继续", error)
        }
        return graph.siteRepository.loadAll()
    }

    /** 处理一条 UI 意图。 */
    fun onIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.OpenSite -> openSite(intent.siteId)
            is AppIntent.ActivateTab -> dispatch(graph.tabManager.activate(intent.tabId))
            is AppIntent.CloseTab -> {
                // 标签关了，它的嗅探结果就成了死地址（带签名的临时 URL），留着只会误导
                graph.mediaSniffer.clearForTab(intent.tabId)
                dispatch(graph.tabManager.close(intent.tabId))
            }

            is AppIntent.StopTab -> {
                // 停止后页面要重载，旧嗅探结果同样不可信；先清掉再释放实例
                graph.mediaSniffer.clearForTab(intent.tabId)
                dispatch(graph.tabManager.stop(intent.tabId))
            }

            is AppIntent.AddSite -> addSite(intent.name, intent.url)
            is AppIntent.DeleteUserSite -> deleteUserSite(intent.siteId)
            is AppIntent.UpdateSettings -> updateSettings(intent.settings)
            is AppIntent.Navigate -> navigate(intent.action)
            is AppIntent.MarkUrlPattern -> markUrlPattern(intent.pattern)
            is AppIntent.ToggleSiteAdBlock -> updateSite(intent.siteId, "站点拦截开关") {
                it.copy(adBlockEnabled = intent.enabled)
            }
            is AppIntent.DeleteAdRule -> deleteAdRule(intent.ruleId)
            is AppIntent.ToggleAdRule -> toggleAdRule(intent.ruleId, intent.enabled)
            is AppIntent.AddAdRule -> addAdRule(intent.pattern, intent.kind)
            is AppIntent.SetMarkingMode -> setMarkingMode(intent.enabled)
            is AppIntent.ElementPicked -> elementPicked(intent.tabId, intent.json)
            is AppIntent.ConfirmElementRule -> confirmElementRule(intent.selector, intent.withSiblings)
            is AppIntent.DismissElementPick -> _uiState.value = _uiState.value.copy(pendingElementPick = null)
            is AppIntent.PickElementParent -> AdBlockingBridge.onPickParent?.invoke()
            is AppIntent.PickElementChild -> AdBlockingBridge.onPickChild?.invoke()
            is AppIntent.ClearUndoRule -> _uiState.value = _uiState.value.copy(pendingUndoRuleId = null)
            is AppIntent.SetSitePlaybackMode -> updateSite(intent.siteId, "站点播放设置") {
                it.copy(playbackMode = intent.mode)
            }
            is AppIntent.SetUserAgentMode -> {
                // 当前站点 UA 切换（§4.4）：更新活动标签 UA 模式并落盘，平台实例由 TabEffect.SetUserAgent 触发 reload
                val active = graph.tabManager.session.value.activeTab ?: return@onIntent
                dispatch(graph.tabManager.setUserAgentMode(active.id, intent.mode))
                persistSession()
                _uiState.value = _uiState.value.copy(
                    message = if (intent.mode == UserAgentMode.DESKTOP_SIMULATE) "已切换为桌面 UA（正在重载）" else "已切回移动 UA（正在重载）",
                )
            }
            // 增强播放与下载同属「嗅探结果的后续动作」，合并成一个分派分支：
            // onIntent 只做一级路由，具体状态变更收敛到 handleEnhancementIntent。
            is AppIntent.OpenEnhancedPlayback,
            AppIntent.CloseEnhancedPlayback,
            AppIntent.AcceptPlaybackAsk,
            AppIntent.DismissPlaybackAsk,
            is AppIntent.StartDownload,
            is AppIntent.PushToExternalPlayer,
            is AppIntent.CancelDownload,
            is AppIntent.DeleteDownloadTask,
            AppIntent.ClearOfflineCache -> handleEnhancementIntent(intent)
            AppIntent.PersistSession -> persistSession()
            AppIntent.DismissMessage -> _uiState.value = _uiState.value.copy(message = null)
        }
    }

    /**
     * 增强播放 / 下载意图的具体分派。
     *
     * 下载状态与进度全由 [org.aggregated.player.domain.usecase.DownloadCoordinator] 持有
     * （UI 直接读它的 StateFlow），这里只转发；播放状态则是本 ViewModel 的 UI 态。
     */
    private fun handleEnhancementIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.OpenEnhancedPlayback -> openEnhancedPlayback(intent.mediaId)
            AppIntent.CloseEnhancedPlayback -> _uiState.value = _uiState.value.copy(
                activePlayback = null,
                isEnhancedPlaying = false,
            )
            AppIntent.AcceptPlaybackAsk -> _uiState.value.pendingPlaybackAsk?.let { request ->
                _uiState.value = _uiState.value.copy(
                    pendingPlaybackAsk = null,
                    activePlayback = request,
                    isEnhancedPlaying = true,
                )
            }
            AppIntent.DismissPlaybackAsk -> _uiState.value = _uiState.value.copy(pendingPlaybackAsk = null)
            is AppIntent.StartDownload -> graph.mediaSniffer.media.value
                .firstOrNull { it.id == intent.mediaId }
                ?.let { graph.downloadCoordinator.enqueue(it) }
            is AppIntent.PushToExternalPlayer -> graph.mediaSniffer.media.value
                .firstOrNull { it.id == intent.mediaId }
                // 双保险：UI 已禁用内存流按钮，这里再挡一次（blob 推出去任何接收方都拿不到）
                ?.takeIf { it.supportsExternalPlayback }
                ?.let { media ->
                    pushMediaToExternalPlayer(PushMediaRequest(media.url, media.title, media.kind))
                }
            is AppIntent.CancelDownload -> graph.downloadCoordinator.cancel(intent.taskId)
            is AppIntent.DeleteDownloadTask -> graph.downloadCoordinator.delete(intent.taskId)
            // 清空离线缓存：用共享层的一次性提示告知释放量（跨端一致，桌面端同样能提示），不用 Toast
            AppIntent.ClearOfflineCache -> {
                val freed = graph.downloadCoordinator.clearOfflineCache()
                _uiState.value = _uiState.value.copy(message = "已释放 ${ByteFormat.readable(freed)} 空间")
            }
            else -> Unit
        }
    }

    /**
     * 平台回写页面真实状态（URL / 标题 / 导航快照）。
     *
     * 不在这里落盘：标题与 URL 变化极频繁，直写磁盘会把 IO 排满；
     * 持久化由宿主在 `onStop` / 切换标签等时机调 [persistSession]。
     */
    fun onPlatformTabState(
        tabId: String,
        url: String? = null,
        title: String? = null,
        navigation: TabNavigationSnapshot? = null,
    ) {
        graph.tabManager.updateFromPlatform(tabId, url, title, navigation)
    }

    private fun openSite(siteId: String) {
        val site = _uiState.value.sites.firstOrNull { it.id == siteId }
        if (site == null) {
            graph.logger.warn("打开站点失败，站点不存在：$siteId")
            return
        }
        // 硬护栏在 UI 层就要拦住并说明原因：到 TabManager 里只剩一条日志，用户看不出为什么点了没反应
        if (!graph.tabManager.canOpenMoreTabs() && graph.tabManager.session.value.tabs.none { it.siteId == siteId }) {
            _uiState.value = _uiState.value.copy(
                message = "标签数已达上限 ${graph.tabManager.hardTabLimit}，请先关闭一些标签",
            )
            return
        }
        dispatch(graph.tabManager.openSite(site))
        // 桌面端尚无内嵌内核（JCEF 未接入，阶段 5）：浏览动作交给系统默认浏览器。
        // Android 未注册该桥 → open 返回 false，共享层零副作用，页面照常由内嵌 WebView 承载。
        if (ExternalBrowserBridge.open(site.url)) {
            _uiState.value = _uiState.value.copy(message = "已在系统浏览器中打开：${site.name}")
        }
    }

    /**
     * 统一的效果出口：先让平台执行，再按需把会话落盘。
     *
     * 落盘放在这里而不是各方法内部，是因为「会话保存时机」有七八处调用点
     * （§4.1 的标签操作 + §4.2 的内存压力），集中一处才不会出现漏存。
     *
     * @param persist 导航类效果传 `false`：页面内每退一步就写一次磁盘没有意义
     *   （URL 会由平台回写进状态，真正落盘发生在切标签 / 退后台时）。
     */
    private fun dispatch(effects: List<TabEffect>, persist: Boolean = true) {
        if (effects.isEmpty()) return
        try {
            graph.tabExecutor.execute(effects)
        } catch (error: Exception) {
            // 契约要求执行器不得抛；真抛了也只能降级为「状态与实例不一致，下次进入重载」，
            // 不能让它击穿到组合外层
            graph.logger.error("标签效果执行失败，已保留状态等待下次纠偏", error)
        }
        if (persist) persistSession()
    }

    private fun navigate(action: NavigationAction) {
        val tabId = graph.tabManager.session.value.activeTabId
        if (tabId == null) {
            graph.logger.info("无活动标签，忽略导航动作：$action")
            return
        }
        dispatch(listOf(TabEffect.Navigate(tabId, action)), persist = false)
    }

    /** 把当前会话写入 `tabs.json`。失败只记日志：会话是便利用途，不影响本次浏览。 */
    fun persistSession() {
        scope.launch {
            try {
                graph.tabSessionRepository.save(graph.tabManager.snapshot())
            } catch (error: Exception) {
                graph.logger.warn("标签会话保存失败", error)
            }
        }
    }

    private fun addSite(name: String, rawUrl: String) {
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            _uiState.value = _uiState.value.copy(message = "地址无法识别，请填写完整 URL（例如 https://example.com）")
            return
        }
        val now = currentTimeMillis()
        val site = Site(
            id = Ids.next("site", now),
            name = name.ifBlank { UriParts.parse(url)?.host ?: url }.take(SITE_NAME_MAX_LENGTH),
            url = url,
            // 追加到列表末尾：sortIndex 允许重复，读侧会再按名称稳定排序
            sortIndex = _uiState.value.sites.size,
            createdAt = now,
        )
        scope.launch {
            try {
                graph.siteRepository.upsert(site)
                _uiState.value = _uiState.value.copy(sites = graph.siteRepository.loadAll(), message = "已添加站点 ${site.name}")
            } catch (error: Exception) {
                graph.logger.error("站点写入失败", error)
                _uiState.value = _uiState.value.copy(message = "站点保存失败：${error.message ?: "未知原因"}")
            }
        }
    }

    /**
     * 删除用户自建站点。
     *
     * 内置站无权删除，直接拒绝并提示（来源决策见 [SiteSource]）；若该站开着标签，先关标签再删记录，
     * 避免残留一个指向已删站点的活动标签。
     */
    private fun deleteUserSite(siteId: String) {
        val site = _uiState.value.sites.firstOrNull { it.id == siteId }
        if (site == null) return
        if (site.source == SiteSource.BUILTIN) {
            _uiState.value = _uiState.value.copy(message = "内置站点不可删除")
            return
        }
        scope.launch {
            try {
                graph.tabManager.session.value.tabs.firstOrNull { it.siteId == siteId }?.let { tab ->
                    graph.mediaSniffer.clearForTab(tab.id)
                    dispatch(graph.tabManager.close(tab.id))
                }
                graph.siteRepository.delete(siteId)
                _uiState.value = _uiState.value.copy(
                    sites = graph.siteRepository.loadAll(),
                    message = "已删除站点 ${site.name}",
                )
            } catch (error: Exception) {
                graph.logger.error("站点删除失败", error)
                _uiState.value = _uiState.value.copy(message = "站点删除失败：${error.message ?: "未知原因"}")
            }
        }
    }

    /**
     * 更新应用设置：先改内存状态（UI 立即响应），再异步落盘。
     *
     * 拖动等高频变更只在拖拽结束时调本方法，避免每帧写盘。
     */
    private fun updateSettings(settings: AppSettings) {
        _uiState.value = _uiState.value.copy(settings = settings)
        // 全局拦截总开关可能变了，同步到桥接层（即便本次只是改悬浮控件，顺手同步也无妨）
        AdBlockingBridge.globalEnabled = settings.adBlockEnabled
        scope.launch {
            try {
                graph.settingsRepository.save(settings)
            } catch (error: Exception) {
                graph.logger.warn("设置保存失败", error)
            }
        }
    }

    private fun markUrlPattern(pattern: String) {
        val trimmed = pattern.trim()
        if (trimmed.length < MARK_MIN_LENGTH) {
            _uiState.value = _uiState.value.copy(message = "特征太短容易误伤，至少需要 $MARK_MIN_LENGTH 个字符")
            return
        }
        val siteId = graph.tabManager.session.value.activeTab?.siteId
        scope.launch {
            try {
                val rule = graph.adRuleRepository.addMarkedRule(
                    siteId = siteId,
                    pattern = trimmed,
                    kind = AdRuleKind.URL_SUBSTRING,
                    note = "点级标记",
                )
                graph.adRuleEngine.reload(graph.adRuleRepository.loadAll())
                _uiState.value = _uiState.value.copy(
                    activeRuleCount = graph.adRuleEngine.activeRuleCount,
                    message = "已标记规则：${rule.pattern}",
                )
                // 规则改完必须重载当前页面才有可见效果（§4.3）；没有活动标签时什么都不做
                graph.tabManager.session.value.activeTab?.let { dispatch(listOf(TabEffect.Load(it))) }
            } catch (error: Exception) {
                graph.logger.error("规则写入失败", error)
                _uiState.value = _uiState.value.copy(message = "规则保存失败：${error.message ?: "未知原因"}")
            }
        }
    }

    /**
     * 重新载入规则集并刷新引擎与 UI 状态。
     *
     * 仓储不反向通知引擎（避免依赖环），所以所有写规则的地方统一在此显式 [org.aggregated.player.domain.usecase.AdRuleEngine.reload]。
     */
    private suspend fun reloadRules(): List<AdRule> {
        val rules = graph.adRuleRepository.loadAll()
        graph.adRuleEngine.reload(rules)
        _uiState.value = _uiState.value.copy(
            rules = rules,
            activeRuleCount = graph.adRuleEngine.activeRuleCount,
        )
        return rules
    }

    /**
     * 站点级设置的统一落盘路径（广告开关 / 播放偏好共用）。
     *
     * 两者都只是「改一个字段 → upsert → 重载列表」，无需重载任何引擎；
     * 合并成一处既避免重复代码，也避免 ViewModel 继续膨胀成上帝组件。
     */
    private fun updateSite(siteId: String, errorLabel: String, transform: (Site) -> Site) {
        val site = _uiState.value.sites.firstOrNull { it.id == siteId } ?: return
        scope.launch {
            try {
                graph.siteRepository.upsert(transform(site))
                _uiState.value = _uiState.value.copy(sites = graph.siteRepository.loadAll())
            } catch (error: Exception) {
                graph.logger.error("${errorLabel}保存失败", error)
                _uiState.value = _uiState.value.copy(message = "${errorLabel}保存失败：${error.message ?: "未知原因"}")
            }
        }
    }

    private fun deleteAdRule(ruleId: String) {
        scope.launch {
            try {
                graph.adRuleRepository.delete(ruleId)
                reloadRules()
                // 规则（尤其是 ELEMENT）变了，立刻重注入样式，让隐藏/取消隐藏立即生效
                AdBlockingBridge.onReapplyStyles?.invoke()
            } catch (error: Exception) {
                graph.logger.error("规则删除失败", error)
                _uiState.value = _uiState.value.copy(message = "规则删除失败：${error.message ?: "未知原因"}")
            }
        }
    }

    private fun toggleAdRule(ruleId: String, enabled: Boolean) {
        scope.launch {
            try {
                graph.adRuleRepository.setEnabled(ruleId, enabled)
                reloadRules()
                AdBlockingBridge.onReapplyStyles?.invoke()
            } catch (error: Exception) {
                graph.logger.error("规则启停失败", error)
                _uiState.value = _uiState.value.copy(message = "规则启停失败：${error.message ?: "未知原因"}")
            }
        }
    }

    private fun addAdRule(pattern: String, kind: AdRuleKind) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "规则特征不能为空")
            return
        }
        scope.launch {
            try {
                val rule = AdRule(
                    id = Ids.next("rule", currentTimeMillis()),
                    kind = kind,
                    siteId = null,
                    pattern = trimmed,
                    enabled = true,
                    note = "手动添加",
                )
                graph.adRuleRepository.upsert(rule)
                reloadRules()
                _uiState.value = _uiState.value.copy(message = "已添加规则：$trimmed")
            } catch (error: Exception) {
                graph.logger.error("规则添加失败", error)
                _uiState.value = _uiState.value.copy(message = "规则添加失败：${error.message ?: "未知原因"}")
            }
        }
    }

    /** 切换元素标记模式：更新 UI 状态并广播到所有存活页面（经 [AdBlockingBridge] 由运行时下发）。 */
    private fun setMarkingMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            markingMode = enabled,
            // 给悬浮控件一个可见反馈：🎯 点击后菜单会收起，不提示用户不知道已进/退标记模式
            message = if (enabled) "已进入元素标记模式，点击页面元素即可隐藏" else "已退出元素标记模式",
        )
        AdBlockingBridge.markingEnabled = enabled
        AdBlockingBridge.onMarkingModeChanged?.invoke(enabled)
    }

    /** 网页端 JS 拾取到元素：解析为 [ElementInfo] 并暂存，等待确认弹窗。 */
    private fun elementPicked(tabId: String, json: String) {
        val info = try {
            Json.decodeFromString<ElementInfo>(json)
        } catch (error: Exception) {
            graph.logger.error("解析元素拾取结果失败", error)
            _uiState.value = _uiState.value.copy(message = "元素拾取解析失败")
            return
        }
        val siteId = graph.tabManager.session.value.tabs.firstOrNull { it.id == tabId }?.siteId
        _uiState.value = _uiState.value.copy(pendingElementPick = ElementPickResult(tabId, siteId, info))
    }

    /** 确认标记：把选定（可能连带兄弟）的选择器写成 ELEMENT 规则并立即重注入样式。 */
    private fun confirmElementRule(selector: String, withSiblings: Boolean) {
        val pick = _uiState.value.pendingElementPick ?: return
        val finalSelector = ElementSelectorFactory.resolve(selector, withSiblings, pick.info)
        scope.launch {
            try {
                val rule = AdRule(
                    id = Ids.next("rule", currentTimeMillis()),
                    kind = AdRuleKind.ELEMENT,
                    siteId = pick.siteId,
                    pattern = finalSelector,
                    enabled = true,
                    note = "元素标记",
                )
                graph.adRuleRepository.upsert(rule)
                reloadRules()
                // 规则变了，立刻重注入元素隐藏样式（无需等下次导航），让标记即时生效
                AdBlockingBridge.onReapplyStyles?.invoke()
                _uiState.value = _uiState.value.copy(
                    pendingElementPick = null,
                    pendingUndoRuleId = rule.id,
                )
            } catch (error: Exception) {
                graph.logger.error("元素规则写入失败", error)
                _uiState.value = _uiState.value.copy(
                    pendingElementPick = null,
                    message = "元素标记失败：${error.message ?: "未知原因"}",
                )
            }
        }
    }

    /**
     * 嗅探队列变化：按「全局默认 × 当前站点覆盖」决定是否自动打开覆盖层或先征询。
     *
     * 默认模式恒为 WEBVIEW，因此绝大多数情况下这里直接返回；只有用户显式把偏好改成
     * ENHANCED / ASK 才会接管，符合「增强而非替代」。
     */
    private fun observeSniffedQueue(queue: List<SniffedMedia>) {
        val state = _uiState.value
        val activeTab = graph.tabManager.session.value.activeTab
        // 队列是全局的，但自动接管只能由「当前站点」的候选触发：
        // 否则切到 B 站后，A 站残留的临时流会让 B 站莫名弹出增强播放覆盖层。
        val currentQueue = queue.forTab(activeTab?.id)
        // 只保留仍在当前标签队列里的 id，避免长时间浏览后这个集合无限增长
        handledAutoPlaybackIds.update { handled -> handled.intersect(currentQueue.map { it.id }.toSet()) }
        // 征询提示还没处置时不要重复弹第二条
        if (state.pendingPlaybackAsk != null) return
        val siteOverride = state.sites.firstOrNull { it.id == activeTab?.siteId }?.playbackMode
        val mode = PlaybackPreferenceResolver.resolve(state.settings.defaultPlaybackMode, siteOverride)
        val candidate = currentQueue.firstOrNull {
            it.supportsExternalPlayback && it.id !in handledAutoPlaybackIds.value
        } ?: return
        when (
            PlaybackPreferenceResolver.decide(
                mode = mode,
                hasPlayableCandidate = true,
                playbackActive = state.activePlayback != null,
            )
        ) {
            PlaybackPreferenceResolver.Action.NONE -> Unit
            PlaybackPreferenceResolver.Action.AUTO_OPEN -> {
                handledAutoPlaybackIds.update { it + candidate.id }
                openEnhancedPlayback(candidate.id)
            }
            PlaybackPreferenceResolver.Action.ASK -> {
                handledAutoPlaybackIds.update { it + candidate.id }
                _uiState.value = _uiState.value.copy(
                    pendingPlaybackAsk = EmbeddedPlaybackRequest(
                        url = candidate.url,
                        title = candidate.title,
                        headers = candidate.headers,
                        kind = candidate.kind,
                    ),
                )
            }
        }
    }

    /** 手动 ▶ 或自动接管：只置状态，真正的播放器由宿主 UI 插槽渲染（共享层零播放器依赖）。 */
    private fun openEnhancedPlayback(mediaId: String) {
        val media = graph.mediaSniffer.media.value.firstOrNull { it.id == mediaId }
        if (media == null) {
            graph.logger.warn("增强播放失败，嗅探记录不存在：$mediaId")
            return
        }
        if (!media.supportsExternalPlayback) {
            _uiState.value = _uiState.value.copy(message = "该流是内存流或类型未识别，只能使用站点原生播放器")
            return
        }
        handledAutoPlaybackIds.update { it + mediaId }
        _uiState.value = _uiState.value.copy(
            activePlayback = EmbeddedPlaybackRequest(
                url = media.url,
                title = media.title,
                headers = media.headers,
                kind = media.kind,
            ),
            pendingPlaybackAsk = null,
            isEnhancedPlaying = true,
        )
    }

    /**
     * 补全用户手输地址。
     *
     * 只做「缺协议就补 `https://`」和「拒绝空白/含空格」两件事，
     * 不做可达性校验：离线时也要允许把站点存进去（§4.1 的入口地址语义）。
     */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private companion object {

        /** 站点名截断长度，避免用户粘贴整页标题把侧栏撑变形。 */
        const val SITE_NAME_MAX_LENGTH = 40

        /**
         * 点级标记的最小特征长度。
         * 短特征（如 `/ad`、`a`）会命中大量正常资源，是误拦截的主要来源，宁可直接拒绝。
         */
        const val MARK_MIN_LENGTH = 5
    }
}
