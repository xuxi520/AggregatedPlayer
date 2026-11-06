package org.aggregated.player.core

import android.util.Log

/**
 * 基于 `android.util.Log` 的实现。
 *
 * 每条输出都包了 try/catch：Log 本身极少抛异常，但在某些定制 ROM 上会，
 * 而日志把 WebView 主路径带崩是不可接受的（见接口约定 2）。
 */
private class AndroidLogger(private val tag: String) : Logger {

    override fun debug(message: String) {
        safely { Log.d(tag, message) }
    }

    override fun info(message: String) {
        safely { Log.i(tag, message) }
    }

    override fun warn(message: String, error: Throwable?) {
        safely { Log.w(tag, message, error) }
    }

    override fun error(message: String, error: Throwable?) {
        safely { Log.e(tag, message, error) }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            // 故意空实现：日志设施自身出错时静默放弃，不再产生二次日志
        }
    }
}

/**
 * Android 侧日志工厂。
 *
 * 注意：本实现依赖 `android.util.Log`，因此**不能**在 JVM 单元测试（`androidHostTest`）里直接调用，
 * 那是未 mock 的 Android 框架类。共享层的单测应使用假实现（见 `commonTest` 的 `FakeLogger`）。
 */
actual fun createLogger(tag: String): Logger = AndroidLogger(tag)
