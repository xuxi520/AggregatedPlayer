package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 规则类型。语法与语义见设计文档 §4.3 的表格。
 */
enum class AdRuleKind {

    /** 域名规则：命中该域名及其子域（ABP 的 `||host^` 语义）。 */
    DOMAIN,

    /** URL 片段规则：请求 URL 包含 [AdRule.pattern] 即拦截。 */
    URL_SUBSTRING,

    /** 例外（白名单）：优先于屏蔽规则匹配，用于修复误拦截。 */
    EXCEPTION,

    /**
     * 元素隐藏规则：pattern 为 CSS 选择器。
     *
     * 它**不参与 URL 拦截**（网络层看不见 DOM），只能由平台侧注入 CSS/移除节点生效；
     * 阶段 0 仅做存储与展示，注入实现排在阶段 2（§4.3）。
     */
    ELEMENT,
}

/**
 * 一条持久化的广告拦截规则。
 *
 * 持久化位置：`ad_rules.json`。
 * 之所以不把规则塞进 `Site.customRules: List<String>`（v1.0 的写法）：
 * 那样无法表达「规则类型 / 启停状态 / 全局与站点级作用域 / 创建时间」，
 * 而这些恰恰是点级标记与误拦截修复必需的信息。
 */
@Serializable
data class AdRule(
    val id: String,

    /**
     * 匹配内容。语义随 [kind] 变化：
     * [AdRuleKind.DOMAIN] 是裸域名（`ads.example.com`），
     * [AdRuleKind.URL_SUBSTRING] 是 URL 子串（`/track-pixel.js`），
     * [AdRuleKind.ELEMENT] 是 CSS 选择器。
     */
    val pattern: String,

    val kind: AdRuleKind = AdRuleKind.URL_SUBSTRING,

    /**
     * 作用站点 id；`null` 表示全局规则。
     *
     * 点级标记默认生成站点级规则——用户是在某个站点上标记的，
     * 直接推广到全局会让一个站点的误标记影响所有站点，风险不对称。
     */
    val siteId: String? = null,

    /** 临时停用而不删除。排查「页面为什么白屏」时逐条开关比删了重加快得多。 */
    val enabled: Boolean = true,

    /** 用户备注，例如「片头广告」。仅用于规则管理界面。 */
    val note: String? = null,

    /** 创建时间（epoch millis），由仓储填充。 */
    val createdAt: Long = 0L,

    /**
     * 是否为正则规则：`true` 时 [pattern] 是（去掉首尾 `/` 的）正则，由引擎按正则匹配整条 URL。
     * 仅第三方过滤列表（EasyList 的 `/regex/`）会用到；用户手动规则默认 `false`。
     */
    val isRegex: Boolean = false,

    /**
     * 第三方约束：`true` 仅匹配第三方请求，`false` 仅匹配同站（首方）请求，`null` 不限。
     * 对应 ABP 的 `$third-party` / `$~third-party`。判定需要页面主帧 URL（[decide] 的 `documentUrl`）。
     */
    val thirdParty: Boolean? = null,

    /**
     * 正资源类型约束：非空时仅当请求类型命中其中之一才拦截（对应 `$script` / `$image` …）。
     * 若调用方给不出类型（[ResourceType.UNKNOWN]），引擎为避免过度拦截而**不命中**。
     */
    val resourceTypes: Set<ResourceType> = emptySet(),

    /** 负资源类型约束：命中即放行（对应 `$~script` 等）。 */
    val excludedResourceTypes: Set<ResourceType> = emptySet(),

    /**
     * 页面域名限定（对应 `$domain=a.com|b.com`）：仅当页面主帧域名（注册域）命中其一才生效。
     * 需要 [decide] 的 `documentUrl`；缺上下文且本集合非空时规则不命中。
     */
    val domainRestrictions: Set<String> = emptySet(),

    /** 页面域名排除（对应 `$domain=~a.com`）：页面注册域命中即放行。 */
    val excludedDomains: Set<String> = emptySet(),
)
