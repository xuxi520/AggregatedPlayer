package org.aggregated.player.platform

import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.UserAgentMode

/**
 * 单个标签页背后的 WebView 实例控制契约（设计文档 §4.2）。
 *
 * 这个接口同时充当**平台实现的可测缝隙**：Android 侧由 `AndroidTabWebViewHandle` 包住真 WebView，
 * 单元测试里用假实现记录调用序列，从而在没有 WebView 运行时的情况下验证实例池的调度逻辑。
 * 因此接口里**不出现任何 Android 类型**，视图对象一律以 [platformView] / [container] 的 `Any` 形式流转。
 *
 * 生命周期约定（阶段 1 的验收口径）：
 * - 实例创建后常驻；切换标签只调用 [pause] / [resume]，不调用 [release]——这是「切回即继续浏览」的前提；
 * - 只有内存压力降级或用户关闭标签才 [release]；
 * - 所有方法都必须在**主线程**调用（Android 的 `WebView.onPause/onResume/loadUrl/destroy` 有线程约束），
 *   实现方应通过可注入的主线程检查器断言，而不是默默在错误线程上跑。
 */
interface BrowserTabController {

    /** 所属标签 id，与 `domain.model.SiteTab.id` 一一对应。 */
    val tabId: String

    /** 底层实例是否存活。冷标签为 `false`，此时除 [loadUrl] 外的操作都无意义。 */
    val isAlive: Boolean

    val currentUrl: String?

    val title: String?

    val canGoBack: Boolean

    val canGoForward: Boolean

    val isLoading: Boolean

    /**
     * 平台视图对象（Android 是 `View`，桌面是 Swing 组件），共享层不解释其类型。
     * 宿主把它挂进统一容器，靠 [setVisibility] 切换，**切换标签不重建视图**。
     */
    val platformView: Any

    /** 加载页面。冷标签晋升为热标签的唯一入口。 */
    fun loadUrl(url: String)

    fun reload()

    fun stopLoading()

    fun goBack()

    fun goForward()

    /**
     * 单实例降载。
     *
     * ⚠️ 实现必须做到两件事，且**不能只做第一件**：
     * 1. 调用逐实例的暂停 API（Android：`WebView.onPause()`）；
     * 2. **显式暂停页面媒体**（`evaluateJavascript` 遍历 `video`/`audio` 调 `pause()`）。
     *
     * 原因：官方文档明确 `onPause()` 是 best-effort、**不暂停 JavaScript**，
     * 而后台标签串音是用户直接能感知的问题（§4.2 第 2 条）。
     * 同时**禁止**用进程级全局的 `pauseTimers()` 实现它——那会冻住当前标签。
     */
    fun pause()

    /** 与 [pause] 对称：恢复实例运行，但**不自动续播媒体**（由用户手势触发播放，避免突然出声）。 */
    fun resume()

    /** 设置可见性。隐藏不等于销毁：视图留在容器里，实例与页面状态都保持。 */
    fun setVisibility(visible: Boolean)

    /** 把视图加入宿主的统一容器；若已挂在别处应先脱离，避免 `addView` 抛「已有父视图」。 */
    fun attachTo(container: Any)

    /** 从当前父容器脱离（释放或标签关闭时调用）。 */
    fun detachFromParent()

    /**
     * 导出导航快照（URL 序列 + 当前索引）。
     *
     * 注意：**不导出**平台私有的历史栈二进制（Bundle/Parcel），
     * 那份数据跨系统版本不可靠，阶段 1 明确不做持久化（§4.2 第 6 条）。
     */
    fun navigationSnapshot(): TabNavigationSnapshot

    /**
     * 切换「元素标记模式」（设计文档 §4.3 的点级标记）。
     *
     * 开启时页面长按进入拾取（由 `AdBlockingBridge` 驱动的 JS 侦测脚本生效，并抑制网页原生长按选择）；
     * 关闭则恢复原生行为。平台实现把该标志写入页面，并视情况注入/移除抑制样式。
     */
    fun setAdMarkingMode(enabled: Boolean)

    /** 立即重注入元素隐藏样式（规则变更后无需等下次导航即可生效）。 */
    fun reapplyAdBlockStyles()

    /**
     * 运行时切换 UA 模式（见 [org.aggregated.player.domain.model.UserAgentMode]）。
     *
     * 切换后必须重载当前页面才会生效（WebView 仅在 `loadUrl` 时读取 `userAgentString`），
     * 因此实现默认 `reload = true`。加载类效果（[org.aggregated.player.domain.usecase.TabEffect.Load]）
     * 在创建实例后会以 `reload = false` 调用本方法——UA 先就位，再由紧随其后的 `loadUrl` 一次性加载，
     * 避免双重加载（§4.4）。
     */
    fun updateUserAgentMode(mode: UserAgentMode, reload: Boolean = true)

    /**
     * 层级微调：把当前已选（高亮）元素上钻到父级 / 下钻到第一个子元素，重新描边并回传。
     * 解决移动端手指粗、容易点到子元素的问题；页面无高亮元素时静默 no-op。
     */
    fun pickParent()

    /** 见 [pickParent]；下钻到第一个子元素。 */
    fun pickChild()

    /** 释放底层实例并置 [isAlive] 为 `false`。调用后本对象不应再被使用。 */
    fun release()
}

/**
 * 标签实例池：按 tabId 获取/创建/释放 [BrowserTabController]。
 *
 * 职责边界：池只管「实例存在与否 + 视图摆放」，**不管**「该淘汰谁」——
 * 淘汰决策在共享层 `TabManager`（§4.1），产出 [org.aggregated.player.domain.usecase.TabEffect] 后
 * 由池执行。这样容量策略跨端可复用，而且能被纯逻辑单元测试覆盖。
 *
 * 实现要求：内部容器需并发安全（UI 线程与 WebView 工作线程都可能查询）。
 */
interface BrowserTabPool {

    /** 取得实例；不存在则以 [entryUrl] 创建一个并加载。 */
    fun obtain(tabId: String, entryUrl: String): BrowserTabController

    /** 只在实例已存在时返回，不产生创建副作用。 */
    fun peek(tabId: String): BrowserTabController?

    /** 当前存活实例数，供容量策略与内存打点使用（与 `TabSession.liveTabCount` 互为校验）。 */
    fun liveCount(): Int

    fun release(tabId: String)

    /**
     * 声明宿主的统一容器，池负责把每个存活实例的视图 attach 进去。
     *
     * 为什么把 attach 交给池而不是每个 Composable：视图的父子关系一旦分散到 UI 层，
     * 就很容易在 recomposition 时被重复添加（Android 会抛「视图已拥有父视图」）。
     * [setHostContainer] 可重复调用，池内部去重。
     */
    fun setHostContainer(container: Any)

    /** 宿主销毁时调用，逐个 `stopLoading` → `detach` → `release`，防止 Activity 泄漏。 */
    fun releaseAll()

    /**
     * 让 [activeTabId] 对应的实例可见、其余隐藏。
     *
     * 由宿主在每次组合更新时调用：可见性属于**视图层事实**，不该由共享层状态机去猜，
     * 否则会出现「状态已切换但屏幕上还是上一页」的错位。
     */
    fun syncVisibility(activeTabId: String?)

    /** 遍历所有存活实例（供标记模式 / 样式重注入等需要「广播到每个页面」的场景使用）。 */
    fun forEachActive(action: (BrowserTabController) -> Unit)
}

/**
 * 创建 [BrowserTabController] 的工厂。
 *
 * 单独拆出来有两个目的：池的测试可以注入假工厂（不需要 Android 运行时）；
 * 将来桌面端复用同一个池实现，只要换一个工厂。
 */
fun interface BrowserTabControllerFactory {

    /**
     * 以 [tabId] 为标识创建一个新实例。
     *
     * ⚠️ **只创建，不要加载页面**：加载由池在收到 [org.aggregated.player.domain.usecase.TabEffect.Load]
     * 时统一调用 [BrowserTabController.loadUrl]。两处入口会让「什么时候会发生一次真实加载」
     * 变得不可追溯，而这正是调试保活与重复加载问题时必须能一眼看出的事。
     */
    fun create(tabId: String, entryUrl: String): BrowserTabController
}
