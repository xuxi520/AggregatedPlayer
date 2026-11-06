package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.ElementInfo
import org.aggregated.player.domain.model.PathStep

/**
 * 由 [ElementInfo] 生成候选 CSS 选择器（设计文档 §4.3 的点级标记）。
 *
 * 纯逻辑、无平台依赖、可单测：JS 只负责把「长按处的元素」抽象成结构化的 [ElementInfo]，
 * 选择器怎么拼、按什么稳定性排序都留在这里，桌面端复用同一套。
 *
 * 稳定性排序：`#id` > 标签+类名组合 > 层级路径（`nth-child` 兜底）。动态类名（如 `vue-3f2a`、
 * Tailwind 的 `css-xxxx`）会被滤掉，避免选出一个下次渲染就失效的选择器。
 */
object ElementSelectorFactory {

    /** 末尾带长十六进制/数字 run 的类名，通常是框架生成的动态类，不适合做选择器。 */
    private val DYNAMIC_CLASS = Regex("-[0-9a-f]{4,}$", RegexOption.IGNORE_CASE)

    /** 末尾连续数字 run（≥3 位）：如 `ad-banner-12345` 的 `12345`，用于泛化为前缀匹配防 ID 变化。 */
    private val ID_NUMERIC_SUFFIX = Regex("\\d{3,}$")

    /** 标签+类名组合选择器最多拼几个类名：太多反而脆弱且可读性差。 */
    private const val MAX_CLASS_COMBINATION_COUNT = 3

    /** 一条候选选择器及其可读说明，供确认弹窗展示。 */
    data class SelectorCandidate(val selector: String, val label: String)

    /** 生成按稳定性排序的候选选择器（去重）。 */
    fun candidates(info: ElementInfo): List<SelectorCandidate> {
        val out = mutableListOf<SelectorCandidate>()
        val target = info.path.firstOrNull() ?: return out

        // 1) 目标 id：最稳，优先
        if (!target.id.isNullOrBlank()) {
            out += SelectorCandidate("#${target.id}", "按 id（最稳）")
            // 1.5) id 末尾带长数字 run（如 `ad-banner-12345`）：自动补一条前缀匹配候选，
            // 防刷新后 ID 里的数字变了导致规则失效（用户可在确认框里选更稳的那条）。
            val m = ID_NUMERIC_SUFFIX.find(target.id)
            if (m != null) {
                val prefix = target.id.substring(0, m.range.first)
                // 前缀非空且不是纯数字（纯数字 id 本身无意义）才生成
                if (prefix.isNotEmpty() && !prefix.all { it.isDigit() }) {
                    out += SelectorCandidate(
                        "${target.tag}[id^=\"$prefix\"]",
                        "按 id 前缀（防 ID 变化）",
                    )
                }
            }
        }

        // 2) 标签 + 稳定类名组合 / 单个稳定类名
        val stable = target.classes.filter { !DYNAMIC_CLASS.containsMatchIn(it) }
        if (stable.isNotEmpty()) {
            out += SelectorCandidate(
                "${target.tag}.${stable.take(MAX_CLASS_COMBINATION_COUNT).joinToString(".")}",
                "按 标签+类名",
            )
            out += SelectorCandidate(".${stable.first()}", "按 单个类名")
        }

        // 3) 层级路径（带类名，可读性好）
        buildPathSelector(info, useClasses = true)?.let {
            out += SelectorCandidate(it, "按 层级路径")
        }

        // 4) 纯位置路径（无类名时的兼容兜底）
        buildPathSelector(info, useClasses = false)?.let {
            out += SelectorCandidate(it, "按 层级路径(位置)")
        }

        return out.distinctBy { it.selector }
    }

    /** 目标父级的选择器（用于「连同同类型兄弟」时把挡截范围放大到父容器下的同类元素）。 */
    fun parentSelector(info: ElementInfo): String? {
        if (info.path.size < 2) return null
        return buildPathFrom(info.path.drop(1), useClasses = true)
    }

    /**
     * 解析最终写入规则的选择器：
     * - 不连带兄弟 → 直接用选定的候选；
     * - 连带兄弟 → `父级选择器 > 标签`，挡掉该父容器下所有同标签元素。
     */
    fun resolve(selector: String, withSiblings: Boolean, info: ElementInfo): String {
        if (!withSiblings) return selector
        val parent = parentSelector(info) ?: return selector
        return "$parent > ${info.path.first().tag}"
    }

    /** 从目标元素的父链构造选择器，遇到有 id 的祖先即以 `#id` 为根截断。 */
    private fun buildPathSelector(info: ElementInfo, useClasses: Boolean): String? =
        buildPathFrom(info.path.drop(1), useClasses)

    private fun buildPathFrom(path: List<PathStep>, useClasses: Boolean): String? {
        if (path.isEmpty()) return null
        val parts = mutableListOf<String>()
        for (step in path) {
            if (!step.id.isNullOrBlank()) {
                parts += "#${step.id}"
                break
            }
            parts += if (useClasses) {
                val stable = step.classes.filter { !DYNAMIC_CLASS.containsMatchIn(it) }
                if (stable.isNotEmpty()) {
                    "${step.tag}.${stable.take(2).joinToString(".")}"
                } else {
                    "${step.tag}:nth-child(${step.index})"
                }
            } else {
                "${step.tag}:nth-child(${step.index})"
            }
        }
        return parts.joinToString(" > ")
    }
}
