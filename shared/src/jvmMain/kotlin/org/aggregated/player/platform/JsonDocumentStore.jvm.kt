package org.aggregated.player.platform

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 桌面的 [JsonDocumentStore] 实现，落在 `~/.aggregated-player/`（设计文档 §4.7）。
 *
 * 放用户目录而不是安装目录：安装目录在 Windows 上通常需要管理员权限才能写，
 * 而且换目录/绿色版移动后配置就找不到了。
 */
private class JvmFileStore(private val rootDir: Path) : JsonDocumentStore {

    override val locationDescription: String = rootDir.toString()

    override fun read(relativePath: String): String? {
        val file = resolve(relativePath)
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    /**
     * 原子替换：同目录临时文件 + `ATOMIC_MOVE`。
     *
     * 跨文件系统移动（`ATOMIC_MOVE` 抛 [AtomicMoveNotSupportedException]）时退化为普通替换：
     * 同目录创建的临时文件理论上不会跨分区，这条兜底只为极端环境（网络盘、RAMDISK）保留可用性。
     */
    override fun write(relativePath: String, content: String) {
        val target = resolve(relativePath)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(temp, content)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resolve(relativePath: String): Path {
        val normalized = relativePath.replace('\\', '/')
        require(!normalized.contains("..")) { "非法的相对路径：$relativePath" }
        val file = rootDir.resolve(normalized).normalize()
        require(file.startsWith(rootDir)) { "路径越界：$relativePath" }
        return file
    }
}

/** 桌面端不要求额外初始化：用户目录总是可用（不可写时由 IO 异常暴露给上层日志）。 */
actual fun createJsonDocumentStore(): JsonDocumentStore =
    JvmFileStore(Paths.get(System.getProperty("user.home"), DATA_DIR_NAME))

private const val DATA_DIR_NAME = ".aggregated-player"
