package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.ResourceType

/**
 * 组合式广告拦截引擎（设计文档 §5，T2 骨架）。
 *
 * 把多个 [AdBlockEngine]（如第三方 EasyList 引擎 + 自研手动/标记层）并联：
 * - [reload] 广播给所有子引擎（用户规则只进手动层，由各子引擎自行过滤）；
 * - [decide] 任一子引擎拦截即拦截（例外 `Pass` 优先由各子引擎内部处理）；
 * - [elementRulesFor] 合并所有子引擎的隐藏规则，由平台侧拼 CSS 注入。
 *
 * 跨引擎「用户白名单压过第三方拦截」的细粒度优先级是 T4 接真实引擎时的细化项；本骨架按「先到先得」处理，
 * 即先遍历到的子引擎若 `Blocked` 即返回，后续子引擎的例外不再翻盘。
 */
class CompositeAdBlockEngine(
    private val engines: List<AdBlockEngine>,
) : AdBlockEngine {

    override val activeRuleCount: Int get() = engines.sumOf { it.activeRuleCount }

    override fun reload(rules: List<AdRule>) {
        engines.forEach { it.reload(rules) }
    }

    override fun decide(
        url: String,
        siteId: String?,
        siteAdBlockEnabled: Boolean,
        resourceType: ResourceType,
        documentUrl: String?,
    ): InterceptionDecision {
        if (!siteAdBlockEnabled) return InterceptionDecision.Pass
        // 组合层已应用站点级逃生口；子引擎一律按「开启」判定，避免双重门控。
        for (engine in engines) {
            when (val decision = engine.decide(url, siteId, true, resourceType, documentUrl)) {
                is InterceptionDecision.Blocked -> return decision
                is InterceptionDecision.Pass -> Unit
            }
        }
        return InterceptionDecision.Pass
    }

    override fun elementRulesFor(siteId: String?): List<AdRule> =
        engines.flatMap { it.elementRulesFor(siteId) }
}
