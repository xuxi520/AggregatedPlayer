package org.aggregated.player.platform

import android.content.Intent
import android.net.Uri
import android.util.Log
import org.aggregated.player.domain.model.MediaKind

private const val CHOOSER_TITLE = "推送给其他播放器"
private const val LOG_TAG = "MediaPush"

/**
 * Android 实现：`ACTION_VIEW` + MIME + 系统选择器。
 *
 * MIME 按 [MediaKind] 精确给出，选择器才会优先列出播放器类应用而不是浏览器；
 * `FLAG_ACTIVITY_NEW_TASK` 是必须的——`AndroidPlatformContext` 给的是 Application 级上下文，
 * 没有 Activity 任务栈可依附。
 */
actual fun pushMediaToExternalPlayer(request: PushMediaRequest) {
    val mime = when (request.kind) {
        MediaKind.HLS -> "application/vnd.apple.mpegurl"
        MediaKind.DASH -> "application/dash+xml"
        else -> "video/*"
    }
    val target = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(request.url), mime)
        putExtra(Intent.EXTRA_TITLE, request.title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(target, CHOOSER_TITLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { AndroidPlatformContext.requireContext().startActivity(chooser) }
        .onFailure { Log.w(LOG_TAG, "推送失败：设备上可能没有可接收的应用", it) }
}
