package org.aggregated.player.ui

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.platform.web.TabStateListener
import org.aggregated.player.platform.web.WebViewProfile
import org.aggregated.player.platform.web.rememberAndroidBrowserHost

/**
 * Android 端的应用入口。
 *
 * 与共享层 `App()` 的唯一差别就是**页面内容插槽换成了真实 WebView 容器**（§2.3「逻辑共享，能力原生」）：
 * 侧栏、标签条、嗅探面板全部复用共享 Compose，不在此文件重复实现任何业务判断。
 *
 * @param profile WebView 参数（UA 模式、自动播放策略等）。默认值即阶段 1 的验收配置，
 *   设置页接入后由用户覆盖。
 */
@Composable
fun AndroidApp(profile: WebViewProfile = WebViewProfile.DEFAULT) {
    val host = rememberAndroidBrowserHost(profile)

    // 页面状态回写：Application 级的实例池 → 共享层状态机。
    // 必须随组合解绑——relay 挂在宿主上活得比组合久，不解绑就是泄漏。
    DisposableEffect(host) {
        val unsubscribe = host.bindTabState(
            TabStateListener { tabId, url, title, _, _, _, navigation ->
                // 只回写状态、不落盘：URL 与标题变化极频繁，直写磁盘会把 IO 排满（§4.2 第 5 条）
                host.graph.tabManager.updateFromPlatform(tabId, url, title, navigation)
            },
        )
        onDispose { unsubscribe() }
    }

    // 捕获宿主 Activity，用于「连按两次返回键退出」时结束应用、回到桌面
    val activity = LocalContext.current as? ComponentActivity

    // 系统返回键的统一处理已下沉到共享层 MainScreen（借助 PlatformBackHandler）：
    // 设置/工具/侧栏优先收起 → 其次网页后退 → 最后主页面连按两次退出，不再由各端各写一套。
    // 「退出应用」通过 onExitApp 回调交还给平台执行，这里只负责结束 Activity。
    MainScreen(
        graph = host.graph,
        onExitApp = { activity?.finish() },
        enhancedPlayerOverlay = { request, onClose ->
            EnhancedPlayerFrame(request, onClose) { AndroidEnhancedPlayer(request) }
        },
    ) { session ->
        BrowserPageArea(host = host, activeTab = session.activeTab)
    }
}

/**
 * WebView 页面区域。
 *
 * 布局策略（§4.2 第 1 条）：**一个容器 + 多个实例常驻 + 切可见性**。
 * - 容器用 `remember` 持有，`AndroidView` 的 factory 不会因为标签切换而重建视图树；
 * - 各标签的 WebView 由池创建并 attach 到这个容器，切换只调 [android.view.View.setVisibility]，
 *   因此后台标签的实例、滚动位置、播放状态都留在内存里。
 *
 * 不做的事：不给每个标签单独一个 `AndroidView`。那样切换时 Compose 会走
 * 「移除旧节点 + 创建新节点」，视图被反复摘挂，反而更容易触发 WebView 的渲染异常。
 */
@Composable
private fun BrowserPageArea(
    host: org.aggregated.player.platform.web.AndroidBrowserHost,
    activeTab: SiteTab?,
    modifier: Modifier = Modifier,
) {
    // LocalContext 必须在组合期取；放进 remember 块里就不是 @Composable 上下文了
    val context = LocalContext.current
    val container = remember(host, context) { FrameLayout(context) }

    AndroidView(
        factory = { container },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            // 每次组合更新都绑定一次：容器可能因宿主重建而换掉，activeTab 也可能是新的
            host.runtime.bindHost(view, activeTab?.id)
        },
    )
}
