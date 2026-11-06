package org.aggregated.player.domain.model

/**
 * User-Agent 模式。
 *
 * 为什么需要用户可选：很多站点用 UA 里的 `Mobile` 词判定「这是手机」，
 * 从而给出 H5 播放器或另一套流地址；换成桌面 UA 有时能拿到更高码率的源。
 * 这是嗅探/播放成功率的实际变量，不是装饰性设置（设计文档 §4.4）。
 *
 * 放在 `domain.model` 而非 `platform`：UA 模式是共享层的状态概念（[SiteTab] 按标签持久化、
 * [BrowserTabController] 按标签切换），被 domain 与 platform 共同引用——
 * 留在 `platform` 会造成 domain→platform 的反向依赖。
 */
enum class UserAgentMode {

    /** 沿用系统 WebView 的 UA（移动端形态）。 */
    MOBILE,

    /**
     * 去掉移动标记，伪装桌面浏览器。
     *
     * 注意：这只影响 UA 字符串，视口宽度仍然是设备宽度，
     * 站点若按宽度响应式布局，效果不会等于真桌面。
     */
    DESKTOP_SIMULATE,
}
