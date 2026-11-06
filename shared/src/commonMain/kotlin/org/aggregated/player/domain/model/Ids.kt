package org.aggregated.player.domain.model

import kotlin.random.Random

/**
 * 共享层的 id 生成。
 *
 * `commonMain` 没有跨平台的 UUID API（`java.util.UUID` 仅 JVM），
 * 而这里的 id 只需要「本地唯一、可读、便于日志追踪」，不需要全局抗碰撞，
 * 所以用 `时间戳-随机段` 的形式；随机段基于 `kotlin.random.Random`，在 commonMain 可用。
 *
 * 若将来要做多端同步合并，需要换成有强抗碰撞保证的方案（如 ULID），届时只改本文件。
 */
object Ids {

    private const val RANDOM_RADIX = 36

    /** 随机段的位数上限：2^30 约 10 亿，足够本地唯一且字符串短。 */
    private const val RANDOM_BITS = 30

    /**
     * @param prefix 语义前缀，取实体名（`site` / `tab` / `rule` / `media` / `dl`），让 id 一眼可辨类型。
     * @param timestamp 调用方注入的时间戳，便于单测传入固定值。
     */
    fun next(prefix: String, timestamp: Long): String {
        val suffix = Random.nextInt(1 shl RANDOM_BITS).toString(RANDOM_RADIX)
        return "$prefix-${timestamp.toString(RANDOM_RADIX)}-$suffix"
    }
}
