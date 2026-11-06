package org.aggregated.player.di

import org.aggregated.player.core.Logger
import org.aggregated.player.core.createLogger
import org.aggregated.player.core.currentTimeMillis
import org.aggregated.player.createPlatformInfo
import org.aggregated.player.data.JsonAdRuleRepository
import org.aggregated.player.data.JsonDownloadRepository
import org.aggregated.player.data.JsonSiteRepository
import org.aggregated.player.data.JsonSettingsRepository
import org.aggregated.player.data.JsonTabSessionRepository
import org.aggregated.player.domain.model.KeepAlivePolicy
import org.aggregated.player.domain.repository.AdRuleRepository
import org.aggregated.player.domain.repository.DownloadRepository
import org.aggregated.player.domain.repository.SettingsRepository
import org.aggregated.player.domain.repository.SiteRepository
import org.aggregated.player.domain.repository.TabSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.aggregated.player.domain.usecase.AdRuleEngine
import org.aggregated.player.domain.usecase.DownloadCoordinator
import org.aggregated.player.domain.usecase.MediaSniffer
import org.aggregated.player.domain.usecase.SniffEnricher
import org.aggregated.player.domain.usecase.TabManager
import org.aggregated.player.platform.ImmersiveSignals
import org.aggregated.player.platform.JsonDocumentStore
import org.aggregated.player.platform.LoggingTabEffectExecutor
import org.aggregated.player.platform.TabEffectExecutor
import org.aggregated.player.platform.createJsonDocumentStore
import org.aggregated.player.platform.createMediaDownloadEngine
import org.aggregated.player.platform.createSniffProbeEngine
import org.aggregated.player.platform.web.MediaCaptureBridge

/**
 * 手工依赖装配根。
 *
 * 不引入 Koin/Hilt：本项目依赖图很小（十来个对象），显式装配更容易审查
 * 「平台能力有没有被正确注入、注入的是不是占位实现」，也避免 DI 框架与 KMP 版本的绑定成本。
 *
 * 构造完成后对象图**不再变化**：所有可变状态（标签、嗅探队列）都在各自的持有者内部，
 * 因此 [create] 之后的读取都是无锁的。
 *
 * @param tabExecutor 标签效果的执行端。平台宿主（Android）传入自己的实例池；
 *   不传则是只记日志的默认实现，保证桌面端在 WebView 池接入前仍然可运行。
 * @param policy 保活策略，默认 [KeepAlivePolicy.Unbounded]（设计文档 Q2 定档）。
 * @param immersive 沉浸式瞬时信号（滚动隐显等）。平台宿主传入自己那份以便推送；
 *   不传则用默认实例，桌面端因无 WebView 滚动源而恒为「未滚动」。
 */
class AppGraph(
    val logger: Logger,
    val documentStore: JsonDocumentStore,
    val siteRepository: SiteRepository,
    val adRuleRepository: AdRuleRepository,
    val tabSessionRepository: TabSessionRepository,
    val downloadRepository: DownloadRepository,
    val settingsRepository: SettingsRepository,
    val tabManager: TabManager,
    val adRuleEngine: AdRuleEngine,
    val mediaSniffer: MediaSniffer,
    val downloadCoordinator: DownloadCoordinator,
    val tabExecutor: TabEffectExecutor,
    val immersive: ImmersiveSignals,
    val platformDisplayName: String,
) {

    companion object {

        /**
         * 装配整个对象图。
         *
         * 平台上下文的初始化必须早于本方法（Android 见 `PlayerApplication`，§6.3），
         * 否则 [createJsonDocumentStore] 会直接抛出带明确提示的异常。
         */
        fun create(
            policy: KeepAlivePolicy = KeepAlivePolicy.DEFAULT,
            tabExecutor: TabEffectExecutor? = null,
            sniffQueueCapacity: Int = MediaSniffer.DEFAULT_CAPACITY,
            immersive: ImmersiveSignals = ImmersiveSignals(),
        ): AppGraph {
            val platform = createPlatformInfo()
            val rootLogger = createLogger("AppGraph")
            val store = createJsonDocumentStore()
            val clock: () -> Long = { currentTimeMillis() }

            rootLogger.info("启动装配：平台=${platform.displayName}，数据目录=${store.locationDescription}")

            val siteRepository = JsonSiteRepository(store, createLogger("SiteRepository"), clock)
            val adRuleRepository = JsonAdRuleRepository(store, createLogger("AdRuleRepository"), clock)
            val tabSessionRepository = JsonTabSessionRepository(store, createLogger("TabSessionRepository"), clock)
            val downloadRepository = JsonDownloadRepository(store, createLogger("DownloadRepository"), clock)
            val settingsRepository = JsonSettingsRepository(store, createLogger("SettingsRepository"))

            // 执行器由宿主传入时用装饰者包一层日志，保证「效果从哪来到哪去」在两端都可追溯
            val executor = LoggingTabEffectExecutor(createLogger("TabEffects"), tabExecutor)
            val mediaSniffer = MediaSniffer(
                logger = createLogger("MediaSniffer"),
                clock = clock,
                capacity = sniffQueueCapacity,
            )
            // 嗅探候选的落地口：平台控制器不持有 AppGraph，经独立桥接把请求交给共享层队列
            MediaCaptureBridge.onCandidate = { mediaSniffer.ingest(it) }
            // 下载协调器自带进程级协程作用域：任务生命周期跟随 AppGraph，不跟随某个 Compose 组合，
            // 因此用户离开设置页甚至切标签时下载仍继续。平台引擎由 expect 工厂注入（§6.2）。
            val downloadCoordinator = DownloadCoordinator(
                repository = downloadRepository,
                engine = createMediaDownloadEngine(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),

                clock = clock,
            )
            // 嗅探增强与协调器同款：进程级作用域 + expect 工厂注入，生命周期跟随 AppGraph
            SniffEnricher(
                sniffer = mediaSniffer,
                probe = createSniffProbeEngine(),
                logger = createLogger("SniffEnricher"),
            ).observe(CoroutineScope(SupervisorJob() + Dispatchers.Default))

            return AppGraph(
                logger = rootLogger,
                documentStore = store,
                siteRepository = siteRepository,
                adRuleRepository = adRuleRepository,
                tabSessionRepository = tabSessionRepository,
                downloadRepository = downloadRepository,
                settingsRepository = settingsRepository,
                tabManager = TabManager(
                    logger = createLogger("TabManager"),
                    clock = clock,
                    policy = policy,
                ),
                adRuleEngine = AdRuleEngine(createLogger("AdRuleEngine")),
                mediaSniffer = mediaSniffer,
                downloadCoordinator = downloadCoordinator,
                tabExecutor = executor,
                immersive = immersive,
                platformDisplayName = platform.displayName,
            )
        }
    }
}
