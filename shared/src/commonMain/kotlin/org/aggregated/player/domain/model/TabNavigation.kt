package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 标签页的导航快照：**URL 序列 + 当前索引**（设计文档 §4.2 第 6 条）。
 *
 * 它替代了 v1.1 里「用 `saveHierarchyState` 保存历史栈」的想法。原因：
 * Android 保存前进/后退列表的正确 API 是 `saveState(Bundle)`/`restoreState(Bundle)`，
 * 而 Bundle 是平台私有格式，塞进 `tabs.json` 需要 Parcel→Base64，跨系统版本不可靠；
 * 且 `restoreState` 只允许在 WebView 尚未建立自身状态前调用。
 *
 * 因此这个快照**只用于展示与近似恢复**（例如 UI 上告诉用户「历史里有这几页」），
 * 不承诺能重建 bfcache：同进程保活本来就是继续浏览的正解，跨进程只恢复当前 URL。
 */
@Serializable
data class TabNavigationSnapshot(
    /** 由旧到新的访问序列。 */
    val urls: List<String> = emptyList(),

    /** 当前页在 [urls] 中的下标；空序列时为 -1。 */
    val currentIndex: Int = -1,
) {

    val isEmpty: Boolean
        get() = urls.isEmpty()

    val currentUrl: String?
        get() = urls.getOrNull(currentIndex)

    /** 索引合法性由调用方（平台拷贝历史列表时）保证，这里只做防御性判断。 */
    val canGoBack: Boolean
        get() = currentIndex > 0

    val canGoForward: Boolean
        get() = currentIndex in 0 until urls.lastIndex

    /**
     * 限制条目数，防止 `tabs.json` 被长历史撑爆。
     *
     * 从**头部**丢弃（保留靠近当前页的记录），并同步平移 [currentIndex]——
     * 忘记平移索引是这类裁剪最常见的错误，裁剪后 `currentUrl` 会指向错误的页。
     * 平移后越界（平台给了个不存在的索引）时归 `-1` 表示「不知道在哪页」，
     * 而不是强行钉到开头或末尾——猜一个错的当前页比承认不知道更误导 UI。
     */
    fun trimmed(limit: Int): TabNavigationSnapshot {
        if (limit < 0 || urls.size <= limit) return this
        val dropCount = urls.size - limit
        val shifted = currentIndex - dropCount
        val kept = urls.drop(dropCount)
        val index = if (shifted < 0 || shifted >= kept.size) -1 else shifted
        return copy(urls = kept, currentIndex = index)
    }

    companion object {

        val EMPTY = TabNavigationSnapshot()
    }
}
