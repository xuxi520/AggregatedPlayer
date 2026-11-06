package org.aggregated.player

import android.os.Build

/**
 * Android 平台标识。
 *
 * [displayName] 只取 API 等级而不是机型名：排查问题时需要的是「这段行为在哪个 API 起变了」，
 * 机型名对定位 WebView 行为差异没有帮助。
 */
private class AndroidPlatformInfo : PlatformInfo {

    override val displayName: String = "Android API ${Build.VERSION.SDK_INT}"

    override val family: PlatformFamily = PlatformFamily.ANDROID
}

actual fun createPlatformInfo(): PlatformInfo = AndroidPlatformInfo()
