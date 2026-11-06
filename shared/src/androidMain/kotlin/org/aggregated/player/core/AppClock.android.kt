package org.aggregated.player.core

/**
 * Android 走 `System.currentTimeMillis()`：这是唯一在 API 24 全版本可用的墙上时钟，
 * `System.nanoTime()` 是单调时钟、不能用于持久化的 `createdAt` 字段。
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
