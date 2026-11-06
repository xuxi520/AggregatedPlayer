package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.PlaybackMode

/**
 * 播放偏好的纯逻辑判定（阶段 3-B）。
 *
 * 把「站点覆盖全局」「什么时候该自动弹覆盖层」从 [org.aggregated.player.ui.AppViewModel]
 * 里抽出来：ViewModel 只负责执行 [Action]，不自己拼条件，便于 `commonTest` 覆盖边界。
 */
object PlaybackPreferenceResolver {

    /** 站点级覆盖优先；`null` 表示跟随全局。 */
    fun resolve(global: PlaybackMode, siteOverride: PlaybackMode?): PlaybackMode =
        siteOverride ?: global

    /** 对新出现的嗅探候选应采取的动作。 */
    enum class Action {

        /** 什么都不做，保持网页原生播放。 */
        NONE,

        /** 直接打开增强播放覆盖层。 */
        AUTO_OPEN,

        /** 先弹一条带动作的提示，等用户确认。 */
        ASK,
    }

    /**
     * @param hasPlayableCandidate 队列里是否存在「支持增强播放」且未被处理过的候选。
     * @param playbackActive 覆盖层已经打开：此时绝不叠加第二个播放器。
     */
    fun decide(
        mode: PlaybackMode,
        hasPlayableCandidate: Boolean,
        alreadyHandled: Boolean = false,
        playbackActive: Boolean = false,
    ): Action {
        if (!hasPlayableCandidate || alreadyHandled || playbackActive) return Action.NONE
        return when (mode) {
            PlaybackMode.WEBVIEW -> Action.NONE
            PlaybackMode.ENHANCED -> Action.AUTO_OPEN
            PlaybackMode.ASK -> Action.ASK
        }
    }
}
