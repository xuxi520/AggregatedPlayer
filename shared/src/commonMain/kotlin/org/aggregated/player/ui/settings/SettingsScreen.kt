package org.aggregated.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aggregated.player.core.ByteFormat
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.DownloadState
import org.aggregated.player.domain.model.DownloadTask
import org.aggregated.player.domain.model.MediaKind
import org.aggregated.player.domain.model.AppSettings
import org.aggregated.player.domain.model.PlaybackMode
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.TriggerDock
import org.aggregated.player.domain.model.TriggerSize
import org.aggregated.player.domain.usecase.DownloadCoordinator
import org.aggregated.player.platform.clearCrashReport
import org.aggregated.player.platform.takeLastCrashReport

/**
 * 设置页（全屏浮层，由侧栏「设置」进入）。
 *
 * 不引入导航库：以状态位控制的整屏 Surface 覆盖在主界面上，自带返回按钮（commonMain 无 BackHandler）。
 * 主页只放分组入口，各分组配置收进自己的二级页（[SettingsPage.FLOATING] / [SettingsPage.ADBLOCK]），
 * 返回键逐级回退：二级页 → 主页 → 关闭设置。
 */
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: (AppSettings) -> Unit,
    sites: List<Site>,
    rules: List<AdRule>,
    onToggleSiteAdBlock: (siteId: String, enabled: Boolean) -> Unit,
    onDeleteRule: (ruleId: String) -> Unit,
    onToggleRule: (ruleId: String, enabled: Boolean) -> Unit,
    onAddRule: (pattern: String, kind: AdRuleKind) -> Unit,
    downloadCoordinator: DownloadCoordinator,
    onSetSitePlaybackMode: (siteId: String, mode: PlaybackMode?) -> Unit = { _, _ -> },
    onClearCache: () -> Unit = {},
) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    // 崩溃诊断：进页时读一次（崩溃落盘由平台侧 UncaughtExceptionHandler 负责）
    var crashReport by remember { mutableStateOf(takeLastCrashReport()) }
    // 下载任务与缓存占用直接读协调器（共享层同源依赖，避免两个真相源）
    val downloads by downloadCoordinator.tasks.collectAsState()
    val cacheBytes by downloadCoordinator.cacheBytes.collectAsState()
    // 进入设置页时刷新一次缓存占用（变化不频繁，无需持续监听）
    LaunchedEffect(Unit) { downloadCoordinator.refreshCacheBytes() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                IconButton(onClick = { if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT }) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
                Text(page.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when (page) {
                SettingsPage.ROOT -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item {
                        SettingsEntry(
                            title = "悬浮控件",
                            subtitle = "触发器开关、停靠位置与大小、手势与淡化",
                            onClick = { page = SettingsPage.FLOATING },
                        )
                    }
                    item {
                        SettingsEntry(
                            title = "广告拦截",
                            subtitle = "全局开关、站点级开关与规则管理（${rules.size} 条）",
                            onClick = { page = SettingsPage.ADBLOCK },
                        )
                    }
                    item {
                        SettingsEntry(
                            title = "播放与下载",
                            subtitle = "默认播放引擎、站点偏好（当前：${settings.defaultPlaybackMode.label()}）",
                            onClick = { page = SettingsPage.PLAYBACK },
                        )
                    }
                    item {
                        SettingsEntry(
                            title = "关于我们",
                            subtitle = "版本、联系方式与版权说明",
                            onClick = { page = SettingsPage.ABOUT },
                        )
                    }
                    // 诊断：仅在检测到上次崩溃日志时出现（桌面端恒为 null，不占位）
                    crashReport?.let { report ->
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                        item {
                            CrashReportCard(
                                report = report,
                                onClear = { clearCrashReport(); crashReport = null },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
                SettingsPage.FLOATING -> FloatingSettingsContent(settings = settings, onChange = onChange)
                SettingsPage.PLAYBACK -> PlaybackSettingsContent(
                    settings = settings,
                    sites = sites,
                    onChange = onChange,
                    onSetSitePlaybackMode = onSetSitePlaybackMode,
                    downloads = downloads,
                    onCancelDownload = { downloadCoordinator.cancel(it) },
                    onDeleteDownload = { downloadCoordinator.delete(it) },
                    cacheBytesUsed = cacheBytes,
                    onClearCache = onClearCache,
                )
                SettingsPage.ABOUT -> AboutSettingsContent()
                SettingsPage.ADBLOCK -> AdBlockSettingsContent(
                    settings = settings,
                    onChange = onChange,
                    sites = sites,
                    rules = rules,
                    onToggleSiteAdBlock = onToggleSiteAdBlock,
                    onDeleteRule = onDeleteRule,
                    onToggleRule = onToggleRule,
                    onAddRule = onAddRule,
                )
            }
        }
    }
}

/** 设置页内的「页面」：主页分组入口 + 各分组二级配置页。 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    FLOATING("悬浮控件"),
    ADBLOCK("广告拦截"),
    PLAYBACK("播放与下载"),
    ABOUT("关于我们"),
}

/**
 * 崩溃诊断卡片：展示最近一次崩溃的摘要预览 + 复制/清除。
 *
 * 服务于「无 adb 环境下回收闪退现场」：用户真机复测闪退后进设置页一键复制整份栈，
 * 发给开发者即可定位——这是当前唯一可靠的根因获取通道。
 */
@Composable
private fun CrashReportCard(report: String, onClear: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "检测到上次异常退出的崩溃日志",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "点「复制日志」发给开发者定位闪退原因；确认无需后可清除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        Row {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                copied = true
            }) { Text(if (copied) "已复制" else "复制日志") }
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

/** 分组入口行：标题 + 副标题摘要，整行可点。 */
@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
    }
}

/** 应用版本号：发版时与各端构建配置（androidApp versionName / desktopApp packageVersion）保持一致。 */
private const val APP_VERSION = "1.0.0"

/**
 * 二级页：关于我们（应用信息 + 联系方式 + 版权说明）。
 *
 * 联系方式为**占位内容**：发版前替换 [ABOUT_EMAIL] / [ABOUT_SITE_URL] / [ABOUT_CONTACT] 三处常量即可。
 */
@Composable
private fun AboutSettingsContent() {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SettingsLabel("聚合播放器") }
        item {
            Text(
                "版本 $APP_VERSION",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item {
            Text(
                "把常用站点聚在一处：内置站点目录随版本更新，播放与下载全部在应用内完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
        item { SettingsLabel("联系方式") }
        item { AboutContactRow("官方邮箱", ABOUT_EMAIL) }
        item { AboutContactRow("官方网站", ABOUT_SITE_URL) }
        item { AboutContactRow("反馈交流", ABOUT_CONTACT) }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
        item { SettingsLabel("版权与合规") }
        item {
            Text(
                "本应用为聚合类播放工具：内置站点目录随版本更新，各站点内容与版权均归原站及著作权人所有，" +
                    "应用仅提供导航与播放能力、不存储、不分发站点内容。" +
                    "如权利人有异议或要求下架，请通过上方邮箱联系，我们会在核实后及时处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** 关于页里的「名称 + 取值」行：右侧为主色，视觉上与站点行的操作位区分开。 */
@Composable
private fun AboutContactRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** ⚠️ 发版前替换为真实联系方式。 */
private const val ABOUT_EMAIL = "support@example.com"
private const val ABOUT_SITE_URL = "https://example.com"
private const val ABOUT_CONTACT = "反馈交流群：请替换为真实群号或二维码说明"

/** 二级页：悬浮控件配置（触发器开关、手势、停靠位置与大小）。 */@Composable
private fun FloatingSettingsContent(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SettingsSwitch("启用悬浮触发器", settings.floatingTriggerEnabled) { onChange(settings.copy(floatingTriggerEnabled = it)) } }
        item { SettingsSwitch("左滑手势唤起侧边栏", settings.edgeSwipeDrawerEnabled) { onChange(settings.copy(edgeSwipeDrawerEnabled = it)) } }
        item { SettingsSwitch("闲置自动淡化", settings.idleDimmingEnabled) { onChange(settings.copy(idleDimmingEnabled = it)) } }
        item { SettingsSwitch("点击网页自动收起面板", settings.tapPageCollapseEnabled) { onChange(settings.copy(tapPageCollapseEnabled = it)) } }

        item { SettingsLabel("触发器停靠位置") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChip("左上角", settings.triggerDock == TriggerDock.TOP_LEFT) {
                    onChange(settings.copy(triggerDock = TriggerDock.TOP_LEFT, triggerOffsetXDp = -1f, triggerOffsetYDp = -1f))
                }
                SettingsChip("右上角", settings.triggerDock == TriggerDock.TOP_RIGHT) {
                    onChange(settings.copy(triggerDock = TriggerDock.TOP_RIGHT, triggerOffsetXDp = -1f, triggerOffsetYDp = -1f))
                }
            }
        }

        item { SettingsLabel("大小") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChip("小", settings.triggerSize == TriggerSize.SMALL) { onChange(settings.copy(triggerSize = TriggerSize.SMALL)) }
                SettingsChip("中", settings.triggerSize == TriggerSize.MEDIUM) { onChange(settings.copy(triggerSize = TriggerSize.MEDIUM)) }
                SettingsChip("大", settings.triggerSize == TriggerSize.LARGE) { onChange(settings.copy(triggerSize = TriggerSize.LARGE)) }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** 二级页：广告拦截配置（全局开关、站点级开关、规则管理）。 */
@Composable
private fun AdBlockSettingsContent(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    sites: List<Site>,
    rules: List<AdRule>,
    onToggleSiteAdBlock: (siteId: String, enabled: Boolean) -> Unit,
    onDeleteRule: (ruleId: String) -> Unit,
    onToggleRule: (ruleId: String, enabled: Boolean) -> Unit,
    onAddRule: (pattern: String, kind: AdRuleKind) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SettingsSwitch("启用广告拦截（全局）", settings.adBlockEnabled) { onChange(settings.copy(adBlockEnabled = it)) } }
        item { SettingsLabel("生效规则：${rules.size} 条（${rules.count { it.enabled }} 启用）") }

        item { SettingsLabel("站点级开关（关闭即不拦截该站点）") }
        items(sites, key = { it.id }) { site ->
            AdBlockSiteRow(site = site, onToggle = onToggleSiteAdBlock)
        }

        item { SettingsLabel("规则列表") }
        if (rules.isEmpty()) {
            item { Text("暂无规则", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        items(rules, key = { it.id }) { rule ->
            AdBlockRuleRow(rule = rule, onToggle = onToggleRule, onDelete = onDeleteRule)
        }

        item { SettingsLabel("新增规则") }
        item { AdBlockAddRuleForm(onAddRule = onAddRule) }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun AdBlockSiteRow(
    site: Site,
    onToggle: (siteId: String, enabled: Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(site.name, style = MaterialTheme.typography.bodyLarge)
            if (site.url.isNotBlank()) {
                Text(site.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Switch(checked = site.adBlockEnabled, onCheckedChange = { onToggle(site.id, it) })
    }
}

@Composable
private fun AdBlockRuleRow(
    rule: AdRule,
    onToggle: (ruleId: String, enabled: Boolean) -> Unit,
    onDelete: (ruleId: String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.pattern,
                style = MaterialTheme.typography.bodyMedium,
                color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                "${rule.kind.label()}${if (rule.siteId != null) " · 站点级" else " · 全局"}${rule.note?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule.id, it) })
        IconButton(onClick = { onDelete(rule.id) }) { Text("删除", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun AdBlockAddRuleForm(onAddRule: (pattern: String, kind: AdRuleKind) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AdRuleKind.URL_SUBSTRING) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = pattern,
            onValueChange = { pattern = it },
            label = { Text("特征（域名或 URL 子串）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            SettingsChip("域名", kind == AdRuleKind.DOMAIN) { kind = AdRuleKind.DOMAIN }
            SettingsChip("子串", kind == AdRuleKind.URL_SUBSTRING) { kind = AdRuleKind.URL_SUBSTRING }
            SettingsChip("例外", kind == AdRuleKind.EXCEPTION) { kind = AdRuleKind.EXCEPTION }
        }
        Button(
            onClick = { onAddRule(pattern, kind); pattern = "" },
            enabled = pattern.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        ) { Text("添加规则") }
    }
}

private fun AdRuleKind.label(): String = when (this) {
    AdRuleKind.DOMAIN -> "域名"
    AdRuleKind.URL_SUBSTRING -> "子串"
    AdRuleKind.EXCEPTION -> "例外"
    AdRuleKind.ELEMENT -> "元素"
}

/** 二级页：播放与下载（第一阶段只有播放引擎偏好；下载设置待 4-A 接线）。 */
@Composable
private fun PlaybackSettingsContent(
    settings: AppSettings,
    sites: List<Site>,
    onChange: (AppSettings) -> Unit,
    onSetSitePlaybackMode: (String, PlaybackMode?) -> Unit,
    downloads: List<DownloadTask>,
    onCancelDownload: (String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    cacheBytesUsed: Long = 0L,
    onClearCache: () -> Unit = {},
) {
    var pickingSite by remember { mutableStateOf<Site?>(null) }
    // 清理缓存不可恢复（会取消进行中的下载并删掉分片），故二次确认并展示可清理容量
    var confirmingClearCache by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SettingsLabel("全局默认播放引擎") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChip("网页原生", settings.defaultPlaybackMode == PlaybackMode.WEBVIEW) {
                    onChange(settings.copy(defaultPlaybackMode = PlaybackMode.WEBVIEW))
                }
                SettingsChip("增强引擎", settings.defaultPlaybackMode == PlaybackMode.ENHANCED) {
                    onChange(settings.copy(defaultPlaybackMode = PlaybackMode.ENHANCED))
                }
                SettingsChip("每次询问", settings.defaultPlaybackMode == PlaybackMode.ASK) {
                    onChange(settings.copy(defaultPlaybackMode = PlaybackMode.ASK))
                }
            }
        }
        item {
            Text(
                "默认「网页原生」不抢站点播放器；「增强引擎」在嗅探到可播放资源时自动用覆盖层接管，" +
                    "「每次询问」会先弹提示。手动点嗅探结果的 ▶ 任何时候都可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
        item { SettingsLabel("站点级覆盖") }
        item {
            Text(
                "可为单个站点单独指定；未设置则跟随全局",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        items(sites, key = { it.id }) { site ->
            PlaybackSiteRow(site = site, onClick = { pickingSite = site })
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
        item { SettingsLabel("下载任务") }
        item {
            Text(
                "直链资源下载到应用私有目录；带防盗链的请求头会随任务透传（不写入日志）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (downloads.isEmpty()) {
            item {
                Text(
                    "还没有下载任务（在「当前页面工具 → 嗅探」点 ⬇ 即可）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            items(downloads, key = { it.id }) { task ->
                DownloadTaskRow(
                    task = task,
                    onCancel = { onCancelDownload(task.id) },
                    onDelete = { onDeleteDownload(task.id) },
                )
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
        item { SettingsLabel("离线缓存") }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "HLS/DASH 分片缓存（边播边缓存、可离线复用）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "已用 ${ByteFormat.readable(cacheBytesUsed)} / 上限 2 GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(onClick = { confirmingClearCache = true }) { Text("清空") }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
    pickingSite?.let { site ->
        PlaybackModePickerDialog(
            site = site,
            current = site.playbackMode,
            onSelect = { mode ->
                onSetSitePlaybackMode(site.id, mode)
                pickingSite = null
            },
            onDismiss = { pickingSite = null },
        )
    }
    if (confirmingClearCache) {
        AlertDialog(
            onDismissRequest = { confirmingClearCache = false },
            title = { Text("清理缓存") },
            text = { Text("将清理 ${ByteFormat.readable(cacheBytesUsed)} 离线视频，是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClearCache = false
                        onClearCache()
                    },
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearCache = false }) { Text("取消") }
            },
        )
    }
}

/** 站点行：沿用广告拦截站点行的视觉，右侧显示当前生效的引擎偏好。 */
@Composable
private fun PlaybackSiteRow(site: Site, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(site.name, style = MaterialTheme.typography.bodyLarge)
            if (site.url.isNotBlank()) {
                Text(site.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(
            site.playbackMode.settingsLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
    }
}

private const val PERCENT_SCALE = 100

/** 下载任务行：文件名 + 进度/状态 + 取消/删除，与站点行保持相同的留白节奏。 */
@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    // 删除已完成的下载不可恢复（用户得重新下载一遍），故要二次确认；
    // 取消下载是「放弃 + 释放空间」，代价低，不需要确认。
    var confirmingDelete by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(task.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val fraction = task.fraction
        if (task.state == DownloadState.RUNNING && fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Text(
                "${(fraction * PERCENT_SCALE).toInt()}% · ${ByteFormat.readable(task.progressBytes)} / ${ByteFormat.readable(task.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // HLS/DASH 离线下载产出的是缓存而非单文件，完成时明确告知「已离线」而非「已完成」
            val statusText = if (task.state == DownloadState.SUCCESS && task.kind != MediaKind.DIRECT) {
                "已离线（缓存）"
            } else {
                task.state.label() + (task.errorMessage?.let { " · $it" } ?: "")
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (task.state == DownloadState.FAILED || task.state == DownloadState.CANCELED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row {
            // 按状态区分动作：已完成 → 「删除（含文件）」；未完成/进行中 → 「取消下载」
            if (task.state == DownloadState.SUCCESS) {
                TextButton(onClick = { confirmingDelete = true }) { Text("删除（含文件）") }
            } else {
                TextButton(onClick = onCancel) { Text("取消下载") }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除（含文件）") },
            text = { Text("确定删除该视频及其本地文件吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun DownloadState.label(): String = when (this) {
    DownloadState.PENDING -> "等待中"
    DownloadState.RUNNING -> "下载中"
    DownloadState.PAUSED -> "已暂停"
    DownloadState.SUCCESS -> "已完成"
    DownloadState.FAILED -> "失败"
    DownloadState.CANCELED -> "已取消"
}

/** 单选弹窗：与元素拾取确认框同一套 RadioButton 视觉，避免为四个选项新增控件库。 */
@Composable
private fun PlaybackModePickerDialog(
    site: Site,
    current: PlaybackMode?,
    onSelect: (PlaybackMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<Pair<PlaybackMode?, String>> = listOf(
        null to "跟随全局",
        PlaybackMode.WEBVIEW to "网页原生",
        PlaybackMode.ENHANCED to "增强引擎",
        PlaybackMode.ASK to "每次询问",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${site.name} · 默认播放引擎") },
        text = {
            Column {
                options.forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun PlaybackMode.label(): String = when (this) {
    PlaybackMode.WEBVIEW -> "网页原生"
    PlaybackMode.ENHANCED -> "增强引擎"
    PlaybackMode.ASK -> "每次询问"
}

private fun PlaybackMode?.settingsLabel(): String = when (this) {
    null -> "跟随全局"
    PlaybackMode.WEBVIEW -> "网页原生"
    PlaybackMode.ENHANCED -> "增强引擎"
    PlaybackMode.ASK -> "每次询问"
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
