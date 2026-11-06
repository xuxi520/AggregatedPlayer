package org.aggregated.player.domain.usecase

import org.aggregated.player.domain.model.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** 播放偏好是纯逻辑，重点覆盖「站点覆盖全局」与「绝不叠加第二个播放器」两条边界。 */
class PlaybackPreferenceResolverTest {

    @Test
    fun `站点覆盖优先 空则跟随全局`() {
        assertEquals(
            PlaybackMode.ENHANCED,
            PlaybackPreferenceResolver.resolve(PlaybackMode.WEBVIEW, PlaybackMode.ENHANCED),
        )
        assertEquals(
            PlaybackMode.WEBVIEW,
            PlaybackPreferenceResolver.resolve(PlaybackMode.WEBVIEW, null),
        )
    }

    @Test
    fun `网页原生模式永不自动接管`() {
        assertEquals(
            PlaybackPreferenceResolver.Action.NONE,
            PlaybackPreferenceResolver.decide(PlaybackMode.WEBVIEW, hasPlayableCandidate = true),
        )
    }

    @Test
    fun `增强模式自动打开 询问模式先征询`() {
        assertEquals(
            PlaybackPreferenceResolver.Action.AUTO_OPEN,
            PlaybackPreferenceResolver.decide(PlaybackMode.ENHANCED, hasPlayableCandidate = true),
        )
        assertEquals(
            PlaybackPreferenceResolver.Action.ASK,
            PlaybackPreferenceResolver.decide(PlaybackMode.ASK, hasPlayableCandidate = true),
        )
    }

    @Test
    fun `无可播候选 已处理过 或覆盖层已打开时一律不动`() {
        assertEquals(
            PlaybackPreferenceResolver.Action.NONE,
            PlaybackPreferenceResolver.decide(PlaybackMode.ENHANCED, hasPlayableCandidate = false),
        )
        assertEquals(
            PlaybackPreferenceResolver.Action.NONE,
            PlaybackPreferenceResolver.decide(
                PlaybackMode.ENHANCED,
                hasPlayableCandidate = true,
                alreadyHandled = true,
            ),
        )
        assertEquals(
            PlaybackPreferenceResolver.Action.NONE,
            PlaybackPreferenceResolver.decide(
                PlaybackMode.ENHANCED,
                hasPlayableCandidate = true,
                playbackActive = true,
            ),
        )
    }
}
