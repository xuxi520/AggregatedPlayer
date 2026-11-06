package org.aggregated.player.platform

/**
 * 主线程检查器。
 *
 * 存在的理由：WebView 的 `onPause()` / `onResume()` / `loadUrl()` / `destroy()` 都要求在
 * **创建该 View 的线程**（通常是主线程）上调用，跨线程调用在真机上表现为偶发的
 * 渲染错乱或 native crash——这类问题离线复现极难，所以宁可在这里显式断言。
 *
 * 做成可注入接口而不是直接调 `Looper`：实例池的调度逻辑因此可以在 JVM 单测里验证
 * （设计文档 §4.2 第 3 条）。
 */
fun interface MainThreadChecker {

    /**
     * 断言当前线程合法。
     *
     * @param callSite 调用点标识（方法名即可），用于报错时能定位到具体入口。
     * @throws IllegalStateException 不在主线程时抛出——这里**不要**降级成日志，
     *   静默通过会把问题推到更难复现的渲染层。
     */
    fun assertMainThread(callSite: String)
}

/**
 * 不做检查的实现。
 *
 * 用于两处：JVM 单元测试（没有 Android Looper，也不关心线程约束），
 * 以及桌面端（阶段 5 接入 WebView2 时应换成 Swing EDT 检查，而不是继续用它）。
 */
object RelaxedMainThreadChecker : MainThreadChecker {

    override fun assertMainThread(callSite: String) {
        // 有意为空：见接口文档，测试环境不需要断言线程
    }
}
