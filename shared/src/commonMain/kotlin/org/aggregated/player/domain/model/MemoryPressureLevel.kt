package org.aggregated.player.domain.model

/**
 * 内存压力等级（设计文档 §4.1 / §4.2）。
 *
 * 平台侧把系统事件（Android `ComponentCallbacks2.onTrimMemory` 的各个 level）映射到这里，
 * 「该释放谁」的决策留在共享层 [org.aggregated.player.domain.usecase.TabManager]，
 * 避免容量策略散落到平台代码里。
 *
 * 放在 `domain.model` 而非 `platform`：它是共享层状态机的输入，必须可被 `domain` 直接引用，
 * 而不反向依赖 `platform`（架构约束见 §3.1）。
 */
enum class MemoryPressureLevel {

    /**
     * App 已不可见（`TRIM_MEMORY_UI_HIDDEN`）。
     *
     * 语义与其它两级完全不同：**不是内存紧张**，而是「没人看了」。
     * 平台实现应当在此刻对所有实例 `pause()` 并调用一次进程级 `pauseTimers()`；
     * 而共享层的淘汰策略在这一级**不释放任何实例**。
     */
    BACKGROUND,

    /** 中度压力：只提示，不释放（`Unbounded` 策略下的选择，见 §4.2 第 4 条）。 */
    MODERATE,

    /** 严重压力：可以释放非活动实例，绝不碰当前活动标签。 */
    CRITICAL,
}
