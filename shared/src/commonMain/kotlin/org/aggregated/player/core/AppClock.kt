package org.aggregated.player.core

/**
 * 墙上时钟，返回 Unix 毫秒时间戳。
 *
 * 共享层只需要「可比较、可排序」的时间值（规则创建时间、标签 `lastActiveAt` 驱动的 LRU），
 * 因此不引入 kotlinx-datetime，少一个依赖就少一个跨 target 的兼容面。
 *
 * 注意：值不做单调性保证。系统时间被回拨时 [currentTimeMillis] 可能变小，
 * 所以 LRU 比较用「越小越旧」的相对语义，不计算经过时长；需要时长请用协程的 `measureTime`。
 */
expect fun currentTimeMillis(): Long
