package org.aggregated.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aggregated.player.platform.EmbeddedPlaybackRequest

/** 覆盖层里次级文字的透明度（深色背景上降低对比，避免喧宾夺主）。 */
private const val SECONDARY_TEXT_ALPHA = 0.7f

/**
 * 增强播放覆盖层的**共享外观框架**（阶段 3-B）。
 *
 * chrome（标题条、返回按钮、底部说明）在 commonMain 统一定义，平台只通过
 * [playerSurface] 提供播放器视图（Android = Media3 `PlayerView`，桌面阶段 5 = mpv 面板）。
 * 这样两端观感一致，且共享层完全不依赖任何播放器库。
 *
 * 整屏黑底 + 状态栏内边距：视频区域不被系统栏压住；「返回网页」是唯一出口，
 * 关闭后 WebView 原封不动（滚动位置、播放进度、标签保活状态都在）。
 */
@Composable
fun EnhancedPlayerFrame(
    request: EmbeddedPlaybackRequest,
    onClose: () -> Unit,
    playerSurface: @Composable () -> Unit,
) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("增强播放", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        request.title ?: domainOf(request.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onClose) {
                    Text("← 返回网页", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                playerSurface()
            }
            Text(
                "关闭即回到站点原生播放器，页面状态不会丢失",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = SECONDARY_TEXT_ALPHA),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 桌面端（阶段 5 前）的降级插槽：仍用同一套外观框架，明确告知而不是点了没反应。
 * 共享层不因此引入任何平台播放器依赖。
 */
@Composable
internal fun UnavailableEnhancedPlayer(
    request: EmbeddedPlaybackRequest,
    onClose: () -> Unit,
) {
    EnhancedPlayerFrame(request, onClose) {
        Text(
            "当前平台尚未接入增强播放引擎（桌面端阶段 5）",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}
