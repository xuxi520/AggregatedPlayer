package org.aggregated.player.domain.usecase

/**
 * 内置的第三方过滤列表（EasyList 风格子集），以常量打包（设计文档 §4.3 / T4）。
 *
 * 这是**第三方内容**（EasyList 规则的子集），不是我们自写的默认规则——它的作用是把「开箱即拦」的覆盖
 * 从手挑的几十个域名扩展到真实广告生态里最常见的一批网络与元素隐藏规则。
 *
 * 内置子集刻意只收 `||host^` / `@@||host^` / `##selector` 三种无 `$option` 的形式：覆盖绝大多数拦截价值、
 * 误伤风险最低，也避免 `$` 在源码字符串里引发模板歧义。**解析器与引擎本身已支持完整的 `$option` 与正则语法**
 * （见 [EasyListImporter] / [AdRuleEngine]），想用完整 EasyList 时把下载文本通过
 * `FilterListEngine(listText = ...)` 注入即可，无需改动共享层——这正是「先扩展纯 Kotlin」路线相对原生引擎的优势。
 *
 * 想扩充内置覆盖：直接往 [EASYLIST] 里追加 `||域名^` / `@@||域名^` / `##选择器` 即可，解析与索引全自动；
 * 若追加带 `$option` 的规则，Kotlin 三引号字符串里的 `$` 需写成 `${'$'}` 以免被当成字符串模板。
 */
object BundledFilterLists {

    const val EASYLIST = """
        ! AggregatedPlayer 内置过滤列表（精选 EasyList 风格子集，第三方内容）。
        ! 仅为「开箱即拦」提供广覆盖；完整规则由未来的原生引擎补齐。
        ! ---- 网络拦截（||host^） ----
        ||doubleclick.net^
        ||googlesyndication.com^
        ||googleadservices.com^
        ||adservice.google.com^
        ||adnxs.com^
        ||rubiconproject.com^
        ||criteo.com^
        ||criteo.net^
        ||taboola.com^
        ||outbrain.com^
        ||scorecardresearch.com^
        ||adsrvr.org^
        ||yieldmanager.com^
        ||advertising.com^
        ||adsymptotic.com^
        ||brightroll.com^
        ||openx.net^
        ||openx.com^
        ||pubmatic.com^
        ||mathtag.com^
        ||nexac.com^
        ||bluekai.com^
        ||demdex.net^
        ||crwdcntrl.net^
        ||flashtalking.com^
        ||2mdn.net^
        ||moatads.com^
        ||adform.net^
        ||adform.com^
        ||lijit.com^
        ||sonobi.com^
        ||triplelift.com^
        ||indexexchange.com^
        ||gumgum.com^
        ||spotx.tv^
        ||freewheel.tv^
        ||33across.com^
        ||biddingx.com^
        ||adgear.com^
        ||dedicatedmedia.com^
        ||smartadserver.com^
        ||adswizz.com^
        ||innovid.com^
        ||tremorhub.com^
        ||vidazoo.com^
        ||adblade.com^
        ||adbutler.com^
        ||adcolony.com^
        ||adscale.de^
        ||adspirit.de^
        ||adtech.de^
        ||adyoulike.com^
        ||c1exchange.com^
        ||contextweb.com^
        ||deltaprojects.com^
        ||districtm.ca^
        ||engage.bidswitch.net^
        ||exelator.com^
        ||fastclick.net^
        ||fmads.net^
        ||imrworldwide.com^
        ||katowishop.com^
        ||linkstorm.net^
        ||mediamath.com^
        ||mgid.com^
        ||netseer.com^
        ||optimatic.com^
        ||pixel.adsafesp.com^
        ||puser.net^
        ||quantserve.com^
        ||revcontent.com^
        ||serving-sys.com^
        ||simpli.fi^
        ||stickyadstv.com^
        ||synacor.com^
        ||teads.tv^
        ||trafficjunky.net^
        ||undertone.com^
        ||valueclickmedia.com^
        ||zemanta.com^
        ||adservice.google.com^
        ||pagead2.googlesyndication.com^
        ! ---- 例外（@@||host^，避免误伤合法资源） ----
        @@||googleapis.com^
        @@||gstatic.com^
        @@||w3.org^
        ! ---- 元素隐藏（##selector，全局） ----
        ##.ad-banner
        ##.adsbox
        ##.ad-placeholder
        ##.adsbygoogle
        ##.ad-container
        ##.advertisement
        ##.ad-leaderboard
        ##.ad-skyscraper
        ##.sponsor
        ##.ads
        ##.promoted
        ##.social-ad
        ##.native-ad
        ##.popup-ad
        ##.sticky-ad
        ##div[class*="ad-"]
        ##div[id*="ad-"]
        ##[data-ad="true"]
        ##.ad-slot
        ##.dfp-ad
    """
}
