package org.aggregated.player.platform.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 页面脚本的**结构性**回归测试。
 *
 * `evaluateJavascript` 不会把 JS 语法错误回传给 Kotlin：一旦 [AdBlockPageScript.ADBLOCK_BOOTSTRAP]
 * 拼接出括号错位，整个 IIFE 会静默失效——表现是「元素隐藏不生效、点击拾取没反应、点链接直接跳转」，
 * 且 Kotlin 侧一切正常（连告警都没有）。T1 把脚本上提到 commonMain 时曾因末尾多一个 `}` 踩过这个坑。
 *
 * 这里不解析 JS（commonMain 无 JS 引擎），而是做括号栈配对校验 + 关键片段断言，
 * 足以拦住这类拼写/顺序错误。改动 bootstrap 拼接务必保证本测试通过。
 */
class AdBlockPageScriptTest {

    @Test
    fun `bootstrap is a bracket-balanced IIFE`() {
        val js = AdBlockPageScript.ADBLOCK_BOOTSTRAP
        assertTrue(js.startsWith("(function(){"), "应以 IIFE 开头")
        assertTrue(js.endsWith("})();"), "应以 IIFE 调用结尾")
        assertBracketsBalanced(js)
    }

    @Test
    fun `apply script stays balanced after appending css`() {
        val js = AdBlockPageScript.buildApplyAdblockScript(".ad-banner{display:none!important}")
        assertBracketsBalanced(js)
        assertTrue(js.contains("window.__aggregatedApplyAdblock("), "应调用样式写入函数")
    }

    @Test
    fun `css with quotes is escaped without breaking the script`() {
        val js = AdBlockPageScript.buildApplyAdblockScript("a[title='ad']{display:none!important}")
        assertBracketsBalanced(js)
        assertTrue(js.contains("\\'ad\\'"), "单引号应被转义")
    }

    @Test
    fun `bootstrap installs capture-phase click picker and bridge callbacks`() {
        val js = AdBlockPageScript.ADBLOCK_BOOTSTRAP
        assertTrue(js.contains("document.addEventListener('click'"), "应注册 click 拾取监听")
        assertTrue(js.contains("},true)"), "click 监听必须是捕获阶段")
        assertTrue(js.contains("e.preventDefault()"), "标记模式下必须阻止链接跳转")
        assertTrue(js.contains("e.stopPropagation()"), "标记模式下必须阻止事件继续派发")
        assertTrue(js.contains("if(!window.__aggregatedMarkingEnabled)return;"), "应由标记开关门控")
        assertTrue(js.contains("aggregatedAdBlock.onElementPicked(JSON.stringify(info))"), "应回传拾取信息")
        assertTrue(js.contains("__aggregatedClearPickHighlight"), "应提供高亮清除入口")
        assertTrue(js.contains("__aggregatedPickInstalled"), "重复注入应幂等")
        assertTrue(js.contains("aggregatedAdBlock.onRouteChanged()"), "应有 SPA 路由钩子")
        assertTrue(js.contains("function pickEl("), "click 监听与遮罩命中共用一份拾取逻辑")
        assertTrue(js.contains("window.__aggregatedHitTest=function"), "应提供原生遮罩坐标命中入口")
        assertTrue(js.contains("document.elementFromPoint"), "遮罩命中靠 elementFromPoint 而非依赖页面事件")
        assertTrue(js.contains("window.__aggregatedShowHover=function"), "应提供实时预览框定位入口")
        assertTrue(js.contains("window.__aggregatedHideHover=function"), "应提供隐藏预览框入口")
        assertTrue(js.contains("addEventListener('mousemove'"), "桌面端应由 mousemove 驱动预览框")
        assertTrue(js.contains("getElementById('__aggHover'"), "预览框应是固定层 div（pointer-events:none，不挡交互）")
        assertTrue(js.contains("window.__aggregatedPickParent=function"), "应提供上钻父级接口（层级微调）")
        assertTrue(js.contains("window.__aggregatedPickChild=function"), "应提供下钻子级接口（层级微调）")
        assertTrue(js.contains("areaRatio:(r.width*r.height)"), "拾取应回传面积比例（误杀保护）")
        assertTrue(js.contains("width:Math.round(r.width)"), "拾取应回传元素宽度（误杀保护）")
        // C3 JS Hook：独立守卫、观察而不阻断、相对路径转绝对
        assertTrue(js.contains("__aggregatedMediaHookInstalled"), "C3 重复注入应幂等，避免层层包装")
        assertTrue(js.contains("window.fetch=function(input)"), "应包装 fetch 观察媒体 URL")
        assertTrue(js.contains("XMLHttpRequest.prototype.open=function"), "应包装 XHR.open")
        assertTrue(js.contains("aggregatedAdBlock.onMediaFound("), "C3 候选应回传宿主桥")
        assertTrue(js.contains("__aggFetch.apply(this,arguments)"), "观察后必须原样转发，绝不阻断请求")
        assertTrue(js.contains("document.createElement('a')"), "相对路径应解析成绝对 URL 再回传")
    }

    /**
     * 逐字符做括号栈配对；跳过单引号字符串字面量及其转义，避免把内容里的括号/引号误判。
     * 顺序错误（如提前闭合 if 块）会在这里被发现，而不只是数量相等。
     */
    private fun assertBracketsBalanced(js: String) {
        val stack = mutableListOf<Char>()
        var inString = false
        var i = 0
        while (i < js.length) {
            val c = js[i]
            when {
                c == '\\' && inString -> i++ // 跳过被转义的字符
                c == '\'' -> inString = !inString
                !inString && (c == '(' || c == '{' || c == '[') -> stack += c
                !inString && (c == ')' || c == '}' || c == ']') -> {
                    val expected = when (c) {
                        ')' -> '('
                        '}' -> '{'
                        else -> '['
                    }
                    assertTrue(stack.isNotEmpty(), "位置 $i 出现多余的 '$c'（栈已空）")
                    assertEquals(expected, stack.removeAt(stack.lastIndex), "位置 $i 括号不匹配：'$c'")
                }
            }
            i++
        }
        assertTrue(stack.isEmpty(), "有 ${stack.size} 个括号未闭合：$stack")
        assertTrue(!inString, "存在未闭合的字符串字面量")
    }
}
