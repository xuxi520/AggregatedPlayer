package org.aggregated.player.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.model.KeepAlivePolicy
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.model.TabState
import org.aggregated.player.domain.model.MemoryPressureLevel
import org.aggregated.player.domain.model.UserAgentMode

/**
 * 在既有实例内发起的导航动作。
 *
 * 单独成一个效果而不是新开 `goBack()` 等方法：共享层只说「该后退」，
 * 而“能不能后退、后退后历史栈怎么变”属于平台事实，由实例池读当前实例回答，
 * 失败也不会让共享状态说谎。
 */
enum class NavigationAction {
    BACK,
    FORWARD,
    RELOAD,

    /** 停在当前进度（站点无限加载时有用）。 */
    STOP,
}

/**
 * 共享层下发给平台层的动作。
 *
 * 这是本模块的关键设计：**状态机不直接操作 WebView**，只产出「该做什么」的指令列表，
 * 由平台宿主（阶段 1 的 Android `BrowserTabPool` 实现）执行。收益有三：
 * 1. 淘汰/切换策略可被纯逻辑单元测试覆盖，不需要真机；
 * 2. Android 与 Windows 复用同一套决策；
 * 3. 平台实现异步失败时（如实例创建失败），可以回调 [TabManager.markCold] 纠偏，而不会出现
 *    「状态说活着、实例早没了」的双向不一致。
 */
sealed interface TabEffect {

    /** 该标签需要（重新）加载 [SiteTab.url]。冷标签被激活、新建标签时产生。 */
    data class Load(val tab: SiteTab) : TabEffect

    /** 把该标签的实例切到前台显示并恢复运行。 */
    data class Resume(val tabId: String) : TabEffect

    /** 暂停该标签（逐实例降载，禁止用进程级全局定时器暂停实现，见 §4.2）。 */
    data class Pause(val tabId: String) : TabEffect

    /** 释放该标签的实例并把状态降为 [TabState.COLD]；释放前应导出历史栈快照。 */
    data class Evict(val tab: SiteTab) : TabEffect

    /** 标签被关闭，实例与状态一并清理。 */
    data class Discard(val tabId: String) : TabEffect

    /**
     * 页面内导航（Q1 定档后唯一的站内前进后退入口）。
     *
     * 与 [Load] 的区别：导航发生在已有实例上，不重建也不重新创建，
     * 因此不会丢页面内部状态。
     */
    data class Navigate(val tabId: String, val action: NavigationAction) : TabEffect

    /**
     * 运行时切换指定标签的 UA 模式（见 [UserAgentMode]）。
     *
     * 与 [Load] 的区别：不重建实例、不重新加载整页资源之外的额外动作，只改 UA 后由平台 `reload`。
     * 平台侧 `BrowserTabController.updateUserAgentMode` 默认带 `reload`；冷标签被重建时，
     * [Load] 路径会以 `reload = false` 先就位 UA 再由 `loadUrl` 加载（§4.4）。
     */
    data class SetUserAgent(val tabId: String, val mode: UserAgentMode) : TabEffect
}

/**
 * 标签页状态机 + 保活策略（设计文档 §4.1、§4.2）。
 *
 * 单一数据源是 [session]；平台层的 WebView 实例只是它的执行结果。
 * 「同一时刻至多一个 [TabState.ACTIVE]」是不变量，所有会改变状态的方法都负责维持它。
 *
 * @param policy 保活策略。默认 [KeepAlivePolicy.Unbounded]（Q2 定档：切回必须在），
 *   只在低配机或无挂起能力的平台（JCEF）上才用 [KeepAlivePolicy.LimitedTo]。
 * @param pressureLiveTabs 收到 [MemoryPressureLevel.CRITICAL] 时收缩到的存活数。
 *   它存在的唯一理由是「进程快被系统杀了」：此时宁可让用户重新加载一次，也不能让应用挂掉。
 * @param hardTabLimit 标签总数硬护栏（不是保活上限）：防止对象图与 `tabs.json` 无限膨胀（§9.1 R4）。
 * @param softWarnTabCount 超过则由 UI 提示内存风险，只提示不淘汰。
 */
class TabManager(
    private val logger: Logger,
    private val clock: () -> Long,
    private val policy: KeepAlivePolicy = KeepAlivePolicy.DEFAULT,
    private val pressureLiveTabs: Int = PRESSURE_MAX_LIVE_TABS,
    val hardTabLimit: Int = HARD_TAB_LIMIT,
    val softWarnTabCount: Int = SOFT_WARN_TAB_COUNT,
) {

    private val _session = MutableStateFlow(TabSession())
    val session: StateFlow<TabSession> = _session.asStateFlow()

    /** 供宿主保存会话时使用（切换标签、onPause、关闭标签等时机）。 */
    fun snapshot(): TabSession = session.value

    /** 是否还能再开标签（硬护栏，与保活策略无关）。 */
    fun canOpenMoreTabs(): Boolean = session.value.tabs.size < hardTabLimit

    /** 标签数是否已进入需要向用户提示内存风险的范围。 */
    fun shouldWarnAboutMemory(): Boolean = session.value.tabs.size > softWarnTabCount

    /**
     * 冷启动恢复。
     *
     * 落盘的 [TabState.ACTIVE] / [TabState.BACKGROUND] 一律降级为 [TabState.COLD]：
     * 进程刚起来时**不存在**任何实例，保留热状态会让 UI 显示成「切回去就还在」而实际是白屏。
     *
     * 恢复后**不自动加载任何标签**：[TabSession.activeTabId] 一并清空，
     * 冷启动第一屏是空状态页，而不是上次退出前的页面——上次会话只留下标签记录，
     * 用户点站点时再重载（[SiteTab.url] 保留着「上次看到的页面」，手动重开仍能回到原位置）。
     *
     * @return 恒为空列表（不再产生首次加载）；保留返回值以兼容既有调用链
     */
    fun restore(saved: TabSession): List<TabEffect> {
        val normalized = saved.copy(
            activeTabId = null,
            tabs = saved.tabs.map { it.copy(state = TabState.COLD) },
        )
        _session.value = normalized
        logger.info("恢复会话：${normalized.tabs.size} 个冷标签，不自动加载，等待用户选择站点")
        return emptyList()
    }

    /**
     * 点击站点：已有标签则切换（不重新加载），否则新建。
     *
     * @param openInNewTab 为 `true` 时总是新建标签。**Q1 定档后 UI 不传 `true`**（站点↔标签 1:1），
     *   参数保留是为了将来开放多开时只需补入口、不动状态机（§4.1）。
     */
    fun openSite(site: Site, openInNewTab: Boolean = false): List<TabEffect> {
        // 先查已有标签再查硬上限：已达上限时用户仍必须能切回已打开的站点，
        // 否则「不能开新的」会顺手把「看旧的」也干掉，那是比上限本身更糟的行为
        val existing = if (openInNewTab) null else session.value.tabs.firstOrNull { it.siteId == site.id }
        if (existing != null) return activate(existing.id)

        if (!canOpenMoreTabs()) {
            logger.warn("已达标签硬上限 $hardTabLimit，拒绝为站点 ${site.name} 新建标签")
            // 拒绝新建时不能静默：把当前活动标签重新顶上去，UI 至少能给出可见反馈
            val activeId = session.value.activeTabId
            return activeId?.let { listOf(TabEffect.Resume(it)) } ?: emptyList()
        }

        val now = clock()
        val created = SiteTab(
            id = Ids.next("tab", now),
            siteId = site.id,
            entryUrl = site.url,
            url = site.url,
            title = site.name,
            state = TabState.ACTIVE,
            lastActiveAt = now,
        )
        val effects = mutableListOf<TabEffect>()
        // 前一个活动标签降后台
        session.value.activeTabId?.let { previous ->
            if (previous != created.id) effects += TabEffect.Pause(previous)
        }
        effects += TabEffect.Load(created)

        // 先写会话、后算淘汰：新实例必须计入预算，否则上限会长期多出一个
        _session.update { current ->
            val demoted = current.tabs.map {
                if (it.id != created.id && it.state == TabState.ACTIVE) it.copy(state = TabState.BACKGROUND) else it
            }
            current.copy(activeTabId = created.id, tabs = demoted + created)
        }
        logger.info("为站点 ${site.name} 新建标签 ${created.id}")
        effects += enforcePolicy()
        return effects
    }

    /**
     * 切换到已存在的标签。
     *
     * 热标签只产生 [TabEffect.Resume]（继续浏览，不加载）；
     * 冷标签产生 [TabEffect.Load]，由平台按 [SiteTab.url] 与历史栈快照重建实例。
     */
    fun activate(tabId: String): List<TabEffect> {
        val target = session.value.find(tabId) ?: return emptyList()
        val now = clock()
        val wasLive = target.isLive
        val effects = mutableListOf<TabEffect>()

        session.value.tabs.forEach { tab ->
            if (tab.id != tabId && tab.state == TabState.ACTIVE && tab.isLive) {
                effects += TabEffect.Pause(tab.id)
            }
        }
        effects += if (wasLive) {
            listOf(TabEffect.Resume(tabId))
        } else {
            listOf(TabEffect.Load(target.copy(state = TabState.ACTIVE, lastActiveAt = now)))
        }

        _session.update { current ->
            val demoted = current.tabs.map {
                if (it.id != tabId && it.state == TabState.ACTIVE) it.copy(state = TabState.BACKGROUND) else it
            }
            val promoted = demoted.map {
                if (it.id == tabId) it.copy(state = TabState.ACTIVE, lastActiveAt = now) else it
            }
            current.copy(activeTabId = tabId, tabs = promoted)
        }
        // 只有冷标签被唤醒才会新增实例，热标签切换不需要淘汰
        if (!wasLive) effects += enforcePolicy()
        return effects
    }

    /**
     * 关闭标签。
     *
     * 关掉的是活动标签时，自动切回最近使用的**存活**标签（只 Resume，不重启冷标签）；
     * 没有存活标签可切时回到「无活动标签」态，UI 展示空状态页。
     *
     * 实现上先算后写（而不是在 CAS 重试块里 `effects +=`）：重试会把副作用叠加成重复指令，
     * 平台侧就会收到两次 Discard / Resume。
     */
    fun close(tabId: String): List<TabEffect> {
        val current = session.value
        val target = current.find(tabId) ?: return emptyList()
        val remaining = current.tabs.filterNot { it.id == tabId }
        val effects = mutableListOf<TabEffect>(TabEffect.Discard(tabId))

        val closingActive = current.activeTabId == tabId
        // 只自动切到「存活」的标签：冷标签被 Load 意味着整页重启，
        // 用户关掉当前页时看到另一个页面凭空重载是反直觉的；没有存活标签就回到空态页，
        // 冷标签留在列表里，是否重载由用户决定
        val successor = if (closingActive) {
            remaining.filter { it.isLive }.maxByOrNull { it.lastActiveAt }
        } else {
            null
        }

        val nextActiveId = if (closingActive) successor?.id else current.activeTabId
        val updatedTabs = remaining.map { tab ->
            when {
                tab.id == nextActiveId && tab.state != TabState.ACTIVE ->
                    tab.copy(state = TabState.ACTIVE, lastActiveAt = clock())

                closingActive && tab.id != nextActiveId && tab.state == TabState.ACTIVE ->
                    tab.copy(state = TabState.BACKGROUND)

                else -> tab
            }
        }
        _session.value = TabSession(activeTabId = nextActiveId, tabs = updatedTabs, updatedAt = clock())

        if (successor != null) {
            // 上面筛选已保证 successor 一定是存活标签，只切显示，不产生重启
            effects += TabEffect.Resume(successor.id)
        }
        logger.info("关闭标签 $tabId（${target.displayTitle}）" + if (nextActiveId == null) "，已无标签" else "，切换到 $nextActiveId")
        return effects
    }

    /**
     * 用户主动停止一个标签：释放实例、把状态降为 [TabState.COLD]，但**保留标签本身**。
     *
     * 与 [close] 的区别：不删除标签与会话记录，历史栈快照留在原地，
     * 用户再点该站点时会重新加载（近似恢复到当前 URL）；
     * 与 [markCold] 的区别：这是用户主动操作，必须同时产出 [TabEffect.Evict] 让平台真正释放实例，
     * 否则会出现「状态说冷、实例还热着」的不一致。
     *
     * 先算后写（与 [close] 同理）：避免在 CAS 重试块里叠加副作用导致平台收到重复指令。
     * 停掉的是活动标签时直接回到「无活动标签」态——用户下一步要么点站点重开，要么开新站。
     */
    fun stop(tabId: String): List<TabEffect> {
        val current = session.value
        val target = current.find(tabId) ?: return emptyList()
        if (!target.isLive) return emptyList()

        _session.update { state ->
            state.copy(
                activeTabId = if (state.activeTabId == tabId) null else state.activeTabId,
                tabs = state.tabs.map { if (it.id == tabId) it.copy(state = TabState.COLD) else it },
            )
        }
        logger.info("停止标签 $tabId（${target.displayTitle}）：实例已释放，标签保留")
        return listOf(TabEffect.Evict(target))
    }

    /**
     * 平台回写页面的真实状态。
     *
     * 由宿主在页面标题变化、导航完成时调用。冷标签不该回写（没有实例可问），
     * 因此内部按 id 存在性更新；[navigation] 传 `null` 表示不改动已有快照。
     */
    fun updateFromPlatform(
        tabId: String,
        url: String? = null,
        title: String? = null,
        navigation: TabNavigationSnapshot? = null,
    ) {
        _session.update { current ->
            current.copy(
                tabs = current.tabs.map { tab ->
                    if (tab.id != tabId) {
                        tab
                    } else {
                        tab.copy(
                            url = url?.takeIf { it.isNotBlank() } ?: tab.url,
                            title = title ?: tab.title,
                            navigation = navigation ?: tab.navigation,
                        )
                    }
                },
            )
        }
    }

    /**
     * 平台侧实例创建失败或被系统回收时的纠偏入口。
     *
     * 不变量：状态为 [TabState.ACTIVE] 但没有实例是允许的过渡态，但绝不能长期存在——
     * 宿主必须走这条路径把状态降下来，否则用户点别的标签时不会触发重新加载。
     */
    fun markCold(tabId: String, navigation: TabNavigationSnapshot? = null) {
        _session.update { current ->
            current.copy(
                activeTabId = if (current.activeTabId == tabId) null else current.activeTabId,
                tabs = current.tabs.map {
                    if (it.id == tabId) {
                        it.copy(state = TabState.COLD, navigation = navigation ?: it.navigation)
                    } else {
                        it
                    }
                },
            )
        }
        logger.info("标签 $tabId 降级为冷标签")
    }

    /**
     * 切换指定标签的 UA 模式（§4.4「当前站点 UA 切换」）。
     *
     * 只改持久化状态、产出 [TabEffect.SetUserAgent] 让平台实例生效，本身不触碰 WebView；
     * 平台池收到效果后调 `BrowserTabController.updateUserAgentMode`（默认 reload）。
     * 调用方负责落盘（`persistSession`），否则下次冷启动恢复的标签会回到默认移动 UA。
     */
    fun setUserAgentMode(tabId: String, mode: UserAgentMode): List<TabEffect> {
        val target = session.value.find(tabId) ?: return emptyList()
        if (target.userAgentMode == mode) return emptyList()
        _session.update { current ->
            current.copy(
                tabs = current.tabs.map {
                    if (it.id == tabId) it.copy(userAgentMode = mode) else it
                },
            )
        }
        logger.info("标签 $tabId UA 模式切换为 $mode")
        return listOf(TabEffect.SetUserAgent(tabId, mode))
    }

    /**
     * 系统内存压力入口（§4.2 第 4 条）。
     *
     * 预算算法：`LimitedTo` 下 `CRITICAL` 取 min(pressureLiveTabs, 上限)，其余取上限；
     * `Unbounded` 下**只有** `CRITICAL` 会给预算（= [pressureLiveTabs]），
     * 其它等级返回「不淘汰」——这正是 Q2 定档后必须写清的语义分界，
     * 否则一次普通的 `TRIM_MEMORY_BACKGROUND` 就会把用户后台页面杀光。
     */
    fun onMemoryPressure(level: MemoryPressureLevel): List<TabEffect> {
        val budget = when (policy) {
            is KeepAlivePolicy.LimitedTo ->
                if (level == MemoryPressureLevel.CRITICAL) minOf(pressureLiveTabs, policy.maxLiveTabs) else policy.maxLiveTabs

            KeepAlivePolicy.Unbounded ->
                if (level == MemoryPressureLevel.CRITICAL) pressureLiveTabs else UNBOUNDED_BUDGET
        }
        return evictToBudgetAndRecord(budget)
    }

    /**
     * 按保活策略做一次被动淘汰。默认策略下恒为空列表（不淘汰），
     * 因此 [openSite] / [activate] 可以无条件调用它。
     */
    private fun enforcePolicy(): List<TabEffect> = when (policy) {
        KeepAlivePolicy.Unbounded -> emptyList()
        is KeepAlivePolicy.LimitedTo -> evictToBudgetAndRecord(policy.maxLiveTabs)
    }

    /**
     * 把存活实例数压到 [budget]：同时产出 [TabEffect.Evict] 并把会话里的状态改完。
     *
     * 两件事必须在这里一次完成，不能只让调用方自己发效果：否则会出现
     * 「UI 显示 7 个热标签、池里只有 6 个实例」的状态与实例不一致。
     *
     * 淘汰依据只有 [SiteTab.lastActiveAt]：越小越久没被看过，越先被淘汰。
     * 不引入「页面权重」「是否正在播放」之类因子——那些需要平台状态才能判断，
     * 一旦掺进来，共享层的决策就不再可预测、也不再可单测。
     */
    private fun evictToBudgetAndRecord(budget: Int): List<TabEffect> {
        val current = session.value
        val live = current.tabs.filter { it.isLive }
        if (live.size <= budget) return emptyList()

        // 活动标签不在候选集内（预算不够时宁可多留它一个实例，也不要切回去变白屏）
        val victims = live
            .filterNot { it.id == current.activeTabId }
            .sortedBy { it.lastActiveAt }
            .take(live.size - budget)
        if (victims.isEmpty()) return emptyList()

        val victimIds = victims.mapTo(mutableSetOf()) { it.id }
        _session.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id in victimIds) it.copy(state = TabState.COLD) else it })
        }
        logger.info("内存策略：释放 ${victims.size} 个实例（上限 $budget）")
        return victims.map { TabEffect.Evict(it) }
    }

    companion object {

        /** 严重内存压力下的保活存活数：活动标签 + 1。 */
        const val PRESSURE_MAX_LIVE_TABS = 2

        /** 标签总数硬护栏（§9.1 R4）。不是保活上限，只防对象图与会话文件无限膨胀。 */
        const val HARD_TAB_LIMIT = 32

        /** 超过此标签数时 UI 提示内存风险，只提示不淘汰。 */
        const val SOFT_WARN_TAB_COUNT = 12

        /** `Unbounded` 策略下的「无穷大预算」，语义是本次不淘汰任何实例。 */
        private const val UNBOUNDED_BUDGET = Int.MAX_VALUE
    }
}
