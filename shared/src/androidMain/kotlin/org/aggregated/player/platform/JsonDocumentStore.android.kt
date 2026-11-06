package org.aggregated.player.platform

import java.io.File

/**
 * Android 的 [JsonDocumentStore] 实现，落在应用内部存储的
 * `filesDir` 下 `aggregated-player` 目录里（设计文档 §4.7）。
 *
 * 选内部存储而不是外部下载目录：这些是应用配置，不该被其它应用改写，
 * 也不该在卸载后残留（用户装了又删时留一堆 JSON 在公共目录是不礼貌的）。
 * 真正的媒体文件下载走系统下载目录，那是阶段 4 的另一个话题。
 */
private class AndroidJsonDocumentStore(private val rootDir: File) : JsonDocumentStore {

    override val locationDescription: String = rootDir.absolutePath

    override fun read(relativePath: String): String? {
        val file = resolve(relativePath)
        // exists() 与 readText() 之间的窗口极小且不可恢复，这里不为它加锁：读不到就按首次运行处理
        return if (file.isFile) file.readText() else null
    }

    override fun write(relativePath: String, content: String) {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(content)

        // 先删后 rename：部分文件系统（含某些 ext4/f2fs 配置）上 renameTo 不覆盖已存在的目标文件
        if (file.exists() && !file.delete()) {
            temp.delete()
            throw java.io.IOException("无法替换旧配置文件：${file.name}")
        }
        if (!temp.renameTo(file)) {
            // 兜底路径：直接覆盖写。失去原子性，但「写入失败」比「非原子写入」更糟
            file.writeText(content)
            temp.delete()
        }
    }

    /**
     * 解析并校验路径。
     *
     * 这里的 `..` 校验属于纵深防御：共享层传入的都是编译期常量文件名，
     * 但一旦有人后续把用户输入拼进路径，没有这道校验就会写出目录外的文件。
     */
    private fun resolve(relativePath: String): File {
        require(!relativePath.contains("..")) { "非法的相对路径：$relativePath" }
        val file = File(rootDir, relativePath)
        require(file.canonicalPath.startsWith(rootDir.canonicalPath)) { "路径越界：$relativePath" }
        return file
    }
}

/**
 * 数据目录名。与桌面端保持一致，方便用户跨端手动搬迁配置。
 *
 * 平台上下文必须由 `PlayerApplication` 初始化，否则 [AndroidPlatformContext.requireContext] 抛出
 * 带修复指引的异常（§6.3）。
 */
actual fun createJsonDocumentStore(): JsonDocumentStore {
    val root = File(AndroidPlatformContext.requireContext().filesDir, DATA_DIR_NAME)
    return AndroidJsonDocumentStore(root)
}

private const val DATA_DIR_NAME = "aggregated-player"
