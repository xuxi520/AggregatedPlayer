package org.aggregated.player.ui

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.platform.EmbeddedPlaybackRequest
import org.aggregated.player.platform.Media3Offline

private const val ERROR_OVERLAY_ALPHA = 0.85f
private const val HINT_TEXT_ALPHA = 0.7f
private const val CARD_CORNER_DP = 12
private const val CARD_PADDING_DP = 16

/**
 * Android 增强播放覆盖层的播放器视图（路径 B 自封装；3-B 直链，4-B 起 HLS）。
 *
 * 为什么自封装而不用 mediamp：SniffedMedia 的 Referer/Cookie 必须透传到**每一个**分片请求，
 * 这里直接把同一个 [DefaultHttpDataSource.Factory] 注入 [DefaultMediaSourceFactory]（直链）或
 * [HlsMediaSource.Factory]（HLS 清单与分片），官方 Media3 API、100% 可控。
 * 这正是 Spike-0 里担心的「HLS MediaSource 层级注入」：路径 B 下它完全暴露，无需绕库抽象。
 *
 * 生命周期：ExoPlayer 按 `(url, headers)` remember，覆盖层关闭或请求变化时
 * [DisposableEffect] 释放实例，绝不泄漏解码器；PlayerView 通过 `update` 重新绑定新实例。
 * 日志绝不打印 headers（内含 Cookie），失败文案只展示播放器给出的错误摘要。
 */
@UnstableApi
@Composable
fun AndroidEnhancedPlayer(request: EmbeddedPlaybackRequest) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(request.url, request.headers, request.kind) {
        // 注册防盗链头：离线缓存与在线播放的清单/分片请求共用，按 host 注入（见 HeaderInjectingDataSourceFactory）
        Media3Offline.headerInjectingFactory.registerHeaders(request.url, request.headers)
        val mediaItem = MediaItem.fromUri(request.url)
        // 经 CacheDataSource：已离线缓存的分片零成本命中，未命中的在线拉取并回填——边播边缓存
        val dataSourceFactory = Media3Offline.cacheDataSourceFactory
        val mediaSource = when (request.kind) {
            MediaKind.HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "未知播放错误"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    // 控制交给自定义手势层（亮度/音量/seek/横屏，见 EnhancedPlayerGestures）：
                    // 原生 controller 只有播放暂停和进度，且与手势层的手势会互相抢事件
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )
        EnhancedPlayerGestures(exoPlayer, modifier = Modifier.fillMaxSize())
        if (errorMessage != null) {
            Surface(
                color = Color.Black.copy(alpha = ERROR_OVERLAY_ALPHA),
                shape = RoundedCornerShape(CARD_CORNER_DP.dp),
                modifier = Modifier.align(Alignment.Center).padding(CARD_PADDING_DP.dp),
            ) {
                Column(modifier = Modifier.padding(CARD_PADDING_DP.dp)) {
                    Text("播放失败", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    Text(
                        "可点右上角「返回网页」继续使用站点原生播放器",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = HINT_TEXT_ALPHA),
                    )
                }
            }
        }
    }
}
