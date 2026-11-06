package org.aggregated.player.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.SiteSource
import org.aggregated.player.domain.model.SiteTab
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.model.TabState
import kotlinx.coroutines.delay
import org.aggregated.player.domain.usecase.mediaDisplayName
import org.aggregated.player.domain.usecase.mediaMetricsHint
import org.aggregated.player.domain.usecase.mediaSourceHint
import org.aggregated.player.domain.usecase.mediaTypeHint
import org.aggregated.player.ui.DrawerWidth
import org.aggregated.player.ui.ItemSpacing
import org.aggregated.player.ui.MARK_HINT_MIN
import org.aggregated.player.ui.SidePadding
import org.aggregated.player.ui.SnifferMaxHeight
import org.aggregated.player.ui.domainOf

/**
 * 侧栏：紧凑状态头 + 站点列表 + 折叠式「添加站点」+ 底部「设置」入口。
 *
 * 站点↔标签 1:1（Q1），所以这里点站点就是切换标签的唯一入口；已打开的站点高亮。
 * 运行中的站点（前台/后台保活）带状态圆点，并提供「停止」按钮释放 WebView 实例；
 * 停止必须用标签自身的 id（[SiteTab.id]），站点 id 只是查找键，两者不是一回事。
 * 「添加站点」默认折叠：表单不常驻，打开侧栏时焦点不会落在输入框上（不弹键盘），
 * 「工具」（资源嗅探）入口暂撤，后续并进悬浮控件。
 */
@Composable
internal fun SiteListPanel(
    sites: List<Site>,
    activeSiteId: String?,
    loading: Boolean,
    tabCount: Int,
    ruleCount: Int,
    warnMemory: Boolean,
    tabBySite: Map<String, SiteTab>,
    onOpenSite: (String) -> Unit,
    onAddSite: (name: String, url: String) -> Unit,
    onDeleteSite: (String) -> Unit,
    onStopTab: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showAddForm by remember { mutableStateOf(false) }

    DrawerSheet(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部拖拽把手：提示用户可从此处（或任意位置）向左滑动关闭侧栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            CircleShape,
                        ),
                )
            }
            // 紧凑状态头：标题一行 + 统计一行，压缩纵向空间
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "站点",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "标签 $tabCount · 规则 $ruleCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                    // 后台保活 = 实例常驻已降载的标签数；从同一份 tabBySite 实时算，
                    // 保证与列表圆点、与总数严格自洽（总数 = 后台保活 + 前台 + 冷）。
                    val background = tabBySite.count { it.value.state == TabState.BACKGROUND }
                    Text(
                        text = "后台 $background",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                    if (warnMemory) {
                        // 只提示不淘汰：这是 Q2「保活数 = 标签数」定档后唯一的资源可见性手段（§4.2 第 4 条）
                        Text(
                            text = "已打开较多标签，若卡顿或被系统回收请关闭部分标签。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (loading) {
                Text(
                    "正在读取本地站点配置…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else if (sites.isEmpty()) {
                // 内置目录已在启动时种入（见 BuiltInSites）；走到这里只可能是种入失败或用户清空，故给出添加引导
                Text(
                    "还没有站点。可在下方展开添加你自己信任的地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                items(sites, key = { it.id }) { site ->
                    val tab = tabBySite[site.id]
                    // 只有真正运行中（前台/后台保活）的标签才给「停止」入口：
                    // 冷标签（含重启恢复的历史标签）没有实例可释放，stop() 也只会空操作
                    val live = tab?.state == TabState.ACTIVE || tab?.state == TabState.BACKGROUND
                    SiteRow(
                        title = site.name,
                        subtitle = domainOf(site.url),
                        selected = site.id == activeSiteId,
                        isBuiltIn = site.source == SiteSource.BUILTIN,
                        tabState = tab?.state,
                        onClick = { onOpenSite(site.id) },
                        onDelete = { onDeleteSite(site.id) },
                        onStop = if (tab != null && live) {
                            { onStopTab(tab.id) }
                        } else {
                            null
                        },
                    )
                }
            }

            // 底部功能区：surfaceContainer 抬一层 + 顶部描边，与列表明确分区
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    // 折叠式添加表单：默认收起，点「添加站点」才展开，避免打开侧栏即聚焦弹键盘
                    AnimatedVisibility(visible = showAddForm) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("名称（可留空）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { url = it },
                                    label = { Text("地址 example.com") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                                Button(
                                    onClick = {
                                        onAddSite(name, url)
                                        name = ""
                                        url = ""
                                        showAddForm = false
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                ) {
                                    Text("添加")
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing)) {
                        FilledTonalButton(
                            onClick = { showAddForm = !showAddForm },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (showAddForm) "收起" else "＋ 添加站点")
                        }
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                            Text("⚙ 设置")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 工具弹层：运行时信息 + 嗅探结果 + 点级标记辅助入口。
 *
 * 从顶栏 🛡 入口迁到侧栏「工具」按钮呼出；能力本身不变（外部播放阶段 3 接线、URL 标记后续进设置页）。
 */
@Composable
internal fun ToolsSheetContent(
    sniffed: List<SniffedMedia>,
    session: TabSession,
    platformName: String,
    ruleCount: Int,
    busy: Boolean,
    onPlay: (String) -> Unit,
    onMarkPattern: (String) -> Unit,
    onDownload: (String) -> Unit = {},
    onPush: (String) -> Unit = {},
    downloadEnabled: Boolean = false,
    markingMode: Boolean = false,
    onToggleMarkingMode: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pattern by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = SidePadding).padding(bottom = 24.dp)) {
        val active = session.activeTab
        Text(
            text = active?.let { "当前标签：${it.displayTitle}（${it.url}）" } ?: "未打开任何标签",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "保活实例：${session.liveTabCount} / 标签 ${session.tabs.size} · " +
                "拦截规则：$ruleCount 条 · $platformName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = ItemSpacing),
        )

        Text("嗅探结果", style = MaterialTheme.typography.titleSmall)
        if (sniffed.isEmpty()) {
            Text(
                "当前页面暂未捕获到媒体资源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ItemSpacing),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(SnifferMaxHeight)) {
                items(sniffed, key = { it.id }) { media ->
                    SniffedMediaRow(
                        media = media,
                        busy = busy,
                        downloadEnabled = downloadEnabled,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onPush = onPush,
                    )
                }
            }
        }
        // 预期管理：网络层只能看到页面发起的请求，Blob/MSE 动态流不一定可见（C3 是「尽力而为」）
        Text(
            "仅能捕获页面发起的网络请求，Blob / MSE 与加密流不一定可见",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = ItemSpacing),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = ItemSpacing))

        Text("点级标记 · URL 特征辅助入口", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("特征至少 ${MARK_HINT_MIN} 字符") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    onMarkPattern(pattern)
                    pattern = ""
                },
                modifier = Modifier.padding(start = ItemSpacing),
            ) {
                Text("标记")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = ItemSpacing))

        Text("元素标记模式", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("开启后长按网页元素即可隐藏", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "标记模式会抑制网页原生长按选择，关闭即恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = markingMode, onCheckedChange = onToggleMarkingMode)
        }
    }
}

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB

/** 「已复制」提示回退为「复制链接」的延迟。 */
private const val COPIED_HINT_RESET_MS = 2000L

/** 行内动作按钮的统一内边距（小号按钮，避免占满行高）。 */
private val RowButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * 嗅探结果行：两段式布局——信息区在上（独占整行宽度），动作按钮整行在下。
 *
 * 改版原因（用户实测反馈「信息一行、按钮在下面一行」）：原先按钮与信息挤同一行，
 * 信息列被按钮压缩得只显示省略号，判断真假视频所需的关键内容根本看不到。
 * 现在信息区放开到两行（标题 1 行 + 证据 2 行），按钮独占一行不再挤占信息宽度。
 */
@Composable
private fun SniffedMediaRow(
    media: SniffedMedia,
    busy: Boolean,
    downloadEnabled: Boolean,
    onPlay: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPush: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = ItemSpacing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = when (media.kind) {
                    MediaKind.DIRECT -> MaterialTheme.colorScheme.primaryContainer
                    MediaKind.HLS -> MaterialTheme.colorScheme.tertiaryContainer
                    MediaKind.DASH -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    media.kind.shortLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = ItemSpacing)) {
                Text(
                    text = mediaDisplayName(media),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val downloadHint = when (media.kind) {
                    MediaKind.DIRECT -> ""
                    MediaKind.HLS, MediaKind.DASH -> " · 可离线下载"
                    else -> " · 暂不可下载"
                }
                Text(
                    // 证据行按「判断真假视频的证据强度」排：MIME 最硬（C2 响应头），其次域名、指标与体积；
                    // 指标（分辨率/时长）来自清单增强，帮用户不点开就判断是不是视频本体。
                    // 放开两行：手机端一行根本装不下这些关键信息
                    text = listOf(
                        mediaTypeHint(media),
                        domainOf(media.url),
                        mediaMetricsHint(media),
                        readableSize(media.estimatedSizeBytes),
                    ).filter { it.isNotBlank() }.joinToString(" · ") +
                        downloadHint +
                        " · ${mediaSourceHint(media)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ItemSpacing),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (!media.supportsExternalPlayback) {
                // MSE/Blob 是内存地址，外部引擎与下载器都用不了；给出原因而不是一排莫名的灰按钮
                Text(
                    "内存流 · 仅网页播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                CopyLinkButton(media.url)
                FilledTonalButton(
                    onClick = { onPlay(media.id) },
                    enabled = !busy,
                    contentPadding = RowButtonPadding,
                ) { Text("▶") }
                OutlinedButton(
                    onClick = { onDownload(media.id) },
                    // 直链产出可打开文件；HLS/DASH 走离线缓存；两者都支持。MSE/Blob 与未知类型不开放
                    enabled = downloadEnabled && media.kind != MediaKind.MSE_BLOB && media.kind != MediaKind.UNKNOWN,
                    contentPadding = RowButtonPadding,
                ) { Text("⬇") }
                // 「推」= 交系统选择器把 URL 给其它播放器（投屏逻辑由接收方实现，见 MediaPush KDoc）
                OutlinedButton(
                    onClick = { onPush(media.id) },
                    enabled = !busy,
                    contentPadding = RowButtonPadding,
                ) { Text("推") }
            }
        }
    }
}

/**
 * 「复制链接」：把嗅探到的原始 URL 写入系统剪贴板。
 *
 * 用户复制链接后可以交给外部下载器/播放器——本项目对 Blob/MSE 与加密流本就不承诺全流处理，
 * 给用户一条自己拿走链接的路，比在应用内死磕更符合「嗅探是辅助入口」的定位。
 * 点击后短暂显示「已复制」反馈，随后自动复原。
 */
@Composable
private fun CopyLinkButton(url: String) {
    var copied by remember(url) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_HINT_RESET_MS)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString(url))
            copied = true
        },
        contentPadding = RowButtonPadding,
    ) { Text(if (copied) "已复制" else "复制链接") }
}

private fun MediaKind.shortLabel(): String = when (this) {
    MediaKind.DIRECT -> "直链"
    MediaKind.HLS -> "HLS"
    MediaKind.DASH -> "DASH"
    MediaKind.MSE_BLOB -> "内存流"
    MediaKind.UNKNOWN -> "未知"
}

private fun readableSize(bytes: Long?): String = when {
    bytes == null -> "大小未知"
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_MB -> "${bytes / BYTES_PER_KB} KB"
    else -> "${bytes / BYTES_PER_MB} MB"
}

/** 抽屉面板：固定宽度 + surfaceContainerLow + 投影（material3 alpha 未暴露稳定 DrawerSheet 组合件，按规范复刻）。 */
@Composable
private fun DrawerSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.width(DrawerWidth).fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
    ) {
        content()
    }
}

/**
 * 站点行：首字母头像 + 名称/域名 + 运行状态点 + 选中态 + 来源标识。
 *
 * 选中行：secondaryContainer 背景 + 左侧主色指示条 + 加粗标题；未选行背景透明。
 * 运行状态：标题尾随小圆点——主色=前台浏览中（ACTIVE），tertiary 色=后台保活（BACKGROUND）；
 * 冷标签/未打开不显示。
 * 尾部：运行中站点先给「停止」按钮（中性色小方块图标，释放实例，标签保留）；内置站显示「内置」标签且不可删
 * （来源决策见 [SiteSource]）；用户站显示删除按钮。
 */
@Composable
private fun SiteRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    isBuiltIn: Boolean,
    tabState: TabState?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onStop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Text(
                        text = title.trim().take(1).ifEmpty { "?" }.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (tabState == TabState.ACTIVE || tabState == TabState.BACKGROUND) {
                            Spacer(modifier = Modifier.width(6.dp))
                            // 运行状态点：前台主色、后台 tertiary 色，一眼区分浏览中与保活中
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (tabState == TabState.ACTIVE) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                    ),
                            )
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onStop != null) {
                    // 停止运行（前台/后台均可）：释放 WebView 实例，标签保留，再点站点即重载。
                    // 用自绘小方块当「停止」图标：⏹/⏸ 等字形在部分设备字体上会回退成彩色 emoji，观感不可控
                    IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
                if (isBuiltIn) {
                    // 内置站只读：用标签标明来源，不提供删除入口
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = "内置",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Text("🗑", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (selected) {
                // 左侧主色指示条：比单纯背景色更易扫视定位当前站点
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
