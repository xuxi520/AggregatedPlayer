package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 元素拾取时从页面回传的「单个祖先节点」信息。
 *
 * 由网页端 JS 收集（见 `AndroidTabWebViewController` 的拾取脚本），经 JSON 序列化后由
 * [ElementSelectorFactory] 拼成 CSS 选择器。
 *
 * @param tag 标签名（大写，如 `DIV`）。
 * @param id 元素 id；无则为 `null`。
 * @param classes 类名列表（已按空白拆分、去空）。
 * @param index 在当前父元素**所有元素子节点**中的序号（1-based，`nth-child` 语义）。
 * @param siblingCount 父元素下的元素子节点总数。
 */
@Serializable
data class PathStep(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val index: Int = 0,
    val siblingCount: Int = 0,
)

/**
 * 一次元素拾取的结果快照。
 *
 * [path] 从**目标元素自身**起、逐级向上到 `<html>`，[path][0] 永远是目标元素——
 * [ElementSelectorFactory] 据此生成「只挡该元素」与「连同父级」两类选择器。
 */
@Serializable
data class ElementInfo(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val path: List<PathStep> = emptyList(),
    /** 目标元素的渲染尺寸（CSS 像素，由 `getBoundingClientRect` 取），用于误杀保护的面积判断。 */
    val width: Int = 0,
    val height: Int = 0,
    /** 目标元素面积占视口面积的比例（0~1），>0.7 通常意味着误选了网页主体，需二次确认。 */
    val areaRatio: Float = 0f,
)

/**
 * 元素拾取结果在 UI 层的暂存形态：附带归属标签与站点，供 [org.aggregated.player.ui.AppViewModel]
 * 写入一条 `ELEMENT` 规则。
 */
data class ElementPickResult(
    val tabId: String,
    val siteId: String?,
    val info: ElementInfo,
)
