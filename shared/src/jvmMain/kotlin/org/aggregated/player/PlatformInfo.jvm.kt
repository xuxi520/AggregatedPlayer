package org.aggregated.player

/**
 * 桌面（JVM）平台标识。
 *
 * 取 `java.version` 而不是操作系统名：桌面端的行为差异主要来自 JDK（WebView2/JCEF 的绑定、
 * 文件 API 支持），操作系统名在 [displayName] 里意义有限，阶段 5 需要时再加字段。
 */
private class JvmPlatformInfo : PlatformInfo {

    override val displayName: String = "Java ${System.getProperty("java.version") ?: "unknown"}"

    override val family: PlatformFamily = PlatformFamily.DESKTOP
}

actual fun createPlatformInfo(): PlatformInfo = JvmPlatformInfo()
