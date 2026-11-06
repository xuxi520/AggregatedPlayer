package org.aggregated.player.platform

import org.aggregated.player.domain.repository.SniffProbeEngine

/**
 * 创建当前平台的清单探测引擎（README §6：需平台参数用 `expect fun create…()` 工厂，
 * 由 `AppGraph` 装配；桌面端返回 no-op 实现）。
 */
expect fun createSniffProbeEngine(): SniffProbeEngine
