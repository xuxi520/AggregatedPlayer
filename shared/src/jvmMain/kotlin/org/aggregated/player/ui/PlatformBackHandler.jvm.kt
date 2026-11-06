package org.aggregated.player.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 桌面端无系统返回键，此处不拦截（窗口关闭即退出）。
}
