package org.aggregated.player.platform

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.usecase.TabEffect

/**
 * 标签效果的执行端。
 *
 * 共享层负责决定「该做什么」（[TabEffect]），平台侧负责「真的去做」。
 * 这层抽象存在的理由有两个（设计文档 §4.1）：
 * 1. `TabManager` 的决策可以被单元测试覆盖，不需要真机 WebView；
 * 2. UI（`AppViewModel`）不需要知道底下是 Android WebView 池还是桌面的 WebView2。
 *
 * 实现要求：[execute] 可能被组合线程调用，必须**只在主线程上做平台调用**并保证可重入安全。
 */
fun interface TabEffectExecutor {

    /** 按顺序执行效果列表。实现不得抛异常穿透到 UI：失败要转成日志 + 状态纠偏（`markCold`）。 */
    fun execute(effects: List<TabEffect>)
}

/**
 * 默认实现：只记日志，不做任何平台动作。
 *
 * 用途是桌面端（阶段 5 之前没有 WebView 池）与测试环境下的兜底，
 * 保证「共享层产出效果 → 有人消费」这条链路在平台实现缺席时也是闭合的，
 * 而不是靠一堆 `if (pool != null)` 分支。
 */
class LoggingTabEffectExecutor(
    private val logger: Logger,
    private val delegate: TabEffectExecutor? = null,
) : TabEffectExecutor {

    override fun execute(effects: List<TabEffect>) {
        effects.forEach { effect -> logger.debug("标签效果：${describe(effect)}") }
        // 允许包一层真实执行器：这样装饰者顺序（先记录再执行）由本类固定住，避免调用方写反
        delegate?.execute(effects)
    }

    private fun describe(effect: TabEffect): String = when (effect) {
        is TabEffect.Load -> "Load ${effect.tab.id} → ${effect.tab.url}"
        is TabEffect.Resume -> "Resume ${effect.tabId}"
        is TabEffect.Pause -> "Pause ${effect.tabId}"
        is TabEffect.Evict -> "Evict ${effect.tab.id}"
        is TabEffect.Discard -> "Discard ${effect.tabId}"
        is TabEffect.Navigate -> "Navigate ${effect.tabId} ${effect.action}"
        is TabEffect.SetUserAgent -> "SetUserAgent ${effect.tabId} → ${effect.mode}"
    }
}
