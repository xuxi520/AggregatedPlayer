package org.aggregated.player.platform.web

import org.aggregated.player.domain.usecase.AdBlockEngine

/**
 * 广告拦截的宿主桥接（与 `VideoFullscreenBridge` 同款解耦思路）。
 *
 * `WebView.shouldInterceptRequest` 的回调在平台控制器里，但「规则引擎实例」「标签→站点上下文查询」「全局总开关」
 * 都归共享层 / 对象图所有，且三者存在构造环（AppGraph → 池 → 工厂 → 引擎），所以用进程级单例在图建好后再注入，
 * 平台控制器只做无依赖的被动读取。[engine] 为 null 时拦截逻辑自动降级为放行，不会崩。
 *
 * 放在 commonMain：全局开关由 UI 层（[org.aggregated.player.ui.AppViewModel]）在设置变化时写入，
 * 桌面端复用同一份，无需平台侧各写一遍。
 */
object AdBlockingBridge {

    /** 已加载规则的规则引擎（任一 [AdBlockEngine] 实现；图建好后由 `AndroidBrowserRuntime` 注入）。 */
    @Volatile
    var engine: AdBlockEngine? = null

    /**
     * 给定标签 id，返回其归属的站点 id 与站点级拦截开关；查不到时回落到 `(null, true)`。
     * 由运行时注入：内部用会话内存态解析 siteId、用仓储解析 `Site.adBlockEnabled`。
     */
    @Volatile
    var siteContext: ((tabId: String) -> Pair<String?, Boolean>)? = null

    /**
     * 全局总开关（对应 `AppSettings.adBlockEnabled`）。关闭时所有站点一律放行——
     * 是「增强而非替代」原则下的最高优先级逃生口（§2.3）。由 `AppViewModel` 在设置变化时同步。
     */
    @Volatile
    var globalEnabled: Boolean = true

    /**
     * 元素标记模式开关（工具面板即时切换，区别于设置里的常驻配置）。
     * 开启时页面长按进入拾取；关闭则恢复网页原生长按行为。由 `AppViewModel` 写入，
     * 平台控制器在 `onPageFinished` 与模式切换时据此设置页面级标志。
     */
    @Volatile
    var markingEnabled: Boolean = false

    /**
     * 元素拾取回调：JS 侦测到长按后，把 `(tabId, JSON)` 回传给 UI 层（[org.aggregated.player.ui.AppViewModel]）。
     * 由 `AppViewModel` 在启动时注入。
     */
    @Volatile
    var onElementPicked: ((tabId: String, json: String) -> Unit)? = null

    /**
     * 标记模式切换时由运行时遍历所有存活实例下发（[AppViewModel] 设置，[AndroidBrowserRuntime] 执行）。
     */
    @Volatile
    var onMarkingModeChanged: ((enabled: Boolean) -> Unit)? = null

    /**
     * `ELEMENT` 规则变更后由运行时遍历所有存活实例重注入隐藏样式（[AppViewModel] 设置，运行时执行），
     * 让标记/撤销立即生效，无需等下次导航。
     */
    @Volatile
    var onReapplyStyles: (() -> Unit)? = null

    /**
     * 层级微调：确认框里用户点「父级 / 子级」时，由 [AppViewModel] 触发、运行时遍历存活实例执行，
     * 仅当前可见（有高亮元素）的页面会真正重选并回传新选择器。
     */
    @Volatile
    var onPickParent: (() -> Unit)? = null

    /** 见 [onPickParent]；下钻到第一个子元素。 */
    @Volatile
    var onPickChild: (() -> Unit)? = null
}
