package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 站点来源。
 *
 * 产品决策（覆盖早期 §8「不内置第三方站点」旧立场）：**内置站点为应用核心能力**，
 * 随版本迭代增删/替换，用户对内置站**无删除与自定义权**；用户自行添加的站则跨版本保留。
 * 持久化时靠本字段区分两类，[org.aggregated.player.domain.usecase.BuiltInSiteSync] 只增删 [BUILTIN]，绝不碰 [USER]。
 */
@Serializable
enum class SiteSource {

    /** 随应用版本内置的站点，用户不可删/改。 */
    BUILTIN,

    /** 用户在本机自行添加的站点，跨版本保留、可删除。 */
    USER,
}

/**
 * 一个站点条目（内置或用户添加）。
 *
 * 早期设计文档 §8 曾定「不内置第三方站点」，现按产品决策改为内置目录为核心能力（见 [SiteSource]）。
 * [id] 用本地生成的 [Ids]，不需要与服务端协商。
 *
 * 持久化位置：`sites.json`（§4.7）。
 */
@Serializable
data class Site(
    val id: String,

    /** 侧栏显示名。允许与站点标题不同，用户常用「A站影视」这类自取名。 */
    val name: String,

    /** 入口地址。打开标签页时以此为首屏 URL，不做在线校验（离线可用优先）。 */
    val url: String,

    /** 图标地址；为空时侧栏用 [name] 首字符生成的占位图标。 */
    val iconUrl: String? = null,

    /**
     * 站点级拦截开关。默认开启；个别站点存在误拦截（把视频分片当广告）时可单独关闭，
     * 这是「增强而非替代」原则下的必要逃生口（§2.3）。
     */
    val adBlockEnabled: Boolean = true,

    /**
     * 侧栏排序权重，越小越靠前。
     *
     * 用显式整数而不是「列表顺序即权重」：拖拽排序时只需改写少数几条记录，
     * 也让并发写入时不会出现整表重排互相覆盖的问题。
     */
    val sortIndex: Int = 0,

    /** 创建时间（epoch millis）。由仓储填充，模型自身不调用时钟，保证可单测。 */
    val createdAt: Long = 0L,

    /**
     * 来源标记。默认 [SiteSource.USER]：早期 `sites.json` 无此字段时旧记录降级为用户站，
     * 内置同步会以 URL 为键把它们重新归位为 [SiteSource.BUILTIN]（见 BuiltInSiteSync）。
     */
    val source: SiteSource = SiteSource.USER,

    /**
     * 站点级播放引擎覆盖；`null` 表示跟随全局 [AppSettings.defaultPlaybackMode]。
     * 建模沿用 `AdRule.siteId` 的「全局 vs 站点级」套路，全默认值保证旧 `sites.json` 兼容。
     */
    val playbackMode: PlaybackMode? = null,
) {

    /** 主机名，用于侧栏副标题与域名类规则的作用域判断。解析失败时为 `null`。 */
    val host: String?
        get() = UriParts.parse(url)?.host
}
