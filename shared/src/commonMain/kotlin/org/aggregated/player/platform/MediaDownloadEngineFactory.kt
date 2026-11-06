package org.aggregated.player.platform

import org.aggregated.player.domain.repository.MediaDownloadEngine

/**
 * 创建当前平台的下载引擎（README §6：需平台参数用 `expect fun create…()` 工厂，由 `AppGraph` 装配）。
 *
 * Android 从 `AndroidPlatformContext`（Application 级，无 Activity 泄漏）取 `filesDir`；
 * 桌面端阶段 4-A 返回占位实现，明确回报「阶段 5 接入」，不静默假装成功。
 * 平台上下文必须早于 `AppGraph.create()` 初始化。
 */
expect fun createMediaDownloadEngine(): MediaDownloadEngine
