package org.aggregated.player.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 沉浸式浏览的瞬时 UI 信号：用户当前是否正在滚动页面。
 *
 * 用途单一——悬浮控件据此在滚动时淡出、停手后重现（设计文档 §2.3「增强而非替代」的界面体现）。
 * 它是**平台推送、UI 只读**的单向通道：平台侧检测到 WebView 滚动就 [markScrolling]，
 * 停止滚动一段时间后由平台侧的节流器 [markIdle]。
 *
 * 为什么不并进 [BrowserTabController] 契约：那个契约管的是实例生命周期与调度（创建/暂停/释放），
 * 而「是否在滚动」是易失的渲染信号，混进去会让池的语义变浑、也让桌面端被迫实现一个用不上的回调。
 * 与内存压力等级一样，这里只做「事实」，何时淡出控件的判断留在 UI。
 *
 * 线程安全：[StateFlow] 自身线程安全，任意线程可读写。
 */
class ImmersiveSignals {

    private val _isScrolling = MutableStateFlow(false)

    /** 页面是否正在滚动；`true` 时悬浮控件应隐藏。 */
    val isScrolling: StateFlow<Boolean> = _isScrolling.asStateFlow()

    /**
     * 页面被点击的递增计数。每次用户按下 WebView 就 +1。
     *
     * 用计数而不是布尔：UI 侧靠 `LaunchedEffect(tick)` 拿到「刚刚发生了一次点击」的事件，
     * 布尔值无法区分「持续为 true」与「又来了一次」。
     */
    private val _pageTapTick = MutableStateFlow(0L)
    val pageTapTick: StateFlow<Long> = _pageTapTick.asStateFlow()

    /** 标记进入滚动。重复调用无副作用。 */
    fun markScrolling() {
        if (!_isScrolling.value) _isScrolling.value = true
    }

    /** 标记滚动停止。重复调用无副作用。 */
    fun markIdle() {
        if (_isScrolling.value) _isScrolling.value = false
    }

    /** 平台在用户按下页面时调用；UI 据此收起展开的悬浮面板（需 [AppSettings.tapPageCollapseEnabled]）。 */
    fun notifyPageTap() {
        _pageTapTick.value += 1
    }
}
