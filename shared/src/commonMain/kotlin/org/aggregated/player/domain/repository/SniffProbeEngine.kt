package org.aggregated.player.domain.repository

/**
 * 嗅探增强的取文本端口：按嗅探到的请求头拉一次清单/文本内容。
 *
 * 嗅探的 C1/C2 通道只能看到 URL 与响应头，看不到响应体——但只有清单**内容**
 * 才能告诉用户分辨率、时长与估算体积。实现方必须：
 * - 复用嗅探到的 headers（Referer/Cookie/UA），否则防盗链站点直接 403；
 * - 严格限时限长（清单是文本，超限即放弃），失败静默返回 `null`；
 * - 结果只用于展示估算，不做任何缓存（清单地址带签名，时效极短）。
 */
interface SniffProbeEngine {

    /**
     * GET [url] 并把响应体按 UTF-8 读成文本；失败、非 2xx 或超限返回 `null`。
     * 实现需自兜异常，不得抛出（调用方在共享层协程里，无法感知平台异常类型）。
     */
    fun fetchText(url: String, headers: Map<String, String>): String?
}
