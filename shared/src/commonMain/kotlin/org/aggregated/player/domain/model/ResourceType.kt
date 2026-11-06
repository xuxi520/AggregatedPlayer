package org.aggregated.player.domain.model

/**
 * 请求的资源类型（设计文档 §4.3 / 选项支持）。
 *
 * 与 ABP 的 `$script` / `$image` / `$stylesheet` 等修饰符一一对应，用于在 [AdRule] 上表达
 * 「只在某类资源上生效」的约束。取值分两类：
 * - [UNKNOWN]：调用方无法判断类型（如 Android `shouldInterceptRequest` 不提供类型），此时带正类型约束
 *   的规则无法被确认命中——为避免过度拦截，引擎**宁可少拦**（见 [org.aggregated.player.domain.usecase.AdRuleEngine]）。
 * - 其余为具体类型；`OTHER` 兜底无法归类的资源。
 */
enum class ResourceType {

    /** 调用方无法判断（缺上下文）。 */
    UNKNOWN,

    /** 顶层文档（HTML 主帧）。 */
    DOCUMENT,

    /** 图片。 */
    IMAGE,

    /** 样式表。 */
    STYLESHEET,

    /** 脚本。 */
    SCRIPT,

    /** 字体。 */
    FONT,

    /** 音视频等媒体。 */
    MEDIA,

    /** XMLHttpRequest / fetch。 */
    XMLHTTPREQUEST,

    /** 子文档（iframe）。 */
    SUBDOCUMENT,

    /** 探测 / 信标（ping / beacon）。 */
    PING,

    /** 弹窗。 */
    POPUP,

    /** 无法归类的其它资源。 */
    OTHER,
}
