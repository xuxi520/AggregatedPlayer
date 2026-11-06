package org.aggregated.player.platform

import org.aggregated.player.domain.repository.SniffProbeEngine

/** Android 侧注入 [AndroidSniffProbeEngine]（HttpURLConnection 直连，纪律见其 KDoc）。 */
actual fun createSniffProbeEngine(): SniffProbeEngine = AndroidSniffProbeEngine()
