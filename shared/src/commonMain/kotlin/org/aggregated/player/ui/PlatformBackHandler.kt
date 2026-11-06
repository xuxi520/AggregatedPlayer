package org.aggregated.player.ui

import androidx.compose.runtime.Composable

/**
 * 跨平台系统返回键拦截。
 *
 * - Android：委托给 `androidx.activity.compose.BackHandler`，接管实体/手势返回；
 * - 桌面（JVM）：无系统返回键，这里为空实现（退出由窗口关闭处理）。
 *
 * 统一在共享层 [MainScreen] 里决定返回优先级（设置/工具/侧栏 > 网页后退 > 主页面连按两次退出），
 * 避免各平台各写一套导致行为不一致。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
