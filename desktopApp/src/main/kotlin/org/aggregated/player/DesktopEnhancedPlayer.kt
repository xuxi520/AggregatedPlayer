package org.aggregated.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.aggregated.player.core.createLogger
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.platform.EmbeddedPlaybackRequest
import org.aggregated.player.platform.web.DesktopPlayerWindow
import org.aggregated.player.ui.EnhancedPlayerFrame
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun DesktopEnhancedPlayer(request: EmbeddedPlaybackRequest, onClose: () -> Unit) {
    EnhancedPlayerFrame(request, onClose) {
        if (request.kind == MediaKind.HLS || request.kind == MediaKind.DASH) {
            Text("桌面端增强播放暂不支持 HLS/DASH，请用站点播放器或外部播放器", color = Color.White)
        } else {
            PlayerControls(request)
        }
    }
}
@Composable
private fun PlayerControls(request: EmbeddedPlaybackRequest) {
    val player = remember(request.url) { DesktopPlayerWindow(request, createLogger("DesktopPlayer")) }
    DisposableEffect(request.url) { onDispose { player.close() } }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // 播放器画面直接嵌在应用窗口里（OSR + SwingPanel），不再是独立窗口
        CefPanelHost(player.view, Modifier.fillMaxWidth().weight(1f))
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("进度条与全屏在播放器画面内操作；下方控制条与移动端一致", color = Color.White)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = { player.seekBy(-10.0) }) { Text("⏪") }
            TextButton(onClick = {
                playing = !playing
                if (playing) player.play() else player.pause()
            }) { Text(if (playing) "⏸" else "▶") }
            TextButton(onClick = { player.seekBy(10.0) }) { Text("⏩") }
            TextButton(onClick = { speedMenu = true }) { Text("${speed}x") }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                SPEEDS.forEach { r ->
                    DropdownMenuItem(text = { Text("${r}x") }, onClick = {
                        player.setRate(r); speed = r; speedMenu = false
                    })
                }
            }
            TextButton(onClick = {
                muted = !muted; player.setMuted(muted)
            }) { Text(if (muted) "🔇" else "🔊") }
            TextButton(onClick = {
                loop = !loop; player.setLoop(loop)
            }) { Text(if (loop) "🔁" else "↻") }
        }
        }
    }
}
