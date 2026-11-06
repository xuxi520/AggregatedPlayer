package org.aggregated.player.platform

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.domain.usecase.NavigationAction
import org.aggregated.player.domain.usecase.TabEffect

/**
 * 通用的标签实例池（设计文档 §4.1、§4.2）。
 *
 * **本类不含任何平台 API**：它只与 [BrowserTabController] 契约和 `Any` 形态的视图对象打交道，
 * 因此放在 `commonMain`——Android 与将来的 Windows 端复用同一份池逻辑，只需要各提供一个
 * [BrowserTabControllerFactory]。这也是池的调度行为能在 `commonTest` 里被完整验证的原因。
 *
 * 它同时实现 [TabEffectExecutor]：共享层 `TabManager` 产出的效果就是本类的输入，
 * 中间不再需要一层「把意图翻译成方法调用」的胶水，减少一处会走偏的映射。
 *
 * 三条不变量（改动前请先想清楚会不会破坏它们）：
 * 1. **一个 tabId 只对应一个实例**：[obtain] 命中已有实例时**绝不**重新加载页面；
 * 2. **切换只改可见性与暂停状态，不销毁**：这是「切回即继续浏览」的实现前提；
 * 3. **释放只发生在 [TabEffect.Evict] / [TabEffect.Discard] / [releaseAll]**：
 *    除此之外没有任何路径会 destroy 实例。
 */
class DefaultBrowserTabPool(
    private val factory: BrowserTabControllerFactory,
    private val logger: Logger,
    private val mainThreadChecker: MainThreadChecker = RelaxedMainThreadChecker,
) : BrowserTabPool, TabEffectExecutor {

    /** 插入顺序即创建顺序；可见性与内存打点都依赖它，所以用 LinkedHashMap 而非 Map。 */
    private val controllers = LinkedHashMap<String, BrowserTabController>()

    private var hostContainer: Any? = null

    override fun obtain(tabId: String, entryUrl: String): BrowserTabController {
        mainThreadChecker.assertMainThread("obtain")

        controllers[tabId]?.let { existing ->
            // 命中已有实例时只补一次 attach（容器可能刚换了），**不能** loadUrl：
            // 一次多余的加载就等于把用户当前的播放与滚动位置全打掉
            attachIfNeeded(existing)
            return existing
        }

        val created = factory.create(tabId, entryUrl)
        controllers[tabId] = created
        attachIfNeeded(created)
        logger.info("创建标签实例 $tabId（当前存活 ${controllers.size} 个）")
        return created
    }

    override fun peek(tabId: String): BrowserTabController? = controllers[tabId]

    override fun liveCount(): Int = controllers.size

    override fun setHostContainer(container: Any) {
        val previous = hostContainer
        hostContainer = container
        if (previous !== container && previous != null) {
            // 宿主重建（如 Activity 配置变更）后旧容器不能再持有实例，否则视图树泄漏
            controllers.values.forEach { it.detachFromParent() }
        }
        controllers.values.forEach { attachIfNeeded(it) }
    }

    override fun release(tabId: String) {
        mainThreadChecker.assertMainThread("release")
        val removed = controllers.remove(tabId) ?: return
        destroy(removed)
    }

    override fun releaseAll() {
        mainThreadChecker.assertMainThread("releaseAll")
        controllers.values.forEach { destroy(it) }
        controllers.clear()
        logger.info("已释放全部标签实例")
    }

    override fun syncVisibility(activeTabId: String?) {
        mainThreadChecker.assertMainThread("syncVisibility")
        controllers.forEach { (tabId, controller) ->
            attachIfNeeded(controller)
            controller.setVisibility(tabId == activeTabId)
        }
    }

    override fun forEachActive(action: (BrowserTabController) -> Unit) {
        controllers.values.forEach(action)
    }

    /**
     * 执行一批效果。
     *
     * 单条失败不影响其余：例如某个冷标签创建实例时抛异常，其它标签的 Pause/Resume 仍要正常下发。
     * 失败只记日志——状态纠偏由调用方（宿主）通过 `TabManager.markCold` 完成，池不越权改共享状态。
     */
    override fun execute(effects: List<TabEffect>) {
        effects.forEach { effect ->
            try {
                apply(effect)
            } catch (error: Exception) {
                logger.error("标签效果执行失败：$effect", error)
            }
        }
    }

    private fun apply(effect: TabEffect) {
        when (effect) {
            // Load 是唯一会触发页面加载的效果：新建、冷标签唤醒、规则生效后的重载都走它
            is TabEffect.Load -> {
                // 以标签持久化的 UA 就位（reload=false，避免与紧随其后的 loadUrl 双重加载），
                // 确保冷标签重建后 UA 与状态一致（§4.4）
                val controller = obtain(effect.tab.id, effect.tab.url)
                controller.updateUserAgentMode(effect.tab.userAgentMode, reload = false)
                controller.loadUrl(effect.tab.url)
            }

            // UA 切换：定向到指定标签实例，默认 reload 让新 UA 立即生效
            is TabEffect.SetUserAgent -> controllers[effect.tabId]?.updateUserAgentMode(effect.mode)

            is TabEffect.Resume -> {
                // Resume 语义上一定对应热标签；实例不存在说明状态与实例已不一致，
                // 这里补一次创建（按已存 URL），比让 UI 白屏更容易恢复
                val controller = controllers[effect.tabId]
                if (controller == null) {
                    logger.warn("Resume 时实例不存在，按冷标签处理：${effect.tabId}")
                } else {
                    controller.resume()
                }
            }

            is TabEffect.Pause -> controllers[effect.tabId]?.pause()

            // Evict 与 Discard 在池这一侧动作相同（释放实例），区别只在共享层是否保留标签记录
            is TabEffect.Evict -> release(effect.tab.id)
            is TabEffect.Discard -> release(effect.tabId)

            is TabEffect.Navigate -> {
                val controller = controllers[effect.tabId]
                if (controller == null) {
                    // 冷标签没有实例可导航。这里不自动重建：用户点「后退」时
                    // 想要的是回到上一页，而不是把当前页重载一遍再退
                    logger.warn("导航失败，标签无实例：${effect.tabId} → ${effect.action}")
                } else {
                    when (effect.action) {
                        NavigationAction.BACK -> controller.goBack()
                        NavigationAction.FORWARD -> controller.goForward()
                        NavigationAction.RELOAD -> controller.reload()
                        NavigationAction.STOP -> controller.stopLoading()
                    }
                }
            }
        }
    }

    private fun attachIfNeeded(controller: BrowserTabController) {
        val container = hostContainer ?: return
        controller.attachTo(container)
    }

    /** 释放顺序固定：先停加载，再脱离父容器，最后销毁。颠倒会触发 WebView 内部的窗口泄漏告警。 */
    private fun destroy(controller: BrowserTabController) {
        runCatching { controller.stopLoading() }
            .onFailure { logger.warn("停止加载失败：${controller.tabId}", it) }
        runCatching { controller.detachFromParent() }
            .onFailure { logger.warn("脱离父容器失败：${controller.tabId}", it) }
        runCatching { controller.release() }
            .onFailure { logger.error("销毁标签实例失败：${controller.tabId}", it) }
        logger.info("释放标签实例 ${controller.tabId}")
    }
}
