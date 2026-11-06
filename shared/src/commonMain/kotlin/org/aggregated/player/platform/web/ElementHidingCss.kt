package org.aggregated.player.platform.web

import org.aggregated.player.domain.model.AdRule

/**
 * 元素隐藏（cosmetic）CSS 的拼装（**跨端共用**）。
 *
 * 原先这段逻辑是 Android 控制器的私有方法，桌面端要复用只能复制一份——两条线迟早漂移。
 * 上移到 commonMain 后两端共用同一份规则拼装，行为一致。
 */
object ElementHidingCss {

    /**
     * 把 `ELEMENT` 规则的选择器拼成一条 CSS 文本（每条 `selector{display:none!important}`）。
     *
     * 空规则列表返回空串，调用方据此「清空已注入样式」（与网络层同一套门控）。
     */
    fun build(rules: List<AdRule>): String {
        if (rules.isEmpty()) return ""
        val sb = StringBuilder()
        for (rule in rules) {
            val selector = rule.pattern.trim()
            if (selector.isEmpty()) continue
            sb.append(selector).append("{display:none!important}")
        }
        return sb.toString()
    }
}
