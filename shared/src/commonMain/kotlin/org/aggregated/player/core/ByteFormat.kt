package org.aggregated.player.core

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
private const val BYTES_PER_GB = BYTES_PER_MB * BYTES_PER_MB

/** 小数位数缩放因子（保留一位小数 → 放大 10 倍做整数运算）。 */
private const val TENTHS = 10L

/**
 * 字节数 → 人类可读体积（跨端共用，故放 commonMain）。
 *
 * 用途：下载产物大小、离线缓存占用、「已释放 XX 空间」这类提示文案。
 * 格式不带空格（`1.2GB` / `512B` / `30MB`），与界面里其余中文文案排版一致。
 */
object ByteFormat {

    /** 把字节数格式化成 `B`/`KB`/`MB`/`GB`；不足 1KB 显示原始字节数。 */
    fun readable(bytes: Long): String = when {
        bytes < BYTES_PER_KB -> "${bytes.coerceAtLeast(0L)}B"
        bytes < BYTES_PER_MB -> "${withOneDecimal(bytes, BYTES_PER_KB)}KB"
        bytes < BYTES_PER_GB -> "${withOneDecimal(bytes, BYTES_PER_MB)}MB"
        else -> "${withOneDecimal(bytes, BYTES_PER_GB)}GB"
    }

    /** 保留一位小数；正好整除时不显示小数点（`1GB` 而不是 `1.0GB`）。 */
    private fun withOneDecimal(bytes: Long, unit: Long): String {
        val tenths = (bytes.coerceAtLeast(0L) * TENTHS) / unit
        val whole = tenths / TENTHS
        val fraction = tenths % TENTHS
        return if (fraction == 0L) "$whole" else "$whole.$fraction"
    }
}
