package org.aggregated.player.platform.web

import kotlinx.coroutines.runBlocking
import org.aggregated.player.core.Logger
import org.aggregated.player.di.AppGraph
import org.aggregated.player.domain.usecase.CompositeAdBlockEngine
import org.aggregated.player.domain.usecase.FilterListEngine
import org.aggregated.player.platform.BrowserTabPool

/** 桌面端浏览器运行时：注入拦截引擎与桥接回调（与 Android 的 AndroidBrowserRuntime 同款职责）。 */
object DesktopBrowserRuntime {

    fun install(graph: AppGraph, pool: BrowserTabPool, logger: Logger) {
        AdBlockingBridge.engine = CompositeAdBlockEngine(listOf(FilterListEngine(logger), graph.adRuleEngine))
        AdBlockingBridge.siteContext = { tabId ->
            val siteId = graph.tabManager.session.value.tabs.firstOrNull { it.id == tabId }?.siteId
            val enabled = siteId?.let { id ->
                runBlocking { graph.siteRepository.loadAll() }.firstOrNull { it.id == id }?.adBlockEnabled
            } ?: true
            siteId to enabled
        }
        AdBlockingBridge.onMarkingModeChanged = { e -> pool.forEachActive { it.setAdMarkingMode(e) } }
        AdBlockingBridge.onReapplyStyles = { pool.forEachActive { it.reapplyAdBlockStyles() } }
        AdBlockingBridge.onPickParent = { pool.forEachActive { it.pickParent() } }
        AdBlockingBridge.onPickChild = { pool.forEachActive { it.pickChild() } }
    }
}
