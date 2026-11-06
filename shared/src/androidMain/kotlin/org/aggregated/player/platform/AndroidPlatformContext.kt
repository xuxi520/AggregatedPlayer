package org.aggregated.player.platform

import android.content.Context

/**
 * Android 平台上下文持有者。
 *
 * 为什么需要它：`commonMain` 的 `expect fun createJsonDocumentStore()` 没有参数（共享层不该
 * 出现 `Context` 类型），而 Android 的文件目录必须从 `Context` 取。
 * 解法是在 `Application.onCreate()` 里注入一次，之后共享层按普通工厂使用（设计文档 §6.3）。
 *
 * 只保存 `applicationContext`：持有 Activity 会造成 WebView 与页面资源的连带泄漏，
 * 而这个对象的生命周期是整个进程。
 */
object AndroidPlatformContext {

    @Volatile
    private var applicationContext: Context? = null

    /** 由 `PlayerApplication.onCreate()` 调用。重复调用安全（同值覆盖）。 */
    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    val isInitialized: Boolean
        get() = applicationContext != null

    /**
     * 取应用上下文。
     *
     * 未初始化时**必须**抛异常而不是返回 null 或兜底值：静默降级会让「忘了在 Application 里初始化」
     * 变成难以定位的「配置莫名丢失」。异常信息直接写清修复方法。
     */
    fun requireContext(): Context =
        applicationContext ?: error(
            "Android 平台上下文未初始化：请在 PlayerApplication.onCreate() 中调用 AndroidPlatformContext.init(this)",
        )
}
