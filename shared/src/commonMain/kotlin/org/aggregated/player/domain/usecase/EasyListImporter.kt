package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.ResourceType

/**
 * EasyList 格式过滤列表解析器（设计文档 §4.3 / T4）。
 *
 * 把第三方过滤列表（EasyList 风格）的文本解析成 [AdRule]，喂给 `AdRuleEngine` 实例构建独立索引。
 * 只实现「开箱即拦」需要的子集，刻意不碰原生引擎（adblock-rust / AdGuard）：
 * - 网络拦截：`||host^`、`||host/path`、`@@||host^`（例外）、裸域名、`/regex/`（正则）。
 * - `$option` 修饰符：`$third-party` / `$~third-party`、`$domain=a.com|b.com` / `$domain=~a.com`、
 *   `$script` / `$image` / `$stylesheet` / `$subdocument` / `$xmlhttprequest` / `$font` / `$media` /
 *   `$ping` / `$popup` / `$document` 及其 `~` 取反；其余（elemhide/important/match-case/cname…）忽略。
 * - 元素隐藏：`##selector`（全局）、`domain##selector`（站点级）。
 * - 注释（`!` / `[`）跳过。
 * 未识别或解析失败的单行会被静默跳过（过滤列表里混有复杂规则的常态），不影响其余规则。
 *
 * 解析出的 `$option` 约束由 [org.aggregated.player.domain.usecase.AdRuleEngine.decide] 在匹配时兑现；
 * 资源类型相关约束在调用方给不出类型（[ResourceType.UNKNOWN]，如 Android `shouldInterceptRequest`）时
 * 按「不命中」处理，以避免过度拦截。
 */
object EasyListImporter {

    /** 固定种子时间，仅用于排序展示。 */
    private const val SEED_EPOCH = 1_700_000_000_000L

    fun parse(text: String): List<AdRule> {
        val rules = mutableListOf<AdRule>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEachIndexed

            // 元素隐藏（cosmetic）：在 `##` 处切分；站点级形如 `domain##selector`
            val hash = line.indexOf("##")
            if (hash >= 0) {
                val domain = line.substring(0, hash).trim()
                val selector = line.substring(hash + 2).trim()
                if (selector.isEmpty()) return@forEachIndexed
                rules += AdRule(
                    id = "easylist-$index",
                    pattern = selector,
                    kind = AdRuleKind.ELEMENT,
                    siteId = domain.ifEmpty { null },
                    enabled = true,
                    createdAt = SEED_EPOCH,
                )
                return@forEachIndexed
            }

            val isException = line.startsWith("@@")
            val body = if (isException) line.removePrefix("@@") else line
            val dollar = body.indexOf('$')
            val opts = if (dollar >= 0) body.substring(dollar + 1) else ""
            val noOpts = if (dollar >= 0) body.substring(0, dollar) else body

            // 正则规则：/regex/（首尾斜杠、长度≥2）。ABP 正则极少内嵌 `$`，这里取首个 `$` 作为选项分隔。
            val isRegex = noOpts.startsWith("/") && noOpts.endsWith("/") && noOpts.length >= 2
            val regexBody = if (isRegex) noOpts.substring(1, noOpts.length - 1) else ""

            val kind = when {
                isRegex -> AdRuleKind.URL_SUBSTRING // 占位；AdRuleEngine 按 isRegex 路由，kind 不影响
                isException -> AdRuleKind.EXCEPTION
                noOpts.contains("||") || looksLikeHost(noOpts) -> AdRuleKind.DOMAIN
                else -> AdRuleKind.URL_SUBSTRING
            }
            val pattern = when (kind) {
                AdRuleKind.DOMAIN, AdRuleKind.EXCEPTION ->
                    noOpts.substringAfter("||").substringBefore("^").substringBefore("/").trim('|')

                else -> noOpts.trim('|')
            }
            val finalPattern = if (isRegex) regexBody else pattern
            if (finalPattern.isEmpty()) return@forEachIndexed

            val o = parseOptions(opts)
            rules += AdRule(
                id = "easylist-$index",
                pattern = finalPattern,
                kind = kind,
                siteId = null,
                enabled = true,
                createdAt = SEED_EPOCH,
                isRegex = isRegex,
                thirdParty = o.thirdParty,
                resourceTypes = o.resourceTypes,
                excludedResourceTypes = o.excludedResourceTypes,
                domainRestrictions = o.domainRestrictions,
                excludedDomains = o.excludedDomains,
            )
        }
        return rules
    }

    /** 解析 `$option` 片段（`third-party,domain=a.com|~b.com,image` …）为结构化约束。 */
    private fun parseOptions(opts: String): RuleOptions {
        var thirdParty: Boolean? = null
        val resourceTypes = mutableSetOf<ResourceType>()
        val excludedResourceTypes = mutableSetOf<ResourceType>()
        val domainRestrictions = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        opts.split(',').forEach { raw ->
            val opt = raw.trim()
            if (opt.isEmpty()) return@forEach
            when (opt) {
                "third-party" -> thirdParty = true
                "~third-party" -> thirdParty = false
                "domain" -> Unit // 形如 `$domain`（无值）无效，忽略
                else -> {
                    if (opt.startsWith("domain=")) {
                        opt.substringAfter("domain=").split('|').forEach { token ->
                            val d = token.trim()
                            if (d.isEmpty()) return@forEach
                            if (d.startsWith("~")) excludedDomains += d.removePrefix("~").lowercase()
                            else domainRestrictions += d.lowercase()
                        }
                    } else {
                        val neg = opt.startsWith("~")
                        val name = if (neg) opt.substring(1) else opt
                        mapResourceType(name)?.let { type ->
                            if (neg) excludedResourceTypes += type else resourceTypes += type
                        }
                        // 其它（elemhide / important / match-case / cname …）忽略
                    }
                }
            }
        }
        return RuleOptions(thirdParty, resourceTypes, excludedResourceTypes, domainRestrictions, excludedDomains)
    }

    /** ABP 资源类型修饰符 → [ResourceType]。 */
    private fun mapResourceType(name: String): ResourceType? = when (name) {
        "script" -> ResourceType.SCRIPT
        "image" -> ResourceType.IMAGE
        "stylesheet", "css" -> ResourceType.STYLESHEET
        "subdocument", "frame" -> ResourceType.SUBDOCUMENT
        "xmlhttprequest" -> ResourceType.XMLHTTPREQUEST
        "font" -> ResourceType.FONT
        "media" -> ResourceType.MEDIA
        "ping" -> ResourceType.PING
        "popup" -> ResourceType.POPUP
        "document" -> ResourceType.DOCUMENT
        "other" -> ResourceType.OTHER
        else -> null
    }

    /** 不含 `||` 也不含路径/通配/空格、且带点的串，视为裸域名。 */
    private fun looksLikeHost(s: String): Boolean =
        s.contains(".") && !s.contains("/") && !s.contains("*") && !s.contains(" ")
}

/** [EasyListImporter.parseOptions] 的临时载体。 */
private data class RuleOptions(
    val thirdParty: Boolean? = null,
    val resourceTypes: Set<ResourceType> = emptySet(),
    val excludedResourceTypes: Set<ResourceType> = emptySet(),
    val domainRestrictions: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
)
