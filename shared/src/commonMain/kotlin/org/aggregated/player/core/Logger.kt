package org.aggregated.player.core

/**
 * 极简日志门面。
 *
 * 共享层不引入第三方日志库，避免把平台依赖带进 `commonMain`；
 * 具体输出方式（Logcat / stdout）由平台 actual 决定。
 *
 * 两条硬约束（对应设计文档 §2.3「可回退」）：
 * 1. 实现必须线程安全——`shouldInterceptRequest` 会并发调用；
 * 2. 实现内部不得抛异常——日志失败绝不能影响 WebView 主路径。
 */
interface Logger {

    /** 调试信息，release 构建中由平台实现决定是否输出。 */
    fun debug(message: String)

    /** 关键状态流转，例如标签由 BACKGROUND 提升为 ACTIVE。 */
    fun info(message: String)

    /** 可恢复的异常路径，例如规则文件解析失败后降级为空规则。 */
    fun warn(message: String, error: Throwable? = null)

    /** 需要人工排查的失败，例如外部播放器启动失败并回退原生。 */
    fun error(message: String, error: Throwable? = null)
}

/**
 * 创建以 [tag] 分组的数据日志器。
 *
 * [tag] 约定使用类名（如 `TabManager`），不要用函数名或中文，便于在 Logcat 里过滤。
 */
expect fun createLogger(tag: String): Logger
