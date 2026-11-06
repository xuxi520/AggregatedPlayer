package org.aggregated.player

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.aggregated.player.core.createLogger
import org.aggregated.player.di.AppGraph
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.platform.DefaultBrowserTabPool
import org.aggregated.player.platform.web.createDesktopBrowserPool
import org.aggregated.player.platform.web.DesktopBrowserRuntime
import org.aggregated.player.platform.web.installDesktopExternalBrowser
import org.aggregated.player.platform.web.jcefBinariesAvailable
import org.aggregated.player.platform.web.jcefSelfCheck
import org.aggregated.player.ui.DesktopPageArea
import org.aggregated.player.ui.MainScreen

/**
 * 桌面（Windows 优先）入口。
 *
 * 阶段 5：桌面端**内嵌 JCEF（CEF 的 Java 绑定）**，与移动端共用同一套共享层 UI 与数据能力。
 * CEF 原生二进制不在 Maven 构件里，需用环境变量 `JCEF_BIN_DIR` 指向 jcef-bin 的 windows 目录；
 * 未提供时**自动退回外链浏览器模式**（页面区显示站点卡片 + 「在系统浏览器中打开」），
 * 应用其余能力（站点、规则、嗅探记录、下载、设置）不受影响。
 */
fun main() {
    // 自检模式：只验证 CEF natives 能否真正初始化（不开窗口），供部署前检查
    if (System.getenv("JCEF_SMOKE") == "1") {
        println("jcef self check: " + jcefSelfCheck())
        // CEF 会起原生线程，close browser 后 JVM 仍不退出——自检必须强制退出
        kotlin.system.exitProcess(0)
    }
    application {
        // 内嵌浏览可用时**不注册外链桥**：点击站点必须留在应用内。
        // 只有没放 CEF natives、确实退回外链模式时才注册。
        if (!jcefBinariesAvailable()) installDesktopExternalBrowser()
        Window(
            onCloseRequest = ::exitApplication,
            title = "站点聚合播放器",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
        ) {
            val pool = remember { if (jcefBinariesAvailable()) createDesktopBrowserPool(createLogger("DesktopPool")) else null }
            val graph = remember { AppGraph.create(tabExecutor = pool) }
            // 注入广告拦截桥接（引擎 + 站点上下文 + 广播回调）：与 Android 同款，只是拦截点不同
            remember(pool) {
                if (pool != null) DesktopBrowserRuntime.install(graph, pool, createLogger("DesktopAdBlock"))
            }
            // 冒烟模式：不依赖 activeTab（空态不会调用 pageContent），直接创建实例并挂载
            if (System.getenv("JCEF_AUTO_OPEN") == "1" && pool != null) {
                createLogger("Smoke").info("[Smoke] creating 2 CEF instances")
                val probe = "data:text/html,<body style='background:red'>JCEF OK</body>"
                val first = remember { pool.obtain("jcef-smoke-tab", probe).also { it.loadUrl(probe) } }
                remember { pool.obtain("jcef-smoke-tab-2", probe).also { it.loadUrl(probe) } }
                (first.platformView as? java.awt.Component)?.let { CefPanelHost(it, Modifier.fillMaxSize()) }
            } else {
                MainScreen(
                    graph,
                    enhancedPlayerOverlay = { request, onClose -> DesktopEnhancedPlayer(request, onClose) },
                    pageContent = { session -> DesktopArea(graph, pool, session) },
                )
            }
        }
    }
}

/**
 * 桌面端页面区：用 `CefPanelHost` 把 CEF 的 OSR 组件嵌进应用窗口（应用内浏览），
 * 没放 natives 时退回外链浏览器提示卡。
 *
 * 实例通过池的 `obtain` 取得——池保证「同一 tabId 只有一个实例、命中已有实例不重复加载」，
 * 因此这里取实例不会触发二次加载（真正的加载由 `TabEffect.Load` 统一发起）。
 */
@Composable
private fun DesktopArea(graph: AppGraph, pool: DefaultBrowserTabPool?, session: TabSession) {
    val tab = session.activeTab
    var url by remember(tab?.id) { mutableStateOf<String?>(null) }
    // 内嵌内核挂载失败（例：JOGL 打不开 Windows 图形设备）→ 退回外链浏览器模式
    var mountFailed by remember { mutableStateOf(false) }
    LaunchedEffect(tab?.siteId) {
        val siteId = tab?.siteId
        url = siteId?.let { id -> graph.siteRepository.loadAll().firstOrNull { it.id == id }?.url }
    }
    val component = remember(tab?.id, url) {
        val entry = url
        if (pool != null && tab != null && entry != null) {
            runCatching { pool.obtain(tab.id, entry).platformView as? java.awt.Component }.getOrNull()
        } else {
            null
        }
    }
    if (component != null && !mountFailed) {
        // 同一个 host 幂等换组件：切标签走 removeAll + add，避免重复 addNotify
        CefPanelHost(
            component,
            Modifier.fillMaxSize(),
            onMountFailed = {
                // 退回外链模式前必须补装外链桥：正常路径下只有「natives 缺失」才会装它，
                // 而这里是「natives 在但内核起不来」，不装的话降级页面的打开按钮会点了没反应。
                installDesktopExternalBrowser()
                mountFailed = true
            },
        )
    } else {
        DesktopPageArea(graph, session, embeddedBrowserReady = false)
    }
}
