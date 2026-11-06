package org.aggregated.player.ui.floating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.aggregated.player.domain.model.AppSettings
import org.aggregated.player.domain.model.TriggerDock
import org.aggregated.player.domain.model.TriggerSize
import org.aggregated.player.ui.BubbleGap
import org.aggregated.player.ui.BubbleSize
import org.aggregated.player.ui.DimmedAlpha
import org.aggregated.player.ui.DisabledAlpha
import org.aggregated.player.ui.FloatingControlMargin
import org.aggregated.player.ui.IDLE_DIM_DELAY_MS

/** 触发器三种尺寸的圆形直径（dp），按 [TriggerSize] 取用。 */
private val TRIGGER_SMALL_DP = 40.dp
private val TRIGGER_MEDIUM_DP = 46.dp
private val TRIGGER_LARGE_DP = 54.dp

/** 各过渡动画时长（ms）。 */
private const val TRANSITION_FADE_MS = 140
private const val TRANSITION_BUBBLE_ENTER_MS = 160
private const val TRANSITION_BUBBLE_SLIDE_MS = 200
private const val TRANSITION_BUBBLE_EXIT_MS = 120

/** 气泡从触发器左/右定位时的「三分之一」分割比例（见下方 slide 偏移计算）。 */
private const val THIRD = 3

/** 气泡数量（站点/刷新/选择元素/当前页面工具/关闭），据此算总宽与间距数。 */
private const val BUBBLE_COUNT = 5

/**
 * 沉浸式悬浮控件（设计文档 §2.3）。可拖动 + 点击原位横向弹出纯图标气泡。
 *
 * - **常驻**：只一枚圆形触发器（☰），松手后吸附到最近的左右边缘（避免停在屏幕中间）；
 *   拖动结束把绝对坐标持久化；
 * - **展开**：点击触发器→原位横向弹出其它操作气泡（站点/刷新/关闭，纯图标无文字，省位置）；
 *   弹出方向在「展开那一刻」按触发器所在半区**冻结**，拖拽/布局过程中不再翻转，
 *   因此触发器槽位稳定、不会被重排 remount —— 这正是旧版「拖到中间就卡住」的根因；
 * - **设置驱动**：[AppSettings.floatingTriggerEnabled] 关闭则整体不渲染；[AppSettings.triggerDock]
 *   决定未拖动时的初始停靠角；[AppSettings.triggerSize] 决定尺寸；[AppSettings.idleDimmingEnabled]
 *   闲置自动淡化；[AppSettings.tapPageCollapseEnabled] 点击网页收起。
 *
 * 前进/后退不在这里：Q1 定档后站内导航由系统返回键驱动（见 `AndroidApp`）。
 */
@Composable
internal fun ImmersiveFloatingControl(
    settings: AppSettings,
    visible: Boolean,
    hasActiveTab: Boolean,
    pageTapTick: Long,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onCloseTab: () -> Unit,
    markingMode: Boolean = false,
    onToggleMarkingMode: (Boolean) -> Unit = {},
    pageToolCount: Int = 0,
    onShowPageTools: () -> Unit = {},
    onDragEnd: (offsetXDp: Float, offsetYDp: Float) -> Unit,
) {
    if (!settings.floatingTriggerEnabled) return

    val density = LocalDensity.current
    val leftDock = settings.triggerDock == TriggerDock.TOP_LEFT
    val triggerSizeDp = when (settings.triggerSize) {
        TriggerSize.SMALL -> TRIGGER_SMALL_DP
        TriggerSize.MEDIUM -> TRIGGER_MEDIUM_DP
        TriggerSize.LARGE -> TRIGGER_LARGE_DP
    }
    val triggerPx = with(density) { triggerSizeDp.toPx() }
    val marginPx = with(density) { FloatingControlMargin.toPx() }

    var expanded by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(false) }

    // 点击网页 / 控件隐藏时都收起气泡
    LaunchedEffect(pageTapTick) { if (settings.tapPageCollapseEnabled && pageTapTick > 0L) expanded = false }
    LaunchedEffect(visible) { if (!visible) expanded = false }
    // 闲置淡化：展开或收到交互后重置计时，静默一段时间再变淡
    LaunchedEffect(expanded, settings.idleDimmingEnabled, pageTapTick) {
        if (!settings.idleDimmingEnabled) {
            dimmed = false
            return@LaunchedEffect
        }
        dimmed = false
        delay(IDLE_DIM_DELAY_MS)
        dimmed = true
    }
    val layerAlpha = if (settings.idleDimmingEnabled && dimmed && !expanded) DimmedAlpha else 1f

    // 展开方向只在「展开那一刻」按触发器所在半区冻结；之后拖拽/布局都不再翻转，
    // 从而避免 Row 重排导致触发器 remount、拖拽手势在跨越中线时丢失（旧版「卡在中间」的根因）。
    var expandRight by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(TRANSITION_FADE_MS)),
        exit = fadeOut(tween(TRANSITION_FADE_MS)),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val minTx = marginPx
            val maxTx = (boxW - triggerPx - marginPx).coerceAtLeast(marginPx)
            val maxTy = (boxH - triggerPx - marginPx).coerceAtLeast(marginPx)

            // 绝对坐标（从左上角）：未拖动过(<0)时按停靠角给默认；拖动后落在哪就在哪
            var tx by remember(boxW, leftDock, triggerPx) {
                val stored = if (settings.triggerOffsetXDp >= 0f) {
                    with(density) { settings.triggerOffsetXDp.dp.toPx() }
                } else if (leftDock) {
                    minTx
                } else {
                    boxW - triggerPx - marginPx
                }
                mutableStateOf(stored.coerceIn(minTx, maxTx))
            }
            var ty by remember(boxH, triggerPx) {
                val stored = if (settings.triggerOffsetYDp >= 0f) {
                    with(density) { settings.triggerOffsetYDp.dp.toPx() }
                } else {
                    marginPx
                }
                mutableStateOf(stored.coerceIn(marginPx, maxTy))
            }

            // 展开瞬间冻结弹出方向（取此时触发器的实际半区）
            LaunchedEffect(expanded) {
                if (expanded) expandRight = tx + triggerPx / 2f < boxW / 2f
            }

            // 气泡总宽（3 枚 + 2 个间距），用于决定其相对触发器的水平摆放
            val bubblePx = with(density) { BubbleSize.toPx() }
            val bubbleGapPx = with(density) { BubbleGap.toPx() }
            val bubblesWidthPx = BUBBLE_COUNT.toFloat() * bubblePx + (BUBBLE_COUNT - 1).toFloat() * bubbleGapPx

            val dragModifier = Modifier.pointerInput(boxW, boxH) {
                detectDragGestures(
                    onDragEnd = {
                        // 松手吸附到最近的左右边缘：左半区→贴左，右半区→贴右，绝不留在屏幕中间
                        tx = if (tx + triggerPx / 2f < boxW / 2f) minTx else maxTx
                        // px → dp：直接用 density.density（px/密度）换算，避免依赖 toDp 扩展
                        onDragEnd(tx / density.density, ty / density.density)
                    },
                ) { change, drag ->
                    change.consume()
                    tx = (tx + drag.x).coerceIn(minTx, maxTx)
                    ty = (ty + drag.y).coerceIn(marginPx, maxTy)
                }
            }

            // 触发器：稳定槽位 + 绝对定位，不参与任何重排 → 拖拽过程永不被打断
            FloatingTrigger(
                sizeDp = triggerSizeDp,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                modifier = Modifier
                    .offset { IntOffset(tx.roundToInt(), ty.roundToInt()) }
                    .graphicsLayer { alpha = layerAlpha }
                    .then(dragModifier),
            )

            // 气泡：与触发器解耦，按冻结的 expandRight 从触发器左/右独立定位；绝不移动触发器槽位
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.graphicsLayer { alpha = layerAlpha },
                enter = fadeIn(tween(TRANSITION_BUBBLE_ENTER_MS)) + slideInHorizontally(tween(TRANSITION_BUBBLE_SLIDE_MS)) { if (expandRight) -it / THIRD else it / THIRD },
                exit = fadeOut(tween(TRANSITION_BUBBLE_EXIT_MS)) + slideOutHorizontally(tween(TRANSITION_FADE_MS)) { if (expandRight) -it / THIRD else it / THIRD },
            ) {
                val bx = if (expandRight) {
                    tx + triggerPx + bubbleGapPx
                } else {
                    tx - bubblesWidthPx - bubbleGapPx
                }
                val by = ty + (triggerPx - bubblePx) / 2f
                Row(
                    modifier = Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) },
                    horizontalArrangement = Arrangement.spacedBy(BubbleGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingBubble(glyph = "🗂") {
                        expanded = false
                        onOpenDrawer()
                    }
                    FloatingBubble(glyph = "⟳", enabled = hasActiveTab) {
                        expanded = false
                        onRefresh()
                    }
                    FloatingBubble(
                        glyph = "🎯",
                        enabled = hasActiveTab,
                        active = markingMode,
                    ) {
                        expanded = false
                        onToggleMarkingMode(!markingMode)
                    }
                    // 「当前页面工具」：点开是带「嗅探 / 已拦截」两个 Tab 的面板（不新增第 6 枚气泡，
                    // 否则左侧弹出时 bubblesWidthPx 会算错、气泡压到屏幕外；UA 切换已收进该面板）。
                    // 徽标汇总两类条目数。
                    Box {
                        FloatingBubble(glyph = "🛠", enabled = hasActiveTab) {
                            expanded = false
                            onShowPageTools()
                        }
                        if (pageToolCount > 0) {
                            Badge(
                                modifier = Modifier.align(Alignment.TopEnd),
                                containerColor = MaterialTheme.colorScheme.error,
                            ) { Text(pageToolCount.toString()) }
                        }
                    }
                    FloatingBubble(glyph = "✕", enabled = hasActiveTab) {
                        expanded = false
                        onCloseTab()
                    }
                }
            }
        }
    }
}

/** 悬浮触发器：一枚圆形主按钮，展开时换形为 ✕。拖动由外层通过 [modifier] 注入。 */
@Composable
private fun FloatingTrigger(
    sizeDp: androidx.compose.ui.unit.Dp,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(sizeDp),
        shape = CircleShape,
        color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (expanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .clickable(onClick = onToggle),
        ) {
            Text(text = if (expanded) "✕" else "☰", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 展开后的单个操作气泡：纯图标、无文字；禁用时降透明度且不响应点击，激活态高亮以示当前开启。 */
@Composable
private fun FloatingBubble(
    glyph: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(BubbleSize)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else DisabledAlpha),
            )
        }
    }
}
