package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.ResourceType
import org.aggregated.player.domain.model.UriParts

/**
 * 从请求 URL 启发式推断资源类型（设计文档 §4.3 / 选项支持）。
 *
 * 存在意义：Android `WebView.shouldInterceptRequest` 的 `WebResourceRequest` **不提供资源类型**，
 * 若一律传 [ResourceType.UNKNOWN]，`$image` / `$script` / `$stylesheet` 等正类型约束规则会全部落空，
 * 覆盖率大幅折损。桌面 JCEF 的 `CefRequest.resourceType` 是精确的，无需本推断。
 *
 * 依据 URL 路径扩展名做保守猜测——猜不出就返回 [ResourceType.UNKNOWN]，交由引擎「少拦优先」处理，
 * 绝不臆断（宁可漏拦一个广告，也不误拦主流程资源）。玄学路径（无扩展名 / 动态接口）无法归类属正常现象。
 */
object ResourceTypeGuesser {

    fun guess(url: String): ResourceType {
        val ext = UriParts.parse(url)?.fileExtension ?: return ResourceType.UNKNOWN
        return EXTENSIONS[ext] ?: ResourceType.UNKNOWN
    }

    /** 扩展名 → 资源类型。仅收录能明确归类的常见静态资源扩展名。 */
    private val EXTENSIONS: Map<String, ResourceType> = buildMap {
        listOf("js", "mjs", "cjs").forEach { put(it, ResourceType.SCRIPT) }
        put("css", ResourceType.STYLESHEET)
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "avif").forEach {
            put(it, ResourceType.IMAGE)
        }
        listOf("woff", "woff2", "ttf", "otf", "eot").forEach { put(it, ResourceType.FONT) }
        listOf("mp4", "webm", "mp3", "m4a", "aac", "ogg", "ts", "m3u8").forEach {
            put(it, ResourceType.MEDIA)
        }
        listOf("html", "htm").forEach { put(it, ResourceType.DOCUMENT) }
    }
}
