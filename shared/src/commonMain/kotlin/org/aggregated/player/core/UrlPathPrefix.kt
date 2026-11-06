package org.aggregated.player.core

/**
 * URL 路径前缀匹配（**纯逻辑、无平台依赖**，故可放在 commonMain 并直接单测）。
 *
 * 存在的意义：防盗链头要按「URL 精确到路径」而不是「只按 host」匹配，
 * 否则同一站点的多部剧集/多集数会互相覆盖 token（详见 `HeaderInjectingDataSourceFactory`）。
 * 匹配规则刻意做成**路径段级**而非裸字符串前缀：
 * `/a/b` 命中 `/a/b` 与 `/a/b/c.ts`，但不命中 `/a/bc`——
 * 否则 `/video/ep1` 会误命中 `/video/ep10`。
 *
 * 另一个刻意的不作为：**只看 path、不看 query**。
 * 清单 URL 常带 `?token=`，而分片 URL 通常不带；若把 query 计入前缀，分片一条都匹配不上。
 */
object UrlPathPrefix {

    /** 路径段级前缀匹配：`/a/b` 命中 `/a/b` 与 `/a/b/c.ts`，但不命中 `/a/bc`。空前缀命中一切。 */
    fun matches(prefix: String, path: String): Boolean {
        if (prefix.isEmpty()) return true
        if (!path.startsWith(prefix)) return false
        if (prefix.endsWith("/")) return true
        return path.length == prefix.length || path[prefix.length] == '/'
    }

    /**
     * 在候选前缀中挑出**命中的最长者**（最具体的那条）；都没命中返回 null。
     *
     * 候选里同时存在「完整 path」与「所在目录」两种前缀时，最长者胜出：
     * 分片 `/a/seg.ts` 命中目录前缀 `/a`，而清单自身 `/a/index.m3u8` 命中更长的完整 path。
     */
    fun bestMatch(candidates: List<String>, path: String): String? =
        candidates.filter { matches(it, path) }.maxByOrNull { it.length }
}
