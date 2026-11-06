package org.aggregated.player.domain.model

/**
 * 后台标签的保活策略（设计文档 §4.1、Q2 定档）。
 *
 * 之所以做成密封类而不是一个 `maxLiveTabs: Int`：
 * 「不限制」和「限制为很大的数」在产品语义上完全不同——前者意味着
 * **任何时候切回都还在**，后者意味着存在一个用户能撞上的淘汰点。
 * 用类型区分比用一个哨兵整数（`Int.MAX_VALUE`）更难误用。
 */
sealed interface KeepAlivePolicy {

    /**
     * 保活实例数 = 标签数，**不做主动淘汰**。
     *
     * 这是本项目的默认策略（用户明确要求「切回去一定还在」）。代价是内存风险改由
     * 两条兜底承担：系统 `onTrimMemory(CRITICAL)` 时释放非活动标签、标签数超软阈值时提示。
     */
    data object Unbounded : KeepAlivePolicy

    /**
     * 存活实例数上限。用于低配机默认、JCEF 路线（无挂起 API）或用户显式开启的省内存模式。
     *
     * @param maxLiveTabs 至少为 1：设为 0 等价于「任何标签都不保活」，那是单 WebView 模式，
     *   应该用另一个策略类型表达，而不是靠一个会被除零/空转的数值。
     */
    data class LimitedTo(val maxLiveTabs: Int) : KeepAlivePolicy {

        init {
            require(maxLiveTabs >= 1) { "保活上限至少为 1，收到 $maxLiveTabs" }
        }
    }

    companion object {

        /** 默认策略。平台宿主若要收紧（低配机、JCEF）应显式传入 [LimitedTo]。 */
        val DEFAULT: KeepAlivePolicy = Unbounded
    }
}
