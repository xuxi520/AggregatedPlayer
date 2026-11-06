package org.aggregated.player.core

/** Android 与桌面用同一个实现即可：墙上时钟只需要「可比较的毫秒值」。 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
