package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.ResourceType

/**
 * 广告拦截引擎契约（设计文档 §4.3 / §5，T2 抽出）。
 *
 * 把 [AdRuleEngine]（自研「手动/标记层」）与未来的第三方引擎（AdGuard / adblock-rust）统一到同一契约，
 * 使 `AdBlockingBridge` 与平台控制器只依赖此接口、不关心底层是谁——详见 [CompositeAdBlockEngine]。
 *
 * 纯逻辑、无 IO、无平台依赖：索引在 [reload] 时一次性构建，[decide] 只做内存查找
 * （`shouldInterceptRequest` 每请求回调，任意磁盘读都会被感知为卡顿）。线程安全：[decide] 可并发；
 * [reload] 期望由单一后台线程调用。
 */
interface AdBlockEngine {

    /** 已启用的网络规则条数（不含元素隐藏类），供设置页展示。 */
    val activeRuleCount: Int

    /**
     * 重建索引。
     *
     * @param rules 全量规则（含禁用项，内部按 [AdRule.enabled] 过滤）。
     */
    fun reload(rules: List<AdRule>)

    /**
     * 判定一次资源请求。
     *
     * @param url 请求地址。
     * @param siteId 当前标签归属的站点 id；`null` 表示临时标签，只应用全局规则。
     * @param siteAdBlockEnabled 站点级开关，关闭时**一律放行**（用户的逃生口）。
     * @param resourceType 资源类型；[ResourceType.UNKNOWN] 表示调用方无法判断（如 Android `shouldInterceptRequest`）。
     *   带正类型约束（`$script`/`$image`…）的规则在 UNKNOWN 下**不命中**（避免过度拦截）。
     * @param documentUrl 页面主帧 URL；用于 `$third-party` 与 `$domain=` 判定，缺省时相关约束按「不命中」处理。
     * @return [InterceptionDecision.Pass] 放行（并顺带交给嗅探）；[InterceptionDecision.Blocked] 拦截。
     */
    fun decide(
        url: String,
        siteId: String?,
        siteAdBlockEnabled: Boolean = true,
        resourceType: ResourceType = ResourceType.UNKNOWN,
        documentUrl: String? = null,
    ): InterceptionDecision

    /**
     * 当前站点可用的元素隐藏规则（CSS 选择器逻辑在 [AdRule.pattern] 中），
     * 由平台侧 `evaluateJavascript` 注入 `<style>` 生效。
     */
    fun elementRulesFor(siteId: String?): List<AdRule>
}
