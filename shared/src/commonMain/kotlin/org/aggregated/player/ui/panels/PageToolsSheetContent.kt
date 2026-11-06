package org.aggregated.player.ui.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.SniffedMedia
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.ui.ItemSpacing

/**
 * 「当前页面工具」面板：一个入口收纳两类页面级内容——嗅探到的媒体资源 / 本站已拦截的元素。
 *
 * 用 Tab 而不是把悬浮气泡加到第 6 枚：气泡总数参与 `bubblesWidthPx` 定位计算，
 * 在左半区弹出时多一枚会把整排气泡推到屏幕外；维持 5 枚、把复杂度放进面板内部更稳。
 * 两个 Tab 的角标数字直接来自列表长度，用户不用点开就知道有没有内容。
 *
 * 「UA 模式」是两个 Tab 共用的公共段（§4.4 当前站点 UA 切换）：按标签持久化到 `SiteTab`，
 * 切桌面 UA 后重载当前页生效——桌面 UA 常能拿到更高码率源。
 */
// 面板参数本就是「数据 + 一串回调」的组合：Composable 面板的回调天然按功能平铺，
// 硬凑 15 个以内只会逼出意义不明的参数对象，抑制比包装更清晰
@Suppress("LongParameterList")
@Composable
internal fun PageToolsSheetContent(
    sniffed: List<SniffedMedia>,
    blockedRules: List<AdRule>,
    session: TabSession,
    platformName: String,
    ruleCount: Int,
    busy: Boolean,
    onPlay: (String) -> Unit,
    onDownload: (String) -> Unit = {},
    onPush: (String) -> Unit = {},
    onMarkPattern: (String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    markingMode: Boolean,
    onToggleMarkingMode: (Boolean) -> Unit,
    userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
    onSetUserAgentMode: (UserAgentMode) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("嗅探 ${sniffed.size}")
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("已拦截 ${blockedRules.size}")
            }
        }
        // 当前站点 UA 切换（§4.4）：两个 Tab 共用的公共段，互斥选中表达当前态；
        // 点击立即下发切换并重载当前页，模式按标签持久化（SiteTab.userAgentMode）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("UA 模式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FilterChip(
                selected = userAgentMode == UserAgentMode.MOBILE,
                onClick = { onSetUserAgentMode(UserAgentMode.MOBILE) },
                label = { Text("📱 移动") },
            )
            FilterChip(
                selected = userAgentMode == UserAgentMode.DESKTOP_SIMULATE,
                onClick = { onSetUserAgentMode(UserAgentMode.DESKTOP_SIMULATE) },
                label = { Text("🖥 桌面") },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        when (selectedTab) {
            0 -> ToolsSheetContent(
                sniffed = sniffed,
                session = session,
                platformName = platformName,
                ruleCount = ruleCount,
                busy = busy,
                onPlay = onPlay,
                onDownload = onDownload,
                onPush = onPush,
                downloadEnabled = true,
                onMarkPattern = onMarkPattern,
                markingMode = markingMode,
                onToggleMarkingMode = onToggleMarkingMode,
            )
            else -> BlockedElementsSheetContent(
                rules = blockedRules,
                onDeleteRule = onDeleteRule,
                onToggleRule = onToggleRule,
            )
        }
    }
}

/**
 * 本站已拦截（隐藏）元素列表。
 *
 * 仅展示当前站点的 `ELEMENT` 规则——不同站点天然隔离（`AdRule.siteId`），互不影响。
 * 关闭开关可临时停用（排查误杀时最快），删除则彻底取消拦截，二者都经 ViewModel 立即重注入样式。
 */
@Composable
private fun BlockedElementsSheetContent(
    rules: List<AdRule>,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("本站已拦截的元素", style = MaterialTheme.typography.titleMedium)
        Text(
            "关闭开关可临时停用，删除则彻底取消拦截（立即生效）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (rules.isEmpty()) {
            Text(
                "还没有标记任何元素",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            rules.forEach { rule ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = ItemSpacing),
                ) {
                    Text(
                        rule.pattern,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule.id, it) })
                    TextButton(onClick = { onDeleteRule(rule.id) }) { Text("删除") }
                }
            }
        }
    }
}
