package org.aggregated.player.domain.usecase

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.ResourceType

/**
 * 基于第三方过滤列表（EasyList 子集）的拦截引擎（设计文档 §4.3 / T4）。
 *
 * 把 [BundledFilterLists] 里的真实第三方规则经 [EasyListImporter] 解析后，交给内部 `AdRuleEngine` 建索引——
 * 复用同一套内存匹配逻辑，不重复实现；同时与「用户规则引擎」彻底隔离：第三方列表量级大且不可编辑，
 * 不应污染用户可见的 `ad_rules.json`。
 *
 * 通过 [CompositeAdBlockEngine] 与手动/标记层并联：任一引擎拦截即拦，元素隐藏合并注入。
 * [reload] 保持内置列表不变（用户规则由 manual 引擎负责），仅匹配接口签名。
 */
class FilterListEngine(
    logger: Logger,
    listText: String = BundledFilterLists.EASYLIST,
) : AdBlockEngine {

    private val delegate = AdRuleEngine(logger).apply {
        reload(EasyListImporter.parse(listText))
    }

    override val activeRuleCount: Int get() = delegate.activeRuleCount

    @Suppress("UNUSED_PARAMETER")
    override fun reload(rules: List<AdRule>) {
        // 第三方列表自带索引，不接收用户规则（用户规则在 manual 引擎）；保留参数以匹配接口。
    }

    override fun decide(
        url: String,
        siteId: String?,
        siteAdBlockEnabled: Boolean,
        resourceType: ResourceType,
        documentUrl: String?,
    ): InterceptionDecision = delegate.decide(url, siteId, siteAdBlockEnabled, resourceType, documentUrl)

    override fun elementRulesFor(siteId: String?): List<AdRule> = delegate.elementRulesFor(siteId)
}
