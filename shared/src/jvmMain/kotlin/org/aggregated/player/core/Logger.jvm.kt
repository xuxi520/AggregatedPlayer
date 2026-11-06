package org.aggregated.player.core

import java.util.logging.Level
import java.util.logging.Logger as SystemLogger

/**
 * 基于 `java.util.logging` 的桌面实现。
 *
 * 已知坑（阶段 5 排查问题时先看这里）：JUL 默认的 ConsoleHandler 级别是 INFO，
 * 所以 [debug] 输出的 FINE 级别日志默认**看不到**。需要调试时加 JVM 参数：
 * `-Djava.util.logging.config.file=logging.properties` 里写
 * `org.aggregated.player.level=ALL`，或直接设 `-Dorg.aggregated.player.core.Logger.level=ALL`。
 * 不在这里强行改全局日志配置，是因为它会污染宿主应用（桌面端将来可能嵌入别的进程）。
 */
private class JULLogger(private val delegate: SystemLogger) : Logger {

    override fun debug(message: String) {
        safely { delegate.log(Level.FINE, message) }
    }

    override fun info(message: String) {
        safely { delegate.log(Level.INFO, message) }
    }

    override fun warn(message: String, error: Throwable?) {
        safely {
            if (error == null) delegate.log(Level.WARNING, message)
            else delegate.log(Level.WARNING, message, error)
        }
    }

    override fun error(message: String, error: Throwable?) {
        safely {
            if (error == null) delegate.log(Level.SEVERE, message)
            else delegate.log(Level.SEVERE, message, error)
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
            // 日志设施自身出错时静默放弃（接口约定 2）
        }
    }
}

actual fun createLogger(tag: String): Logger = JULLogger(SystemLogger.getLogger(TAG_PREFIX + tag))

private const val TAG_PREFIX = "org.aggregated.player."
