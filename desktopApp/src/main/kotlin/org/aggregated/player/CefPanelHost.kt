package org.aggregated.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.aggregated.player.core.createLogger

/**
 * 把 CEF 的浏览器组件嵌进 Compose 窗口。
 *
 * 此项目用 **OSR 模式**（组件是 JOGL `GLCanvas`，渲染进单个 surface，不依赖 native 子窗口）。
 * windowed 模式在本机不可用：CEF 在 createBrowser 时就把原生窗口建成了顶层 HWND（Canvas 当时还未
 * displayable），之后无法重父化 → 浏览器起不来、整屏白 + native 弹窗。
 *
 * OSR 的两个历史坑与本文件的对策：
 * 1. `GLException: Unable to determine GraphicsConfiguration` —— GLCanvas 在**未 displayable** 的容器里
 *    `addNotify` 时 JOGL 拿不到 GraphicsConfiguration。故 attach 只在 host 已有真实尺寸时才挂载。
 * 2. `IllegalStateException: WindowClosingListener already set` —— 同一个 GLCanvas 被重复 `addNotify`
 *    （Compose Hot Reload 重挂时旧 removeNotify 没先触发）。故在 DisposableEffect 的 onDispose 里
 *    `removeAll()` 显式移除，保证重挂前 removeNotify 已触发、监听器已注销。
 *
 * ⚠️ 挂载失败（例：JOGL 在本机打不开 Windows 图形设备，`WindowsGraphicsDevice[handle 0x0]`）时
 * **不把异常抛给 AWT 事件线程**——否则会被弹成错误框、或变成 EDT 未捕获异常直接崩掉。
 * 改为捕获 → 记日志 → 通过 [onMountFailed] 通知上层退回外链浏览器模式。
 *
 * factory 永远返回同一个 remember 的 host；组件只在 attach 里按 component 幂等 removeAll+add。
 */
@Composable
fun CefPanelHost(
    component: Component,
    modifier: Modifier = Modifier,
    onMountFailed: (Throwable) -> Unit = {},
) {
    val host = remember { JPanel(null) }
    val latest = remember { AtomicReference<Component>() }
    val logged = remember { AtomicBoolean(false) }
    val failed = remember { AtomicBoolean(false) }
    latest.set(component)

    fun attach() {
        val c = latest.get() ?: return
        if (failed.get()) return
        // 等 host 真正有了尺寸再挂 GLCanvas，避开「尺寸为 0 的画布」
        if (host.width <= 0 || host.height <= 0) return
        if (host.componentCount != 1 || host.getComponent(0) !== c) {
            host.removeAll()
            try {
                host.add(c)
            } catch (error: Throwable) {
                // 见上方说明：接住并降级，别让 AWT 弹框 / 让 EDT 崩掉。
                failed.set(true)
                runCatching { host.removeAll() }
                createLogger("CefPanelHost")
                    .warn("[CefPanelHost] 挂载 CEF 组件失败，本次退回外链浏览器模式", error)
                onMountFailed(error)
                return
            }
        }
        c.setBounds(0, 0, host.width, host.height)
        host.revalidate()
        if (!logged.get()) {
            logged.set(true)
            createLogger("CefPanelHost").info("[CefPanelHost] attached ${c.javaClass.name}, ${host.width}x${host.height}")
        }
    }

    DisposableEffect(host) {
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = attach()
        }
        host.addComponentListener(listener)
        SwingUtilities.invokeLater { attach() }
        onDispose {
            host.removeComponentListener(listener)
            // 关键：先移除 GLCanvas，触发其 removeNotify → JOGL 注销 closing listener，
            // 这样 Hot Reload 重新挂载同一个 GLCanvas 时不会抛 WindowClosingListener already set。
            host.removeAll()
        }
    }
    LaunchedEffect(component) { SwingUtilities.invokeLater { attach() } }
    SwingPanel(factory = { host }, modifier = modifier)
}
