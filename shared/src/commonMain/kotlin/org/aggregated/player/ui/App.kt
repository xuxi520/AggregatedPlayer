package org.aggregated.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aggregated.player.di.AppGraph
import org.aggregated.player.core.currentTimeMillis
import org.aggregated.player.domain.model.ElementPickResult
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.usecase.ElementSelectorFactory
import org.aggregated.player.domain.usecase.NavigationAction
import org.aggregated.player.domain.usecase.forTab
import org.aggregated.player.domain.usecase.sortedForDisplay
import org.aggregated.player.ui.floating.ImmersiveFloatingControl
import org.aggregated.player.ui.panels.SiteListPanel
import org.aggregated.player.ui.panels.PageToolsSheetContent
import org.aggregated.player.ui.settings.SettingsScreen
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.platform.EmbeddedPlaybackRequest

/** 误杀保护阈值：选中元素面积占视口超过该比例即视为「疑似网页主体」，需显式确认。 */
private const val LARGE_AREA_RATIO = 0.7f

/** 面积比例换算成百分比文案的倍率。 */
private const val PERCENT_SCALE = 100

/**
 * 应用根组合（无平台宿主时的入口，桌面端使用）。
 *
 * Android 不走这里，而是用 `androidMain` 的 `AndroidApp()` 传入 WebView 页面插槽，
 * 这样共享层不需要 `expect` 一个 Composable，也不需要为了让平台插内容而开反射式注册点（§2.3）。
 */
@Composable
fun App() {
    val graph = remember { AppGraph.create() }
    MainScreen(graph)
}

/** 便捷重载：没有平台页面内容时显示占位区。 */
@Composable
fun MainScreen(graph: AppGraph) {
    MainScreen(graph) { session -> SessionPlaceholder(graph, session) }
}

/**
 * 主界面（沉浸式流媒体浏览壳）。
 *
 * 为「内容优先」的流媒体浏览做的取舍（本轮界面优化）：
 * - **无顶栏、无标签条、无底栏**：页面全出血，前进/后退交给系统返回键（统一由 `MainScreen` 内的 `PlatformBackHandler` 接管：设置/工具/侧栏 > 网页后退 > 主页面连按两次退出）；
 * - **悬浮控件**（左上）：默认只一枚圆形主按钮，点击展开小菜单做「站点列表 / 刷新 / 关闭标签」；
 *   随页面滚动淡出以实现全屏沉浸（滚动信号来自 [AppGraph.immersive]，桌面端恒不滚动故常驻）；
 * - **侧栏**：站点列表 + 添加站点，底部预留「设置」与「工具」入口。
 *
 * @param pageContent 页面内容插槽。**这里就是 WebView 的位置**：Android 侧传入
 * `AndroidPageArea`（单容器 + 多实例切可见性），桌面端在阶段 5 传入 SwingPanel 宿主。
 * 用插槽而不是在共享层写死，是为了让共享 UI 完全不依赖任何平台视图类型（§2.3）。
 * @param enhancedPlayerOverlay 增强播放覆盖层插槽：共享层只提供 [EnhancedPlayerFrame] 外观，
 * 播放器视图由平台注入（Android = Media3 `PlayerView`）。默认插槽给桌面端优雅降级。
 */
@OptIn(ExperimentalMaterial3Api::class)
// 大型组装式 Composable：206 行基本是布局堆叠而非逻辑复杂度，200 行阈值对它偏严，抑制比硬拆更清晰
@Suppress("LongMethod")
@Composable
fun MainScreen(
    graph: AppGraph,
    onExitApp: () -> Unit = {},
    enhancedPlayerOverlay: @Composable (EmbeddedPlaybackRequest, () -> Unit) -> Unit =
        { request, onClose -> UnavailableEnhancedPlayer(request, onClose) },
    pageContent: @Composable (TabSession) -> Unit,
) {
    MaterialTheme {
        // 协程作用域跟随组合：离开组合时自动取消，避免后台任务在页面销毁后继续改状态
        val scope = rememberCoroutineScope()
        val viewModel = remember(graph, scope) { AppViewModel(graph, scope) }
        LaunchedEffect(viewModel) { viewModel.bootstrap() }

        val uiState by viewModel.uiState.collectAsState()
        val session by graph.tabManager.session.collectAsState()
        val allSniffed by graph.mediaSniffer.media.collectAsState()
        // 下载任务与嗅探队列同款：状态归协调器持有，UI 只读 StateFlow（不进 AppUiState，避免两个真相源）
        // 设置页自行 collect 协调器（commonMain 同源依赖），此处不再重复收集
        val isScrolling by graph.immersive.isScrolling.collectAsState()
        val pageTapTick by graph.immersive.pageTapTick.collectAsState()

        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val snackbarHostState = remember { SnackbarHostState() }
        var showPageTools by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        val drawerOpen = drawerState.currentValue == DrawerValue.Open
        val activeTabId = session.activeTab?.id
        val activeSiteId = session.activeTab?.siteId
        // 队列是全局内存态，这里只把当前标签的候选交给面板：站点↔标签 1:1，
        // 避免切站点后旧站点的临时流地址混进来（带签名的地址点了必然失败）。
        val sniffed = remember(allSniffed, activeTabId) { allSniffed.forTab(activeTabId).sortedForDisplay() }
        // 「当前页面工具」面板的两类条目：本站 ELEMENT 规则 + 本站嗅探结果，徽标只数这两类
        val blockedRules = uiState.rules.filter { it.kind == AdRuleKind.ELEMENT && it.siteId == activeSiteId }
        val pageToolCount = blockedRules.size + sniffed.size

        // 连按两次退出：记录上次返回时间戳，间隔内再次返回才真正退出
        var lastBackToExitMillis by remember { mutableStateOf(0L) }

        // 统一返回策略（跨平台，借助 PlatformBackHandler 接管系统返回键）：
        // 设置页 / 当前页面工具面板 / 侧栏优先收起 → 其次网页后退 → 最后主页面连按两次退出。
        // enabled 恒为 true：所有返回都由这里接管，避免各层各写一套或漏接导致直接退出应用。
        PlatformBackHandler(enabled = true) {
            when {
                // 覆盖层是最上层的模态，返回键必须先关它，不能穿透到设置/网页后退
                uiState.activePlayback != null ->
                    viewModel.onIntent(AppIntent.CloseEnhancedPlayback)
                showSettings -> showSettings = false
                showPageTools -> showPageTools = false
                drawerOpen -> scope.launch { drawerState.close() }
                session.activeTab?.navigation?.canGoBack == true ->
                    viewModel.onIntent(AppIntent.Navigate(NavigationAction.BACK))
                else -> {
                    val now = currentTimeMillis()
                    if (now - lastBackToExitMillis < BACK_TO_EXIT_MS) {
                        onExitApp()
                    } else {
                        lastBackToExitMillis = now
                        scope.launch { snackbarHostState.showSnackbar("再按一次返回键退出应用") }
                    }
                }
            }
        }

        // 一次性提示改用 Snackbar：出现→自动消失→顺带清空状态，不再占用固定布局行
        LaunchedEffect(uiState.message) {
            val message = uiState.message ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(message)
            viewModel.onIntent(AppIntent.DismissMessage)
        }

        // 标记元素后的「撤销」Snackbar：带动作按钮，处置完清除待撤销 id
        LaunchedEffect(uiState.pendingUndoRuleId) {
            val undoId = uiState.pendingUndoRuleId ?: return@LaunchedEffect
            val result = snackbarHostState.showSnackbar(
                message = "已隐藏该元素",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            viewModel.onIntent(AppIntent.ClearUndoRule)
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onIntent(AppIntent.DeleteAdRule(undoId))
            }
        }

        // 「每次询问」偏好：发现可播资源时先征询，用户点「播放」才开覆盖层
        LaunchedEffect(uiState.pendingPlaybackAsk) {
            val request = uiState.pendingPlaybackAsk ?: return@LaunchedEffect
            val result = snackbarHostState.showSnackbar(
                message = "发现可播放资源：${request.title ?: domainOf(request.url)}，使用增强播放？",
                actionLabel = "播放",
                duration = SnackbarDuration.Short,
            )
            viewModel.onIntent(AppIntent.DismissPlaybackAsk)
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onIntent(AppIntent.AcceptPlaybackAsk)
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // 关闭态禁用自带手势（避免页面滚动、拖悬浮控件时误开），开启态恢复（才能拖拽/点遮罩关闭）。
            // 打开侧栏仍由「☰ 触发器 + 左边缘窄带」两个明确入口负责。
            gesturesEnabled = drawerState.currentValue == DrawerValue.Open,
            drawerContent = {
                SiteListPanel(
                    sites = uiState.sites,
                    activeSiteId = session.activeTab?.siteId,
                    loading = uiState.isBootstrapping,
                    tabCount = session.tabs.size,
                    ruleCount = uiState.activeRuleCount,
                    warnMemory = graph.tabManager.shouldWarnAboutMemory(),
                    tabBySite = remember(session.tabs) {
                        session.tabs
                            .mapNotNull { tab -> tab.siteId?.let { it to tab } }
                            .toMap()
                    },
                    onOpenSite = {
                        scope.launch { drawerState.close() }
                        viewModel.onIntent(AppIntent.OpenSite(it))
                    },
                    onAddSite = { name, url -> viewModel.onIntent(AppIntent.AddSite(name, url)) },
                    onDeleteSite = { viewModel.onIntent(AppIntent.DeleteUserSite(it)) },
                    onStopTab = { viewModel.onIntent(AppIntent.StopTab(it)) },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 无活动标签时展示空状态页（也是将来冷启动的第一屏形态），绝不自动重启某个标签
                if (session.activeTab == null) {
                    EmptySessionState(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 页面全出血：不加任何 insets，WebView 一直铺到状态栏/导航栏下，沉浸优先
                    pageContent(session)
                }

                // 左边缘窄带：仅在此区域内明确向右滑才唤起侧栏（悬浮控件在上层优先消费，不会误触）
                LeftEdgeSwipeZone(
                    enabled = uiState.settings.edgeSwipeDrawerEnabled && drawerState.currentValue == DrawerValue.Closed,
                    onOpen = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.CenterStart),
                )

                ImmersiveFloatingControl(
                    settings = uiState.settings,
                    visible = !isScrolling || drawerOpen,
                    hasActiveTab = session.activeTab != null,
                    pageTapTick = pageTapTick,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onRefresh = { viewModel.onIntent(AppIntent.Navigate(NavigationAction.RELOAD)) },
                    onCloseTab = {
                        session.activeTab?.let { tab ->
                            // 标签关了，它的嗅探结果就成了死地址（带签名的临时 URL），留着只会误导
                            graph.mediaSniffer.clearForTab(tab.id)
                            viewModel.onIntent(AppIntent.CloseTab(tab.id))
                        }
                    },
                    markingMode = uiState.markingMode,
                    onToggleMarkingMode = { viewModel.onIntent(AppIntent.SetMarkingMode(it)) },
                    pageToolCount = pageToolCount,
                    onShowPageTools = { showPageTools = true },
                    onDragEnd = { xDp, yDp ->
                        viewModel.onIntent(
                            AppIntent.UpdateSettings(uiState.settings.copy(triggerOffsetXDp = xDp, triggerOffsetYDp = yDp)),
                        )
                    },
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        if (showPageTools) {
            ModalBottomSheet(onDismissRequest = { showPageTools = false }) {
                PageToolsSheetContent(
                    sniffed = sniffed,
                    blockedRules = blockedRules,
                    session = session,
                    platformName = graph.platformDisplayName,
                    ruleCount = uiState.activeRuleCount,
                    busy = uiState.isEnhancedPlaying,
                    onPlay = { viewModel.onIntent(AppIntent.OpenEnhancedPlayback(it)) },
                    onDownload = { viewModel.onIntent(AppIntent.StartDownload(it)) },
                    onPush = { viewModel.onIntent(AppIntent.PushToExternalPlayer(it)) },
                    onMarkPattern = { viewModel.onIntent(AppIntent.MarkUrlPattern(it)) },
                    onDeleteRule = { viewModel.onIntent(AppIntent.DeleteAdRule(it)) },
                    onToggleRule = { id, en -> viewModel.onIntent(AppIntent.ToggleAdRule(id, en)) },
                    markingMode = uiState.markingMode,
                    onToggleMarkingMode = { viewModel.onIntent(AppIntent.SetMarkingMode(it)) },
                    userAgentMode = session.activeTab?.userAgentMode ?: UserAgentMode.MOBILE,
                    onSetUserAgentMode = { viewModel.onIntent(AppIntent.SetUserAgentMode(it)) },
                )
            }
        }

        if (showSettings) {
            SettingsScreen(
                settings = uiState.settings,
                onBack = { showSettings = false },
                onChange = { viewModel.onIntent(AppIntent.UpdateSettings(it)) },
                sites = uiState.sites,
                rules = uiState.rules,
                onToggleSiteAdBlock = { id, en -> viewModel.onIntent(AppIntent.ToggleSiteAdBlock(id, en)) },
                onDeleteRule = { id -> viewModel.onIntent(AppIntent.DeleteAdRule(id)) },
                onToggleRule = { id, en -> viewModel.onIntent(AppIntent.ToggleAdRule(id, en)) },
                onAddRule = { p, k -> viewModel.onIntent(AppIntent.AddAdRule(p, k)) },
                onSetSitePlaybackMode = { id, mode -> viewModel.onIntent(AppIntent.SetSitePlaybackMode(id, mode)) },
                downloadCoordinator = graph.downloadCoordinator,
                onClearCache = { viewModel.onIntent(AppIntent.ClearOfflineCache) },
            )
        }

        uiState.pendingElementPick?.let { pick ->
            ElementPickDialog(
                pick = pick,
                onConfirm = { selector, withSiblings -> viewModel.onIntent(AppIntent.ConfirmElementRule(selector, withSiblings)) },
                onDismiss = { viewModel.onIntent(AppIntent.DismissElementPick) },
                onPickParent = { viewModel.onIntent(AppIntent.PickElementParent) },
                onPickChild = { viewModel.onIntent(AppIntent.PickElementChild) },
            )
        }

        // 覆盖层最后声明：Compose 中后声明者在上层，天然盖住设置页 / 工具面板 / 确认框，
        // 不必给每个浮层都补互斥条件；返回键也已优先关它。
        uiState.activePlayback?.let { request ->
            enhancedPlayerOverlay(request) { viewModel.onIntent(AppIntent.CloseEnhancedPlayback) }
        }
    }
}

/**
 * 元素标记确认弹窗：展示由 [ElementSelectorFactory] 生成的候选选择器（按稳定性排序），
 * 让用户选择粒度（只挡该元素 / 连同同类型兄弟），确认后写入一条 `ELEMENT` 规则。
 */
@Composable
private fun ElementPickDialog(
    pick: ElementPickResult,
    onConfirm: (selector: String, withSiblings: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPickParent: () -> Unit = {},
    onPickChild: () -> Unit = {},
) {
    val candidates = remember(pick) { ElementSelectorFactory.candidates(pick.info) }
    var selected by remember(pick) { mutableStateOf(candidates.firstOrNull()?.selector ?: "") }
    var withSiblings by remember { mutableStateOf(false) }
    // 误杀保护：元素面积占视口 >70% 时大概率是网页主体，确认框给出醒目警告并要求显式确认
    val largeArea = pick.info.areaRatio > LARGE_AREA_RATIO
    val summary = buildString {
        append(pick.info.tag)
        pick.info.id?.let { append("#$it") }
        if (pick.info.classes.isNotEmpty()) append(" · ${pick.info.classes.size} 个类名")
        pick.siteId?.let { append(" · 站点 $it") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, withSiblings) }, enabled = selected.isNotEmpty()) {
                Text(if (largeArea) "仍要拦截" else "隐藏该元素")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("标记元素以隐藏") },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.bodySmall)
                if (largeArea) {
                    Text(
                        "⚠ 该元素约占屏幕 ${(pick.info.areaRatio * PERCENT_SCALE).toInt()}%，疑似网页主体，隐藏后可能大片空白。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text("选择一个选择器（越靠上越精确）：", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    candidates.forEach { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = c.selector }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = selected == c.selector, onClick = { selected = c.selector })
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(c.selector, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(c.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                // 层级微调：移动端手指粗容易点到子元素，这里可上/下钻一级再确认
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = onPickParent) { Text("⬆ 父级") }
                    OutlinedButton(onClick = onPickChild) { Text("⬇ 子级") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = withSiblings, onCheckedChange = { withSiblings = it })
                    Text("连同同类型兄弟元素一起隐藏", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

/**
 * 左边缘唤侧栏的窄带手势区。
 *
 * 只在屏幕最左侧一条窄带内、且是**明确的水平向右拖拽**时才回调 [onOpen]：
 * 这样既不会像 `ModalNavigationDrawer` 自带手势那样被页面滚动的水平抖动误触，
 * 也不会与悬浮控件的拖拽抢事件（控件作为更上层兄弟优先命中）。
 * 关闭时（[enabled] = false）直接不渲染，零开销。
 */
@Composable
private fun LeftEdgeSwipeZone(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val thresholdPx = with(density) { EdgeSwipeThreshold.toPx() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(EdgeSwipeWidth)
            .pointerInput(enabled) {
                var total = 0f
                var opened = false
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; opened = false },
                    onDragEnd = { if (total > thresholdPx && !opened) onOpen() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        total += amount
                        // 一旦越过阈值立即唤起，无需等松手，反馈更跟手；opened 去重避免重复触发
                        if (total > thresholdPx && !opened) {
                            opened = true
                            onOpen()
                        }
                    },
                )
            },
    ) {}
}

/** 无平台宿主时的占位内容（桌面端阶段 5 之前会看到它）。 */
@Composable
private fun SessionPlaceholder(graph: AppGraph, session: TabSession) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "WebView 宿主未接入当前平台\n平台：${graph.platformDisplayName} · 活动标签：${session.activeTab?.displayTitle ?: "无"}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 空状态页：会话里没有任何前台标签时展示（最后一个标签被停止/关闭后）。
 *
 * 不自动重启任何标签：冷标签留在侧栏列表里，是否重载由用户决定。
 * 这也是将来冷启动的第一屏形态——届时恢复策略改为先进此页，而不是立即重建上次会话。
 */
@Composable
private fun EmptySessionState(onOpenDrawer: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = "☰",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "没有正在浏览的标签",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "从侧栏选择一个站点开始浏览；屏幕左边缘向右滑动也能唤出侧栏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        FilledTonalButton(onClick = onOpenDrawer, modifier = Modifier.padding(top = 20.dp)) {
            Text("打开侧栏")
        }
    }
}
