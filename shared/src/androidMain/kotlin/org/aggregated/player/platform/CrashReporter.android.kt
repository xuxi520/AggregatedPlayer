package org.aggregated.player.platform

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

private const val CRASH_FILE = "crash-latest.txt"
private const val TAG = "MPCrash"

/**
 * Android 实现：`UncaughtExceptionHandler` 装饰器——先把栈写进 `filesDir/crash-latest.txt`，
 * 再交回系统默认处理器（不接管进程退出，系统崩溃对话框照常弹）。
 *
 * 只保留最近一份：诊断看的是「复现闪退」的现场，不是历史归档；
 * 写文件本身全程 `runCatching`——崩溃现场任何二次异常都不允许打断交接流程。
 */
actual fun installCrashReporter() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { writeCrashFile(thread, throwable) }
            .onFailure { Log.e(TAG, "崩溃日志写盘失败", it) }
        previous?.uncaughtException(thread, throwable)
    }
}

actual fun takeLastCrashReport(): String? = runCatching {
    crashFile().takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
}.getOrNull()

actual fun clearCrashReport() {
    runCatching { crashFile().delete() }
}

private fun crashFile(): File = File(AndroidPlatformContext.requireContext().filesDir, CRASH_FILE)

private fun writeCrashFile(thread: Thread, throwable: Throwable) {
    val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
    crashFile().writeText("time=${System.currentTimeMillis()} thread=${thread.name}\n$stack")
}
