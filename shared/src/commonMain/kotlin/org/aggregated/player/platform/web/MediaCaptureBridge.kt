package org.aggregated.player.platform.web

import org.aggregated.player.domain.usecase.SniffCandidate

/**
 * 媒体嗅探候选的宿主桥接：平台层把观察到的请求「丢进来」，共享层负责判定与入队。
 *
 * 为什么不复用 [AdBlockingBridge]：后者已经承担引擎、全局开关、站点上下文、标记模式、
 * 规则重注入与层级微调，职责边界不宜再被嗅探模糊；单独一个只有一个字段的桥，
 * 让控制器不必持有 `AppGraph`（池与工厂存在构造环，见 `AndroidBrowserRuntime` 的注入顺序）。
 *
 * 与 `AdBlockingBridge.engine` 同构：由 `AppGraph.create()` 在对象图建好后注入
 * `mediaSniffer::ingest`，平台控制器只做无依赖的被动调用。
 */
object MediaCaptureBridge {

    /** 收到一条候选流。未注入（如桌面端尚未接线）时静默丢弃，嗅探永远是增强而非必需。 */
    @Volatile
    var onCandidate: ((SniffCandidate) -> Unit)? = null
}
