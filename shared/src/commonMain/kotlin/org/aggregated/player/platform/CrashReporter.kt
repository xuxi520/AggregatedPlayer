package org.aggregated.player.platform

/**
 * 崩溃诊断基建（跨端）：捕获进程内未捕获异常 → 落盘最近一份 → 设置页可查看/复制/清除。
 *
 * 为什么需要：下载闪退这类问题发生在 Service 主线程或引擎内部，本地开发环境没有 adb，
 * 用户真机复测就是唯一观测手段——把完整栈落到应用私有文件、在设置页给出一键复制，
 * 用户一次复测即可把现场带回来，而不是只看到「闪退了」三个字。
 */
expect fun installCrashReporter()

/** 最近一次崩溃的完整报告（时间戳 + 线程名 + 栈）；从未崩溃或已清除返回 `null`。 */
expect fun takeLastCrashReport(): String?

/** 用户复制完日志后手动清除，避免下次进入设置页重复提醒。 */
expect fun clearCrashReport()
