package org.aggregated.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aggregated.player.di.AppGraph
import org.aggregated.player.domain.model.Site
import org.aggregated.player.domain.model.TabSession
import org.aggregated.player.platform.web.ExternalBrowserBridge

/**
 * 桌面端页面区（阶段 5）。
 *
 * 桌面端当前**没有内嵌浏览器内核**（JCEF 尚未接入），所以页面区不是网页视图，
 * 而是一张「站点卡片 + 打开入口 + 能力边界说明」：
 * - 点击站点后由 [ExternalBrowserBridge] 交给系统默认浏览器打开（桌面端浏览的实际承载）；
 * - 如实列出桌面端暂不可用的能力（广告拦截 / 媒体嗅探 / 增强播放都依赖内嵌内核），
 *   避免用户以为「点了没反应」是 bug。
 *
 * 站点名与入口地址从 [AppGraph.siteRepository] 读（挂起调用，故用 LaunchedEffect）。
 */
@Composable
fun DesktopPageArea(
    graph: AppGraph,
    session: TabSession,
    embeddedBrowserReady: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tab = session.activeTab
    var site by remember { mutableStateOf<Site?>(null) }
    LaunchedEffect(tab?.siteId) {
        val siteId = tab?.siteId ?: return@LaunchedEffect
        site = graph.siteRepository.loadAll().firstOrNull { it.id == siteId }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedCard(modifier = Modifier.widthIn(max = 520.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = site
                if (current == null) {
                    Text("桌面端浏览说明", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "从左侧站点列表选一个站点即可开始。" +
                            if (embeddedBrowserReady) {
                                "站点会在应用窗口内打开（内嵌 CEF 内核）。"
                            } else {
                                "桌面端当前把站点交给系统默认浏览器打开。"
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(current.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        current.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!embeddedBrowserReady) {
                        Button(onClick = { ExternalBrowserBridge.open(current.url) }) {
                            Text("在系统浏览器中打开")
                        }
                    }
                    if (embeddedBrowserReady) {
                        Text(
                            "页面已在应用窗口内加载（广告拦截、弹窗守卫、点击拾取均在页面内生效）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    "桌面端能力边界：广告拦截、媒体嗅探、增强播放均依赖内嵌浏览器内核" +
                        "（阶段 5 计划接入 JCEF），当前尚未启用；" +
                        "「UA 模式」等站点设置仅对内嵌内核生效，外链浏览由浏览器自身决定。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
