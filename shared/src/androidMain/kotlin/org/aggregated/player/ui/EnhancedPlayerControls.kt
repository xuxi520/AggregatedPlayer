package org.aggregated.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.Window
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val CONTROL_AUTO_HIDE_MS = 5000L
private const val HINT_AUTO_HIDE_MS = 800L
private const val STATE_POLL_INTERVAL_MS = 500L
private const val BRIGHTNESS_MIN = 0.01f
private const val BRIGHTNESS_MAX = 1f

/** `screenBrightness == -1` 表示跟随系统，此时手势从默认值起步。 */
private const val BRIGHTNESS_DEFAULT = 0.5f

/** 竖直滑动换算成亮度/音量变化的灵敏度（>1 让半屏滑动就能走完全程）。 */
private const val GESTURE_SENSITIVITY = 1.5f
private const val CONTROL_BAR_ALPHA = 0.55f
private const val HINT_OVERLAY_ALPHA = 0.6f

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val PERCENT_BASE = 100

/** 常用倍速档位（下拉菜单选择）。 */
@Suppress("MagicNumber")
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val DEFAULT_SPEED_INDEX = 2
/** 快进/快退步长（主流应用的 10 秒手感）。 */
private const val SEEK_STEP_MS = 10_000L
/** 控制条内小按钮的内边距（密集排布，避免一行溢出）。 */
private val CONTROL_BUTTON_PADDING = PaddingValues(horizontal = 6.dp, vertical = 2.dp)

/** 一次拖动锁定的功能：开始后不切换，避免手指斜着拖时亮度/音量/进度来回跳。 */
private enum class DragMode { BRIGHTNESS, VOLUME, SEEK }

/**
 * 递归解包 [ContextWrapper] 找宿主 [Activity]。
 *
 * 应用是单 Activity 架构，Composable 拿到的 context 几乎总是 Activity；
 * 找不到（如 Application 上下文）返回 null，调用方据此**降级**而不是崩溃：
 * 亮度手势与横竖屏按钮需要 Window / `requestedOrientation`，没有 Activity 就没有这些能力。
 */
internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * 播放器手势层（阶段 4-C，手机端高频操作）。
 *
 * 取代 `PlayerView` 自带 controller：那套在嵌入覆盖层里太简陋（无亮度/音量手势、
 * 无法切横屏）。约定与主流视频应用一致：
 * - 单击显示/隐藏控制条；双击播放/暂停；
 * - 左半屏竖直滑动调亮度（写 `Window.screenBrightness`，只影响本窗口）；
 * - 右半屏竖直滑动调音量（`AudioManager` STREAM_MUSIC）；
 * - 任意位置水平滑动 seek，浮层实时提示增减秒数。
 *
 * 无 Activity 上下文时亮度手势与横竖屏按钮自动降级，其余照常。
 */
@Composable
internal fun EnhancedPlayerGestures(
    exoPlayer: Player,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember { context.findHostActivity() }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var dragMode by remember { mutableStateOf<DragMode?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var startBrightness by remember { mutableFloatStateOf(BRIGHTNESS_DEFAULT) }
    var startVolume by remember { mutableIntStateOf(0) }
    var maxVolume by remember { mutableIntStateOf(1) }
    var seekStartPosition by remember { mutableLongStateOf(0L) }

    // 浮层提示 800ms 自动消失；控制条 5s 无操作自动隐藏
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_AUTO_HIDE_MS)
            hint = null
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(CONTROL_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .pointerInput(exoPlayer) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
                )
            }
            .pointerInput(activity, audioManager) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        dragStartY = offset.y
                        dragMode = resolveDragMode(offset.x < size.width / 2f, activity, audioManager)
                        when (dragMode) {
                            DragMode.BRIGHTNESS -> startBrightness = activity?.window
                                ?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: BRIGHTNESS_DEFAULT
                            DragMode.VOLUME -> audioManager?.let { am ->
                                maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                startVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                            }
                            DragMode.SEEK -> seekStartPosition = exoPlayer.currentPosition
                            null -> Unit
                        }
                    },
                    onDragEnd = { dragMode = null },
                    onDragCancel = { dragMode = null },
                    onDrag = { change, _ ->
                        change.consume()
                        val newHint = when (dragMode) {
                            DragMode.BRIGHTNESS -> applyBrightness(
                                activity?.window,
                                startBrightness,
                                change.position.y - dragStartY,
                                size.height.toFloat(),
                            )
                            DragMode.VOLUME -> audioManager?.let {
                                applyVolume(
                                    it, startVolume, maxVolume,
                                    change.position.y - dragStartY, size.height.toFloat(),
                                )
                            }
                            DragMode.SEEK -> applySeek(
                                exoPlayer, seekStartPosition,
                                change.position.x - dragStartX, size.width.toFloat(),
                            )
                            null -> null
                        }
                        if (newHint != null) hint = newHint
                    },
                )
            },
    ) {
        hint?.let { text ->
            Surface(
                color = Color.Black.copy(alpha = HINT_OVERLAY_ALPHA),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (controlsVisible) {
            PlayerControlBar(
                exoPlayer = exoPlayer,
                isLandscape = isLandscape,
                onToggleOrientation = {
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 亮度/音量手势需要 Window（无 Activity 时降级为 seek），音量手势还需要 AudioManager。 */
private fun resolveDragMode(
    leftHalf: Boolean,
    activity: Activity?,
    audioManager: AudioManager?,
): DragMode = when {
    leftHalf && activity != null -> DragMode.BRIGHTNESS
    audioManager != null -> DragMode.VOLUME
    else -> DragMode.SEEK
}

private fun applyBrightness(window: Window?, start: Float, deltaY: Float, heightPx: Float): String? {
    val attributes = window?.attributes ?: return null
    val next = (start - deltaY / heightPx * GESTURE_SENSITIVITY)
        .coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
    attributes.screenBrightness = next
    window.attributes = attributes
    return "亮度 ${(next * PERCENT_BASE).roundToInt()}%"
}

private fun applyVolume(
    audioManager: AudioManager,
    start: Int,
    max: Int,
    deltaY: Float,
    heightPx: Float,
): String {
    val delta = (-deltaY / heightPx * max * GESTURE_SENSITIVITY).roundToInt()
    val next = (start + delta).coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    return "音量 $next/$max"
}

/** 直播流（duration = `TIME_UNSET`）不支持 seek，返回 null 不出提示。 */
private fun applySeek(
    player: Player,
    startPositionMs: Long,
    deltaX: Float,
    widthPx: Float,
): String? {
    val duration = player.duration
    if (duration == C.TIME_UNSET || duration <= 0 || widthPx <= 0f) return null
    val target = (startPositionMs + deltaX / widthPx * duration)
        .coerceIn(0f, duration.toFloat()).toLong()
    player.seekTo(target)
    val deltaSec = ((target - startPositionMs) / MS_PER_SECOND).toInt()
    return if (deltaSec >= 0) "快进 ${deltaSec} 秒" else "快退 ${-deltaSec} 秒"
}

/**
 * 底部控制条（阶段 4-C 基础控制增强）：两行布局——
 * 上行时间 + 进度 Slider，下行按钮行（快退/播放暂停/快进、倍速、静音、循环、横竖屏）。
 *
 * 播放器状态用 500ms 轮询而不是事件监听：Media3 的回调碎片化（onIsPlayingChanged /
 * onPlaybackStateChanged / onPositionDiscontinuity 各管一块），轮询一次拿全，更不易漏。
 *
 * 新增能力（Media3 原生，零架构风险）：
 * - 倍速：下拉菜单选档位，经 `PlaybackParameters` 生效；
 * - 快进/快退 ±10s 按钮（直播流 duration 未知时禁用）；
 * - 静音：切 `Player.setVolume`（应用内轨道音量，与手势调的系统音量相互独立）；
 * - 循环：单条 `REPEAT_MODE_ONE`。
 */
@Composable
private fun PlayerControlBar(
    exoPlayer: Player,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(DEFAULT_SPEED_INDEX) }
    var muted by remember { mutableStateOf(false) }
    var repeatOne by remember { mutableStateOf(false) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    val canSeek = durationMs > 0
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            positionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            playing = exoPlayer.isPlaying
            delay(STATE_POLL_INTERVAL_MS)
        }
    }
    Surface(
        color = Color.Black.copy(alpha = CONTROL_BAR_ALPHA),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (canSeek) positionMs.toFloat() else 0f,
                    onValueChange = { exoPlayer.seekTo(it.toLong()) },
                    valueRange = 0f..if (canSeek) durationMs.toFloat() else 1f,
                    enabled = canSeek,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = { if (canSeek) exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)) },
                    enabled = canSeek,
                    contentPadding = CONTROL_BUTTON_PADDING,
                ) { Text("⏪", color = Color.White) }
                TextButton(
                    onClick = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
                    contentPadding = CONTROL_BUTTON_PADDING,
                ) { Text(if (playing) "⏸" else "▶", color = Color.White) }
                TextButton(
                    onClick = { if (canSeek) exoPlayer.seekTo((exoPlayer.currentPosition + SEEK_STEP_MS).coerceAtMost(durationMs)) },
                    enabled = canSeek,
                    contentPadding = CONTROL_BUTTON_PADDING,
                ) { Text("⏩", color = Color.White) }
                // 倍速：下拉菜单选档位，经 PlaybackParameters 生效（pitch 保持 1）
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }, contentPadding = CONTROL_BUTTON_PADDING) {
                        Text(
                            "${formatSpeed(SPEED_OPTIONS[speedIndex])}x",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        SPEED_OPTIONS.forEachIndexed { index, speed ->
                            DropdownMenuItem(
                                text = { Text("${formatSpeed(speed)}x") },
                                onClick = {
                                    exoPlayer.setPlaybackParameters(PlaybackParameters(speed))
                                    speedIndex = index
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                // 静音：切应用内轨道音量，与手势层调的系统音量相互独立
                TextButton(
                    onClick = {
                        muted = !muted
                        exoPlayer.setVolume(if (muted) 0f else 1f)
                    },
                    contentPadding = CONTROL_BUTTON_PADDING,
                ) { Text(if (muted) "🔇" else "🔊", color = if (muted) MaterialTheme.colorScheme.primary else Color.White) }
                // 循环：单条循环
                TextButton(
                    onClick = {
                        repeatOne = !repeatOne
                        exoPlayer.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    },
                    contentPadding = CONTROL_BUTTON_PADDING,
                ) { Text("🔁", color = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White) }
                TextButton(onClick = onToggleOrientation, contentPadding = CONTROL_BUTTON_PADDING) {
                    Text(if (isLandscape) "竖屏" else "横屏", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** `mm:ss`；超过一小时用 `h:mm:ss`。非正值统一显示 `00:00`。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = (ms / MS_PER_SECOND).toInt()
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 倍速标签：`1.0`→`1`、`1.25`→`1.25`，避免强迫症看到 `1.0x`。 */
private fun formatSpeed(speed: Float): String {
    val whole = speed.toInt().toFloat()
    return if (whole == speed) speed.toInt().toString() else speed.toString()
}
