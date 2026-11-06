package org.aggregated.player.platform

/**
 * 桌面端 no-op：桌面发行版可直接看终端栈，暂无需落盘诊断（与下载/探测占位同策略）。
 */
actual fun installCrashReporter() = Unit

actual fun takeLastCrashReport(): String? = null

actual fun clearCrashReport() = Unit
