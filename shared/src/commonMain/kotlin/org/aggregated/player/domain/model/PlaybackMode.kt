package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 页面的默认播放引擎偏好（设计文档 §4.5，阶段 3-B）。
 *
 * 两级覆盖：全局默认写在 [AppSettings.defaultPlaybackMode]，站点可用
 * [Site.playbackMode] 单独指定；`null` 表示跟随全局。默认恒为 [WEBVIEW] ——
 * 增强引擎是「增强而非替代」，绝不在用户没表态时抢走站点原生播放器。
 *
 * 这里的「引擎」指**页面内覆盖层**（Embedded），不是另起一个全屏 Activity：
 * 覆盖层与 WebView 同屏叠放，关闭即回到原页面，播放状态与滚动位置都不丢。
 */
@Serializable
enum class PlaybackMode {

    /** 只用站点原生 WebView 播放器；用户仍可手动点嗅探结果里的 ▶ 临时启用增强引擎。 */
    WEBVIEW,

    /** 嗅探到可播放资源时自动用覆盖层接管（每条候选只处理一次，避免反复弹出）。 */
    ENHANCED,

    /** 嗅探到可播放资源时先征询，用户确认后才开覆盖层。 */
    ASK,
}
