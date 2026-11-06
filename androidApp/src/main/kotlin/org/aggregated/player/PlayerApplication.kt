package org.aggregated.player

import android.app.Application
import org.aggregated.player.platform.AndroidPlatformContext
import org.aggregated.player.platform.installCrashReporter
import org.aggregated.player.platform.web.AndroidBrowserHosts

/**
 * 应用进程入口。
 *
 * 职责只有两件事，顺序却是硬约束（设计文档 §6.3）：
 * 1. 注入平台上下文——共享层的文件存储依赖它，晚一步就会抛带修复指引的异常；
 * 2. 创建进程级浏览器宿主（WebView 实例池 + AppGraph）。
 *
 * 为什么把实例池挂在进程而不是 Activity：
 * 「切回标签还在」的前提是实例活得比 Activity 久。挂在 Activity 上时，
 * 配置变更或系统回收重建都会把全部后台标签打成冷标签，F2 直接失效。
 *
 * 相应地，内存压力事件也必须在这里转发：`ComponentCallbacks2` 在 Application 上
 * 一定会收到（比任何单个 Activity 都可靠），而降级决策由共享层 `TabManager` 做。
 */
class PlayerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidPlatformContext.init(this)
        // 崩溃捕获必须在平台上下文之后：写崩溃文件依赖它（下载闪退诊断依赖此基建）
        installCrashReporter()
        AndroidBrowserHosts.obtain(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 用 peek() 而不是 obtain()：这里只是转发事件，不该在事件回调里触发对象创建
        AndroidBrowserHosts.peek()?.runtime?.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AndroidBrowserHosts.peek()?.runtime?.onLowMemory()
    }
}
