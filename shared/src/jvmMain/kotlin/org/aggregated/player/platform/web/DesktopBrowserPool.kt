package org.aggregated.player.platform.web

import org.aggregated.player.core.Logger
import org.aggregated.player.platform.DefaultBrowserTabPool

/**
 * 桌面端浏览器实例池：复用 commonMain 的 [DefaultBrowserTabPool]，只换工厂（JCEF 实例），
 * 保活/淘汰等调度逻辑两端同一份实现。
 *
 * 返回具体类型而非接口：池同时实现了 `TabEffectExecutor`（标签效果的执行端），
 * 桌面端要把它注入 [org.aggregated.player.di.AppGraph]，静态类型需保留这一点。
 */
fun createDesktopBrowserPool(logger: Logger): DefaultBrowserTabPool =
    DefaultBrowserTabPool(
        factory = { tabId, _ -> JcefTabController(tabId, logger) },
        logger = logger,
    )

/**
 * CEF 原生二进制是否就位（`JCEF_BIN_DIR` 指向的目录存在）。
 * 桌面端据此决定「启用内嵌浏览」还是「退回外链浏览器模式」——
 * 没有 natives 时创建 CEF 实例会抛 `UnsatisfiedLinkError`，不能让它把应用带崩。
 */
fun jcefBinariesAvailable(): Boolean = JcefRuntime.binDir() != null

/**
 * 自检：初始化 CEF 运行时并创建一个浏览器实例，返回可读结果。
 *
 * 用途：桌面端启动前确认 natives 是否真的可用（`jcefBinariesAvailable()` 只看目录存在，
 * 而「能否真的初始化」要等 JNA 加载 `libcef.dll` 才知道）。失败时返回原因而不抛异常。
 */
// 自检必须兜住 Error 类异常：native 库缺失/版本不匹配只在运行期暴露（UnsatisfiedLinkError 是 Error 而非 Exception）
@Suppress("TooGenericExceptionCaught")
fun jcefSelfCheck(): String = try {
    // OSR 组件必须挂到 displayable 容器才渲染；自检只验证 CEF 运行时与 OSR 组件能创建
    val browser = JcefRuntime.createClient().createBrowser(ABOUT_BLANK, false, false)
    val view = browser.uiComponent
    // 回归验证：第二个 CefClient 必须能正常创建（多标签保活的前提）。
    // 曾因每次 createClient 都传 settings，第二个标签抛 IllegalStateException → GUI 黑屏 + Error 弹窗。
    val second = JcefRuntime.createClient().createBrowser(ABOUT_BLANK, true, true)
    second.close(true)
    val version = runCatching { JcefRuntime.version() }.getOrNull()
    browser.close(true)
    "OK ${version ?: "CEF 已启动"}; OSR view=${view.javaClass.name}; secondClient=ok"
} catch (e: Throwable) {
    "FAIL ${e.javaClass.simpleName}: ${e.message}"
}

private const val ABOUT_BLANK = "about:blank"
