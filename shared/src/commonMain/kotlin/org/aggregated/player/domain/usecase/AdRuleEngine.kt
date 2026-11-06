package org.aggregated.player.domain.usecase

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.ResourceType
import org.aggregated.player.domain.model.UriParts

/**
 * 一次请求的拦截决策。
 */
sealed interface InterceptionDecision {

    /** 放行。由调用方继续加载资源（并顺带交给 [MediaSniffer] 嗅探）。 */
    data object Pass : InterceptionDecision

    /** 拦截。[rule] 用于「拦截日志」与点级标记的撤销入口——用户必须能看到是哪条规则拦的。 */
    data class Blocked(val rule: AdRule) : InterceptionDecision
}

/**
 * 广告规则决策引擎（设计文档 §4.3）。
 *
 * **纯逻辑、无 IO、无平台依赖**：索引在 [reload] 时一次性构建，[decide] 只做内存查找。
 * 这是性能约束的直接结果——`shouldInterceptRequest` 是每请求回调，一个页面可能触发上百次，
 * 任何一次磁盘读都会被用户感知为「卡」。
 *
 * 规则语法是 ABP 的**子集**，只实现 `||host^`、`||host/path`、URL 子串、`@@` 例外四类；
 * 通配符 `$`、`$domain=` 选项、正则等高级语法暂不支持，需要时优先接入成熟引擎而不是在这里扩展。
 *
 * 线程安全：[decide] 可被多线程并发调用；[reload] 期望由单一后台线程调用。
 * 新索引对其它线程的可见性不做强制保证（不引入 atomic 依赖），
 * 最坏情况是紧跟重载的那一个请求按旧规则判定——对广告拦截而言可以接受。
 */
class AdRuleEngine(private val logger: Logger) : AdBlockEngine {

    /** 编译后的域名条目。[pathPrefix] 非空时要求路径前缀匹配，用于 `||host/path` 形式。 */
    private class DomainEntry(val rule: AdRule, val pathPrefix: String?)

    /**
     * 不可变的规则快照。
     *
     * 分成「全局」与「站点级」两套，是因为站点级规则只对产生它的站点生效：
     * 用户在 A 站标记的误伤规则不应该影响 B 站（§4.3）。
     */
    private class Snapshot(
        val globalDomains: Map<String, List<DomainEntry>> = emptyMap(),
        val siteDomains: Map<String, Map<String, List<DomainEntry>>> = emptyMap(),
        val globalSubstrings: List<Pair<String, AdRule>> = emptyList(),
        val siteSubstrings: Map<String, List<Pair<String, AdRule>>> = emptyMap(),
        val globalRegexes: List<Pair<Regex, AdRule>> = emptyList(),
        val siteRegexes: Map<String, List<Pair<Regex, AdRule>>> = emptyMap(),
        val exceptionDomains: Set<String> = emptySet(),
        val exceptionSubstrings: List<String> = emptyList(),
        val exceptionRegexes: List<Regex> = emptyList(),
        val elementRules: List<AdRule> = emptyList(),
    )

    private var snapshot = Snapshot()

    /** 已启用的域名/子串/正则规则条数（不含元素隐藏类），供设置页展示。 */
    override val activeRuleCount: Int
        get() = snapshot.globalDomains.values.sumOf { it.size } +
            snapshot.siteDomains.values.sumOf { site -> site.values.sumOf { it.size } } +
            snapshot.globalSubstrings.size +
            snapshot.siteSubstrings.values.sumOf { it.size } +
            snapshot.globalRegexes.size +
            snapshot.siteRegexes.values.sumOf { it.size } +
            snapshot.exceptionRegexes.size

    /**
     * 重建索引。
     *
     * @param rules 全量规则（含禁用项，内部按 [AdRule.enabled] 过滤）。
     */
    override fun reload(rules: List<AdRule>) {
        val enabled = rules.filter { it.enabled }
        val urlRules = enabled.filter { it.kind != AdRuleKind.ELEMENT }
        val exceptions = urlRules.filter { it.kind == AdRuleKind.EXCEPTION }
        val blockers = urlRules.filter { it.kind != AdRuleKind.EXCEPTION }

        val exceptionDomains = mutableSetOf<String>()
        val exceptionSubstrings = mutableListOf<String>()
        val exceptionRegexes = mutableListOf<Regex>()
        exceptions.forEach { rule -> compile(rule, exceptionDomains, exceptionSubstrings, exceptionRegexes) }

        val globalDomains = mutableMapOf<String, MutableList<DomainEntry>>()
        val siteDomains = mutableMapOf<String, MutableMap<String, MutableList<DomainEntry>>>()
        val globalSubstrings = mutableListOf<Pair<String, AdRule>>()
        val siteSubstrings = mutableMapOf<String, MutableList<Pair<String, AdRule>>>()
        val globalRegexes = mutableListOf<Pair<Regex, AdRule>>()
        val siteRegexes = mutableMapOf<String, MutableList<Pair<Regex, AdRule>>>()

        blockers.forEach { rule ->
            // 正则规则单独成桶：按整条 URL 匹配，与 kind/域名桶无关
            if (rule.isRegex) {
                val regex = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (regex == null) {
                    logger.warn("忽略非法正则规则：id=${rule.id}")
                } else {
                    when (val siteId = rule.siteId) {
                        null -> globalRegexes += regex to rule
                        else -> siteRegexes.getOrPut(siteId) { mutableListOf() } += regex to rule
                    }
                }
                return@forEach
            }
            when (rule.kind) {
                AdRuleKind.DOMAIN -> {
                    val (host, pathPrefix) = normalizeDomain(rule.pattern) ?: return@forEach
                    val bucket = when (val siteId = rule.siteId) {
                        null -> globalDomains.getOrPut(host) { mutableListOf() }
                        else -> siteDomains.getOrPut(siteId) { mutableMapOf() }
                            .getOrPut(host) { mutableListOf() }
                    }
                    bucket += DomainEntry(rule, pathPrefix)
                }

                AdRuleKind.URL_SUBSTRING -> {
                    val needle = rule.pattern.trim().lowercase()
                    if (needle.isEmpty()) {
                        logger.warn("忽略空白 URL 子串规则：id=${rule.id}")
                    } else when (val siteId = rule.siteId) {
                        null -> globalSubstrings += needle to rule
                        else -> siteSubstrings.getOrPut(siteId) { mutableListOf() } += needle to rule
                    }
                }

                // ELEMENT 不参与网络层匹配（DOM 看不见 URL），交给 elementRulesFor() 供注入使用
                else -> Unit
            }
        }

        snapshot = Snapshot(
            globalDomains = globalDomains,
            siteDomains = siteDomains,
            globalSubstrings = globalSubstrings,
            siteSubstrings = siteSubstrings,
            globalRegexes = globalRegexes,
            siteRegexes = siteRegexes,
            exceptionDomains = exceptionDomains,
            exceptionSubstrings = exceptionSubstrings,
            exceptionRegexes = exceptionRegexes,
            elementRules = enabled.filter { it.kind == AdRuleKind.ELEMENT },
        )
        logger.info("规则索引重建完成：域名 ${globalDomains.size + siteDomains.values.sumOf { it.size }} 项，" +
            "子串 ${globalSubstrings.size + siteSubstrings.values.sumOf { it.size }} 项，" +
            "例外 ${exceptionDomains.size + exceptionSubstrings.size} 项")
    }

    /**
     * 判定一次资源请求。
     *
     * @param url 请求地址。
     * @param siteId 当前标签归属的站点 id；`null` 表示临时标签，只应用全局规则。
     * @param siteAdBlockEnabled 站点级开关，关闭时**一律放行**（用户的逃生口，见 [Site] 说明）。
     */
    override fun decide(
        url: String,
        siteId: String?,
        siteAdBlockEnabled: Boolean,
        resourceType: ResourceType,
        documentUrl: String?,
    ): InterceptionDecision {
        if (!siteAdBlockEnabled) return InterceptionDecision.Pass

        val parts = UriParts.parse(url) ?: return InterceptionDecision.Pass
        val host = parts.host
        val loweredUrl = url.lowercase()

        // 例外优先：ABP 语义里 @@ 先于屏蔽规则，用户加白名单时必须能立刻见效
        if (host != null) {
            foreachParentDomain(host) { candidate ->
                if (snapshot.exceptionDomains.contains(candidate)) return InterceptionDecision.Pass
            }
        }
        if (snapshot.exceptionSubstrings.any { loweredUrl.contains(it) }) return InterceptionDecision.Pass
        if (snapshot.exceptionRegexes.any { it.containsMatchIn(loweredUrl) }) return InterceptionDecision.Pass

        // 域名规则：命中主机后还要过一遍 $option 约束
        if (host != null) {
            val siteMap = siteId?.let { snapshot.siteDomains[it] }
            foreachParentDomain(host) { candidate ->
                // 全局与站点级都要查：全局命中但路径前缀不匹配时，不能因此跳过站点级同名条目
                val candidates = buildList {
                    snapshot.globalDomains[candidate]?.let { addAll(it) }
                    siteMap?.get(candidate)?.let { addAll(it) }
                }
                candidates.firstOrNull { it.matchesPath(parts) && matchesOptions(it.rule, host, resourceType, documentUrl) }
                    ?.let { return InterceptionDecision.Blocked(it.rule) }
            }
        }

        // 子串规则
        val substringCandidates =
            snapshot.globalSubstrings + (siteId?.let { snapshot.siteSubstrings[it] } ?: emptyList())
        substringCandidates.firstOrNull { loweredUrl.contains(it.first) && matchesOptions(it.second, host, resourceType, documentUrl) }
            ?.let { return InterceptionDecision.Blocked(it.second) }

        // 正则规则
        val regexCandidates =
            snapshot.globalRegexes + (siteId?.let { snapshot.siteRegexes[it] } ?: emptyList())
        regexCandidates.firstOrNull { it.first.containsMatchIn(loweredUrl) && matchesOptions(it.second, host, resourceType, documentUrl) }
            ?.let { return InterceptionDecision.Blocked(it.second) }

        return InterceptionDecision.Pass
    }

    /**
     * 命中候选后，逐一校验 [AdRule] 上的 `$option` 约束。任一约束不满足即视为「不命中」（返回 false），
     * 由上层继续尝试其它候选——宁可少拦，避免选项误配带来的灾难级误拦截。
     */
    private fun matchesOptions(
        rule: AdRule,
        requestHost: String?,
        resourceType: ResourceType,
        documentUrl: String?,
    ): Boolean {
        // $third-party / $~third-party：缺上下文（请求主机或页面 URL 任一未知）时无法断言 -> 不命中
        rule.thirdParty?.let { wantThird ->
            if (requestHost == null || documentUrl == null) return false
            if (isThirdParty(requestHost, documentUrl) != wantThird) return false
        }

        // $domain= / $domain=~：需要页面主帧域名（注册域）
        if (rule.domainRestrictions.isNotEmpty() || rule.excludedDomains.isNotEmpty()) {
            val pageHost = documentUrl?.let { UriParts.parse(it)?.host }
            if (pageHost == null) {
                if (rule.domainRestrictions.isNotEmpty()) return false
            } else {
                val reg = registrableDomain(pageHost)
                if (rule.domainRestrictions.isNotEmpty() && rule.domainRestrictions.none { registrableDomain(it) == reg }) {
                    return false
                }
                if (rule.excludedDomains.contains(reg)) return false
            }
        }

        // 正资源类型约束：调用方若给不出类型（UNKNOWN），无法确认 -> 不命中（少拦优先）
        if (rule.resourceTypes.isNotEmpty()) {
            if (resourceType == ResourceType.UNKNOWN) return false
            if (resourceType !in rule.resourceTypes) return false
        }
        // 负资源类型约束
        if (rule.excludedResourceTypes.isNotEmpty() &&
            resourceType != ResourceType.UNKNOWN &&
            resourceType in rule.excludedResourceTypes
        ) {
            return false
        }
        return true
    }

    /** 请求是否第三方：请求主机的注册域 ≠ 页面主帧的注册域。 */
    private fun isThirdParty(requestHost: String, documentUrl: String): Boolean {
        val docHost = UriParts.parse(documentUrl)?.host ?: return false
        return registrableDomain(requestHost) != registrableDomain(docHost)
    }

    /**
     * 取注册域（eTLD+1 启发式）：取主机最后两段标签。IPv4/IPv6/localhost 原样返回（同主机判定）。
     * 不引入公共后缀列表——对广告拦截足够（广告域通常在注册域层级即与首方不同）。
     */
    private fun registrableDomain(host: String): String {
        if (host == LOCALHOST || host.contains(':')) return host
        if (host.all { it.isDigit() || it == '.' }) return host
        val labels = host.split('.')
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
    }

    /**
     * 当前站点可用的元素隐藏规则（CSS 选择器）。
     *
     * 每条规则的 [AdRule.pattern] 即 CSS 选择器；由平台侧 `evaluateJavascript` 注入 `<style>` 生效
     * （Android 实现见 `AndroidTabWebViewController.applyElementHiding`，并挂 `history`/`popstate` 钩子
     * 处理 SPA 站内路由）。
     */
    override fun elementRulesFor(siteId: String?): List<AdRule> =
        snapshot.elementRules.filter { it.siteId == null || it.siteId == siteId }

    /**
     * 从完整主机逐层向上取父域（`a.b.c` → `a.b.c`, `b.c`, `c`）。
     *
     * 这样匹配就是 O(标签数) 的哈希查找，而不是对每条规则做一次 `endsWith` 扫描——
     * 订阅 EasyList 之后规则数会到几万量级，扫描式实现撑不住。
     */
    private inline fun foreachParentDomain(host: String, action: (String) -> Unit) {
        var start = 0
        while (start < host.length) {
            action(host.substring(start))
            val nextDot = host.indexOf('.', start)
            if (nextDot < 0) break
            start = nextDot + 1
        }
    }

    private fun DomainEntry.matchesPath(parts: UriParts): Boolean =
        pathPrefix == null || parts.path.startsWith(pathPrefix)

    /** 例外规则编译：区分域名型、子串型与正则型。 */
    private fun compile(
        rule: AdRule,
        exceptionDomains: MutableSet<String>,
        exceptionSubstrings: MutableList<String>,
        exceptionRegexes: MutableList<Regex>,
    ) {
        if (rule.isRegex) {
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }
                .onSuccess { exceptionRegexes += it }
                .onFailure { logger.warn("忽略非法正则例外规则：id=${rule.id}") }
            return
        }
        val body = rule.pattern.trim().removePrefix("@@")
        if (body.isEmpty()) {
            logger.warn("忽略空白例外规则：id=${rule.id}")
            return
        }
        if (body.startsWith("||")) {
            val (host, _) = normalizeDomain(body) ?: return
            exceptionDomains += host
        } else {
            exceptionSubstrings += body.lowercase()
        }
    }

    /**
     * 把用户写的域名模式归一化成「裸主机 + 可选路径前缀」。
     *
     * 容忍多种写法：`||example.com^`、`example.com`、`https://ads.example.com/pre/`。
     *
     * @return `null` 表示模式非法（空、或主机过短）。**短主机必须拒绝**：
     *   形如 `com` 的规则会把所有 .com 站点拦死，属于灾难级误配置，宁可丢弃并告警。
     */
    private fun normalizeDomain(pattern: String): Pair<String, String?>? {
        var text = pattern.trim().removePrefix("||")
        text = text.substringAfter("://")
        val slash = text.indexOf('/')
        val rawHost = if (slash >= 0) text.substring(0, slash) else text.substringBefore('^')
        val path = if (slash >= 0) text.substring(slash).substringBefore('^') else null
        val host = rawHost.lowercase().trimEnd('.', '^').trim()

        if (host.isEmpty() || host.any { it == ' ' }) {
            logger.warn("忽略非法域名规则：$pattern")
            return null
        }
        // 至少两级（example.com），localhost 例外：本机调试与测试站点会用到
        if (!host.contains('.') && host != LOCALHOST) {
            logger.warn("拒绝过于宽泛的域名规则（需要至少两级域名）：$pattern")
            return null
        }
        return host to path
    }

    private companion object {

        const val LOCALHOST = "localhost"
    }
}
