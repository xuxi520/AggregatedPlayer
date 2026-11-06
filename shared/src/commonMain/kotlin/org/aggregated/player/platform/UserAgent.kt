package org.aggregated.player.platform

import org.aggregated.player.domain.model.UserAgentMode

/**
 * 组装最终 User-Agent。纯函数，可单测。
 *
 * 规则：
 * 1. 追加应用标识 token，便于站点侧排查、也便于将来按 UA 灰度；
 *    token 用标准 `Name/Version` 形态，**不删除原有信息**——站点校验常依赖完整 UA 链。
 * 2. [UserAgentMode.DESKTOP_SIMULATE] 只剔掉独立的 `Mobile` 词，不做其它改写：
 *    Android WebView 的真实 UA 形如 `… Chrome/120.0.0.0 Mobile Safari/537.36`，
 *    `Mobile` 前面是**空格而不是分号**（拿分号当分隔符会漏剔），这里三种写法都照到。
 *    机型与系统信息（`Linux; Android 14; Pixel 8`）一律保留：它们是
 *    很多站点判断「可信客户端」的依据，删掉反而更容易被风控。
 *
 * @param appToken 形如 `AggregatedPlayer/1.0`；传 `null` 或空白时不追加。
 */
fun buildUserAgent(baseUserAgent: String, mode: UserAgentMode, appToken: String?): String {
    val stripped = when (mode) {
        UserAgentMode.MOBILE -> baseUserAgent
        UserAgentMode.DESKTOP_SIMULATE -> baseUserAgent
            // 三种真实写法：` Mobile Safari`（最常见）、开头的 `Mobile `、结尾孤零的 `Mobile`
            .replace(" Mobile Safari", " Safari", ignoreCase = false)
            .removePrefix("Mobile ")
            .let { ua -> if (ua.endsWith("Mobile")) ua.removeSuffix("Mobile").trimEnd() else ua }
    }

    val cleaned = stripped.trim()
    val token = appToken?.trim().orEmpty()
    return if (token.isEmpty()) cleaned else "$cleaned $token"
}
