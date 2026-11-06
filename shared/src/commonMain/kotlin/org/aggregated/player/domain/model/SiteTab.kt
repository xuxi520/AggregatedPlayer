package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 标签页的存活状态（设计文档 §4.1、§4.2）。
 */
enum class TabState {

    /** 正在展示，WebView 实例可见且全速运行。同一时刻至多一个。 */
    ACTIVE,

    /** 后台保活：实例常驻但已降载（`pause()`），切回即继续浏览，不重新加载。 */
    BACKGROUND,

    /**
     * 冷标签：实例已被 LRU 淘汰或宿主销毁，只剩 URL 与历史栈快照。
     * 点击它 = 重新加载（可恢复前进/后退列表，但不保证滚动位置）。
     */
    COLD,
}

/**
 * 一个标签页的**可持久化**状态。
 *
 * 持久化位置：`tabs.json`（[TabSession]）。
 * 刻意不含 `isLoading` / `canGoBack` 这类瞬时标志——它们属于 WebView 实例，
 * 由 `platform.BrowserTabController` 在渲染时提供；混进模型会导致冷启动时读到过期值。
 */
@Serializable
data class SiteTab(
    val id: String,

    /** 归属站点。为 `null` 时是「临时标签」，承载用户直接输入的 URL。 */
    val siteId: String? = null,

    /** 站点入口地址，用于「重载」按钮与冷标签恢复的默认目标。 */
    val entryUrl: String,

    /** 最后一次已知的当前地址；恢复时优先加载它，而不是 [entryUrl]。 */
    val url: String,

    /** 页面标题，可能来自 `document.title`，也可能退化为域名。 */
    val title: String = "",

    val state: TabState = TabState.COLD,

    /** 最近一次成为活动标签的时间（epoch millis），是 LRU 淘汰的唯一依据。 */
    val lastActiveAt: Long = 0L,

    /**
     * 导航快照（URL 序列 + 当前索引），由平台侧 `navigationSnapshot()` 回写。
     *
     * 它**不用于精确恢复历史栈**（阶段 1 明确不持久化平台 Bundle，见 §4.2 第 6 条），
     * 存在意义是让 UI 能展示「这个标签往后退还有几页」，以及供将来的近似恢复使用。
     */
    val navigation: TabNavigationSnapshot? = null,

    /**
     * 本标签的 UA 模式（见 [UserAgentMode]）。按标签持久化，切换后重载生效，
     * 实现「当前站点 UA 切换」——很多站点在桌面 UA 下给出更高码率源。默认 [UserAgentMode.MOBILE]。
     */
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
) {

    /** 是否仍有 WebView 实例。 */
    val isLive: Boolean
        get() = state != TabState.COLD

    /** 展示用名称：优先标题，退化到主机名，再退化到原始 URL。 */
    val displayTitle: String
        get() = title.ifBlank { UriParts.parse(url)?.host ?: url }
}

/**
 * 一次会话的全部标签。
 *
 * 恢复策略（设计文档 §4.2 第 5 条）：读出来的标签一律按 [TabState.COLD] 处理，
 * **只自动重新加载 [activeTabId] 对应的那一个**，其余点开才热——
 * 冷启动就恢复 N 个 WebView 会把启动耗时和内存同时打爆。
 */
@Serializable
data class TabSession(
    val activeTabId: String? = null,
    val tabs: List<SiteTab> = emptyList(),
    val updatedAt: Long = 0L,
) {

    fun find(tabId: String): SiteTab? = tabs.firstOrNull { it.id == tabId }

    val activeTab: SiteTab?
        get() = activeTabId?.let { id -> tabs.firstOrNull { it.id == id } }

    /** 存活实例数，与 `BrowserTabPool.liveCount()` 对照使用（后者是权威值）。 */
    val liveTabCount: Int
        get() = tabs.count { it.isLive }
}
