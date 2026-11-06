package org.aggregated.player.ui

import androidx.compose.ui.unit.dp
import org.aggregated.player.domain.model.UriParts

/** 域名展示时最多保留的字符数；超出原样截断（解析失败也走这条）。 */
private const val DOMAIN_DISPLAY_LIMIT = 32

/** 从 URL 取展示用域名；解析失败时原样截断显示。 */
internal fun domainOf(url: String): String =
    UriParts.parse(url)?.host ?: url.take(DOMAIN_DISPLAY_LIMIT)

internal const val MARK_HINT_MIN = 5

internal val DrawerWidth = 300.dp
internal val SidePadding = 12.dp
internal val ItemSpacing = 6.dp
internal val FloatingControlMargin = 12.dp

/** 左边缘唤侧栏手势区的宽度与触发阈值。窄带 + 高阈值，避免误触。 */
internal val EdgeSwipeWidth = 20.dp
internal val EdgeSwipeThreshold = 48.dp

/** 操作气泡的直径与间距。 */
internal val BubbleSize = 40.dp
internal val BubbleGap = 8.dp

/** 闲置多久后自动淡化悬浮控件（毫秒）；展开或交互会重置计时。 */
internal const val IDLE_DIM_DELAY_MS = 3500L

/** 闲置淡化后的不透明度。 */
internal const val DimmedAlpha = 0.4f

/** 禁用图标的字形透明度。 */
internal const val DisabledAlpha = 0.35f

/** 主页面连按两次退出：两次返回键间隔小于该值（毫秒）视为「确认退出」。 */
internal const val BACK_TO_EXIT_MS = 2000L

/**
 * 嗅探列表的最大高度。
 *
 * 给固定值而不是 weight：弹层里的列表不能无限长——WebView 才是主体，
 * 嗅探面板只是偶尔要看一眼的辅助信息。
 */
internal val SnifferMaxHeight = 180.dp
