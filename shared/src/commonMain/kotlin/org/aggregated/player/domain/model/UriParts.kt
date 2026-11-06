package org.aggregated.player.domain.model

/**
 * URL 的极简解析结果。
 *
 * 为什么手写而不是用 `java.net.URI`：`commonMain` 还要为将来的 iOS target 编译，
 * JVM 专用 API 用不了；而广告规则匹配与媒体嗅探只需要**主机名后缀**与**路径前缀/扩展名**，
 * 不需要完整的 RFC 实现。
 *
 * 明确不处理：百分号解码、国际化域名（punycode）、`userinfo`、相对 URL。
 * 若后续需要精确匹配，应引入成熟 URL 库替换本类，而不是继续往这里加特例。
 */
data class UriParts(
    /** 小写协议名，不含冒号；解析不到时为 `null`。 */
    val scheme: String?,
    /** 小写主机名，不含端口与 `userinfo`；无主机形式（`data:`）时为 `null`。 */
    val host: String?,
    /** 端口；未显式指定时为 `null`（不推导默认端口）。 */
    val port: Int?,
    /** 路径，通常以 `/` 开头；不含 query 与 fragment。 */
    val path: String,
    /** 查询串，不含 `?`；无查询时为空串。 */
    val query: String,
) {

    /**
     * [domain] 是否命中本 URL 的主机：相等，或本主机是 [domain] 的子域。
     *
     * 例：`player.example.com` 命中 `example.com`，但 `notexample.com` **不**命中
     * ——必须靠「点边界」判断，否则规则会误伤同前缀域名，这是 ABP `||` 语义的核心。
     */
    fun hostMatches(domain: String): Boolean {
        val self = host ?: return false
        val target = domain.lowercase().trim { it <= ' ' || it == '/' }
        if (target.isEmpty()) return false
        return self == target || self.endsWith(".$target")
    }

    /**
     * 路径最后一段的小写扩展名（不含点），取不到时返回空串。
     *
     * 局限：`/video.mp4/download` 这类「扩展名不在末尾」的路径会返回 `download`。
     * 嗅探器另有整串包含判断（见 `MediaSniffer`）来覆盖这种写法，这里不做二次猜测。
     */
    val fileExtension: String
        get() {
            val lastSegment = path.substringAfterLast('/')
            val dot = lastSegment.lastIndexOf('.')
            if (dot < 0 || dot == lastSegment.lastIndex) return ""
            val ext = lastSegment.substring(dot + 1).lowercase()
            // 只接受「纯字母数字且不超过 6 位」，避免把 `/v1.2/xxx` 里的片段当扩展名
            return if (ext.length <= MAX_EXTENSION_LENGTH && ext.all { it.isLetterOrDigit() }) ext else ""
        }

    companion object {

        private const val MAX_EXTENSION_LENGTH = 6

        /**
         * 解析 [raw]。
         *
         * @return 解析结果；[raw] 为空白时返回 `null`。空主机不会被当成「命中所有域名规则」的依据，
         * 因此 `http://` 这种畸形输入在这里就归一化成 `host = null`。
         */
        fun parse(raw: String): UriParts? {
            var work = raw.trim()
            if (work.isEmpty()) return null

            // `blob:` 里再套一层真实 URL，剥掉外层协议后继续解析；data: 无主机，直接给空结构
            if (work.startsWith("blob:", ignoreCase = true)) {
                work = work.substring("blob:".length)
            } else if (work.startsWith("data:", ignoreCase = true)) {
                // data: 没有主机与路径概念，给一个空结构让上层统一处理
                return UriParts(scheme = "data", host = null, port = null, path = "", query = "")
            }

            val withoutFragment = work.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val beforeQuery = withoutFragment.substringBefore('?')

            val schemeSeparator = beforeQuery.indexOf("://")
            val scheme = if (schemeSeparator > 0) beforeQuery.substring(0, schemeSeparator).lowercase() else null
            val rest = if (schemeSeparator > 0) beforeQuery.substring(schemeSeparator + "://".length) else beforeQuery

            val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            val path = if (authorityEnd < rest.length) rest.substring(authorityEnd) else ""

            // 去掉 userinfo（只保留最后一个 @ 之后的内容，避免用户名里含 ':' 的解析歧义）
            val hostPort = authority.substringAfterLast('@')
            val host: String?
            val port: Int?
            when {
                // IPv6 字面量：`[::1]:8080` 里的冒号属于主机内部，不能按 firstColon 切分
                hostPort.startsWith('[') -> {
                    val close = hostPort.indexOf(']')
                    host = if (close > 0) hostPort.substring(1, close).lowercase() else null
                    // 取 `]` 之后的部分并去掉端口分隔符，否则 ":8080" 会因为冒号被判成非法端口
                    port = parsePort(hostPort.substringAfterLast(']', "").removePrefix(":"))
                }

                else -> {
                    val firstColon = hostPort.indexOf(':')
                    if (firstColon < 0) {
                        host = hostPort.lowercase().ifEmpty { null }
                        port = null
                    } else {
                        host = hostPort.substring(0, firstColon).lowercase().ifEmpty { null }
                        port = parsePort(hostPort.substring(firstColon + 1))
                    }
                }
            }

            return UriParts(
                scheme = scheme,
                host = host?.trimEnd('.'),
                port = port,
                path = path.ifEmpty { "/" },
                query = query,
            )
        }

        private fun parsePort(text: String): Int? =
            // 端口必须是纯数字，`/` 之后的残留（如 `:8080/x` 未截断的边界情况）会让 toIntOrNull 失败并返回 null
            text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }
}
