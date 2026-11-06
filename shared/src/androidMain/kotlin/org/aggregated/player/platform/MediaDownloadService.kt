package org.aggregated.player.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.media3.common.util.UnstableApi
import org.aggregated.player.shared.R
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

private const val NOTIFICATION_CHANNEL_ID = "aggregated_offline_download"
private const val NOTIFICATION_ID = 2001
/** 前台通知刷新间隔（毫秒）：进度条不必每帧更新 */
private const val NOTIFICATION_UPDATE_MS = 1000L
/** PlatformScheduler 的 JobScheduler jobId（正数、全局唯一即可） */
private const val DOWNLOAD_JOB_ID = 1001
private const val TAG = "MPOffline"

/**
 * Media3 离线下载的前台服务宿主（阶段 4-D，HLS/DASH 分片下载保活 + 通知）。
 *
 * 放在 shared（而非 app 模块）：下载调度 `DownloadService.sendAddDownload` 需要本类的 `Class`，
 * 而 shared 不能反向依赖 app 模块，因此 Service 与下载单例同处 shared。
 *
 * 下载经它跑在前台：Android 14 起前台服务必须声明 `dataSync` 类型（见 shared AndroidManifest），
 * 否则 `startForeground` 抛异常；通知栏进度由自建 `DownloadNotificationHelper` 生成。
 * 注意 `DownloadNotificationHelper` 只按 channelId 组装通知、并不创建渠道，故在 `onCreate` 自建。
 * `getScheduler()` 返回 `PlatformScheduler`：系统级调度（跨重启/网络恢复自动续传），
 * 需 `RECEIVE_BOOT_COMPLETED` 权限 + `PlatformSchedulerService` 声明（见 shared AndroidManifest）。
 */
@UnstableApi
class MediaDownloadService :
    // 第 4/5 参是「通知渠道名字符串资源 ID」与「通知小图标资源 ID」——Media3 用它们建通知渠道，
    // 传 0 会在 onCreate 里 getString(0) 崩在服务主线程（下载闪退的根因）。必须给有效资源 ID。
    DownloadService(
        NOTIFICATION_ID,
        NOTIFICATION_UPDATE_MS,
        NOTIFICATION_CHANNEL_ID,
        R.string.download_channel_name,
        android.R.drawable.stat_sys_download,
    ) {

    private val notificationHelper by lazy { DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = Media3Offline.downloadManager

    // 跨重启/网络恢复自动续传：PlatformScheduler 把下载任务交给系统 JobScheduler 持久化，
    // 设备重启或网络恢复后由 PlatformSchedulerService 回调 DownloadService 自动恢复队列。
    // 需 RECEIVE_BOOT_COMPLETED 权限 + PlatformSchedulerService 声明（见 shared AndroidManifest）。
    override fun getScheduler(): Scheduler = PlatformScheduler(this, DOWNLOAD_JOB_ID)

    @Suppress("TooGenericExceptionCaught")
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        try {
            notificationHelper.buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                null,
                null,
                downloads,
                notMetRequirements,
            )
        } catch (e: Exception) {
            // 兜底：通知构建失败绝不能冒泡（否则 startForeground(null) 直接闪退）。
            // 退路返回一个最小可用通知，保证前台服务能正常拉起。
            Log.e(TAG, "构建下载前台通知失败", e)
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("离线下载")
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        // DownloadNotificationHelper 不会创建渠道，必须自行注册（API 26+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "离线下载",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }
}
