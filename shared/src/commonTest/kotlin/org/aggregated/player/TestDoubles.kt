package org.aggregated.player

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.TabNavigationSnapshot
import org.aggregated.player.domain.model.UserAgentMode
import org.aggregated.player.platform.BrowserTabController
import org.aggregated.player.platform.BrowserTabControllerFactory
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 测试替身集合。
 *
 * 放在 `commonTest` 而不是各平台测试里：所有共享层的用例都要能在 Android 与 Desktop 上跑同一份代码。
 * 特别地，这里**不用**平台的 `createLogger()`——`android.util.Log` 在 JVM 单元测试里未 mock 会直接崩。
 */

/** 记录式日志器。断言只用于「确实报了错」这类行为，不要绑死具体文案。 */
class FakeLogger : Logger {

    /** 日志条目，按写入顺序。 */
    val entries: MutableList<Entry> = mutableListOf()

    data class Entry(val level: Level, val message: String, val error: Throwable?)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    val hasError: Boolean
        get() = entries.any { it.level == Level.ERROR }

    override fun debug(message: String) {
        entries += Entry(Level.DEBUG, message, null)
    }

    override fun info(message: String) {
        entries += Entry(Level.INFO, message, null)
    }

    override fun warn(message: String, error: Throwable?) {
        entries += Entry(Level.WARN, message, error)
    }

    override fun error(message: String, error: Throwable?) {
        entries += Entry(Level.ERROR, message, error)
    }
}

/**
 * 内存版 [JsonDocumentStore]。
 *
 * 不需要线程安全：仓储的读-改-写由 `JsonRecordStore` 内部的 Mutex 串行化，
 * 而 `runTest` 里每个用例都是顺序推进的。
 */
class InMemoryJsonDocumentStore : JsonDocumentStore {

    /** 直接暴露给用例，方便制造「文件存在但内容损坏」的场景。 */
    val files: MutableMap<String, String> = mutableMapOf()

    override val locationDescription: String = "in-memory"

    override fun read(relativePath: String): String? = files[relativePath]

    override fun write(relativePath: String, content: String) {
        files[relativePath] = content
    }
}

/** 可控时钟：让用例能构造出确定的新旧顺序，而不依赖真实时间。 */
class FakeClock(private var value: Long = 1_000L) {

    /** 每次调用自增固定步长，保证同一毫秒内的多次调用也能排出先后。 */
    fun now(): Long {
        value += STEP
        return value
    }

    private companion object {

        const val STEP = 10L
    }
}

/**
 * 假的标签实例：把 [BrowserTabController] 的每次调用录下来，供实例池用例断言。
 *
 * 有了它，「创建几次」「有没有多余的一次 loadUrl」「释放顺序对不对」
 * 这些只能跑在真 WebView 上的关键行为，都能在 JVM 里验完（设计文档 §4.2 第 3 条）。
 */
class FakeTabController(
    override val tabId: String,
    entryUrl: String,
) : BrowserTabController {

    /** 按顺序记录的调用日志，断言直接看它。 */
    val calls: MutableList<String> = mutableListOf("create:$entryUrl")

    override var isAlive: Boolean = true
        private set

    override var currentUrl: String? = null
        private set

    override var title: String? = null
        private set

    override var canGoBack: Boolean = false
        private set

    override var canGoForward: Boolean = false
        private set

    override var isLoading: Boolean = false
        private set

    /** 不透明的「视图」：用 tabId 做标识，方便断言挂到了哪个容器。 */
    override val platformView: Any = "view-$tabId"

    var parent: Any? = null
        private set

    var snapshot: TabNavigationSnapshot = TabNavigationSnapshot.EMPTY

    override fun loadUrl(url: String) {
        calls += "load:$url"
        currentUrl = url
    }

    override fun reload() {
        calls += "reload"
    }

    override fun updateUserAgentMode(mode: UserAgentMode, reload: Boolean) {
        calls += "updateUserAgentMode:$mode:$reload"
    }

    override fun stopLoading() {
        calls += "stopLoading"
    }

    override fun goBack() {
        calls += "goBack"
        canGoBack = false
        canGoForward = true
    }

    override fun goForward() {
        calls += "goForward"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun setVisibility(visible: Boolean) {
        calls += if (visible) "visible" else "hidden"
    }

    override fun attachTo(container: Any) {
        calls += "attach:$container"
        parent = container
    }

    override fun detachFromParent() {
        calls += "detach"
        parent = null
    }

    override fun navigationSnapshot(): TabNavigationSnapshot = snapshot

    override fun setAdMarkingMode(enabled: Boolean) {
        calls += "markingMode:$enabled"
    }

    override fun reapplyAdBlockStyles() {
        calls += "reapplyStyles"
    }

    override fun pickParent() {
        calls += "pickParent"
    }

    override fun pickChild() {
        calls += "pickChild"
    }

    override fun release() {
        calls += "release"
        isAlive = false
    }
}

/**
 * 实例工厂替身：统计创建次数，并允许让某个 tabId 创建失败，
 * 用来验「一个失败不要带崩整批效果」。
 */
class RecordingTabControllerFactory(
    private val failFor: Set<String> = emptySet(),
) : BrowserTabControllerFactory {

    val created: MutableList<FakeTabController> = mutableListOf()

    override fun create(tabId: String, entryUrl: String): BrowserTabController {
        if (tabId in failFor) error("模拟创建失败：$tabId")
        return FakeTabController(tabId, entryUrl).also { created += it }
    }

    /** 某个标签被创建了几次。保活的前提就是这里永远只应该是 0 或 1。 */
    fun createCount(tabId: String): Int = created.count { it.tabId == tabId }
}
