package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind

/**
 * 首次启动写入的内置默认广告拦截规则（设计文档 §4.3 / T3）。
 *
 * 一份手工挑选的「常见第三方广告/追踪网络」域名子集（ABP `||host^` 语义，命中域名及全部子域），
 * 让应用开箱即拦最常见的横幅与贴片前广告，而不必等用户手动标记。
 *
 * 约定：
 * - 全部是**全局**规则（`siteId = null`）；站点级例外仍由用户在标记/设置里追加；
 * - 只收「第三方广告网络」，不碰站点自身的 CDN/播放域名，避免误伤内容加载；
 * - 这份种子是起点，不是完整 EasyList——完整覆盖交给 T4 的第三方引擎（adblock-rust / AdGuard）。
 *
 * id 用固定的 `seed-ad-<domain>`（而非时间戳），保证可重复、且不与用户规则（时间戳 id）撞键。
 * 仅当 `ad_rules.json` 不存在时才写入（见 [org.aggregated.player.domain.repository.AdRuleRepository.seedDefaults]）。
 */
object DefaultAdRules {

    /** 内置广告/追踪网络域名（裸域名，命中自身及子域）。 */
    private val DOMAINS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "rubiconproject.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "adsrvr.org",
        "yieldmanager.com",
        "advertising.com",
        "adsymptotic.com",
        "brightroll.com",
        "openx.net",
        "openx.com",
        "pubmatic.com",
        "mathtag.com",
        "nexac.com",
        "bluekai.com",
        "demdex.net",
        "crwdcntrl.net",
        "flashtalking.com",
        "2mdn.net",
        "moatads.com",
        "adform.net",
        "adform.com",
        "lijit.com",
        "sonobi.com",
        "triplelift.com",
        "indexexchange.com",
        "gumgum.com",
        "spotx.tv",
        "freewheel.tv",
        "33across.com",
        "biddingx.com",
        "adgear.com",
        "dedicatedmedia.com",
        "smartadserver.com",
        "adswizz.com",
        "innovid.com",
        "tremorhub.com",
        "vidazoo.com",
    )

    /** 固定种子时间，仅用于排序展示，不代表真实创建时刻。 */
    private const val SEED_EPOCH = 1_700_000_000_000L

    /** 生成默认规则列表（全局 DOMAIN 规则）。 */
    fun all(): List<AdRule> = DOMAINS.map { domain ->
        AdRule(
            id = "seed-ad-$domain",
            pattern = domain,
            kind = AdRuleKind.DOMAIN,
            siteId = null,
            enabled = true,
            createdAt = SEED_EPOCH,
        )
    }
}
