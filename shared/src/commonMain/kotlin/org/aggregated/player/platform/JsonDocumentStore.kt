package org.aggregated.player.platform

/**
 * 本地 JSON 文件的读写能力（设计文档 §4.7）。
 *
 * 抽象只到「整份文本读 / 原子写」这一层：目录选择、原子替换这些平台差异留在 actual 里，
 * `commonMain` 的仓储实现只管 JSON 结构，未来换成 SQLDelight 也只改 `data/` 包。
 *
 * 线程约定：方法都是**阻塞**的。共享层调用方必须放到后台协程里执行
 * （`commonMain` 没有 `Dispatchers.IO`，统一用 `Dispatchers.Default`，见 §4.8）。
 */
interface JsonDocumentStore {

    /** 数据目录的可读描述，只用于日志与排障；不要把绝对路径暴露到 UI 文案里。 */
    val locationDescription: String

    /**
     * 读取 [relativePath] 下的文本内容。
     *
     * @return 文件内容；文件不存在时返回 `null`——首次运行是正常状态，不是错误。
     * @throws java.io.IOException 读取失败（权限、磁盘故障）。调用方需降级处理，不能让它击穿 UI。
     */
    fun read(relativePath: String): String?

    /**
     * 原子写入：先写临时文件再替换目标文件。
     *
     * 之所以要求原子性：应用被系统杀死（内存压力回收很常见）时，
     * 半截 JSON 会让站点列表、广告规则全部读不出来，用户感知是「配置莫名丢失」。
     */
    fun write(relativePath: String, content: String)
}

/**
 * 创建平台数据目录的实现。
 *
 * Android 使用 `context.filesDir`，因此要求 `PlayerApplication` 先完成平台上下文初始化
 * （见设计文档 §6.3）；Desktop 使用 `userHome/.aggregated-player`。
 */
expect fun createJsonDocumentStore(): JsonDocumentStore
