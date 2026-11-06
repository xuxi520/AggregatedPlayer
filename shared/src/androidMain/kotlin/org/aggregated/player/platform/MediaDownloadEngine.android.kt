package org.aggregated.player.platform

import org.aggregated.player.domain.repository.MediaDownloadEngine

/** Android：Application 级上下文取 `filesDir`，不持有 Activity，避免生命周期泄漏。 */
actual fun createMediaDownloadEngine(): MediaDownloadEngine =
    AndroidMediaDownloader(AndroidPlatformContext.requireContext())
