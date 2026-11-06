package org.aggregated.player

import org.aggregated.player.domain.model.TabNavigationSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 导航快照的用例。
 *
 * 重点是 [TabNavigationSnapshot.trimmed] 的索引平移：裁剪列表却忘记平移索引，
 * 会让 `currentUrl` 悄悄指向错误的页——这种错误不会抛异常，只会让 UI 显示错的「当前位置」，
 * 所以必须有用例钉住。
 */
class TabNavigationSnapshotTest {

    @Test
    fun emptySnapshotHasNoCurrentUrl() {
        val snapshot = TabNavigationSnapshot.EMPTY

        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.currentUrl)
        assertFalse(snapshot.canGoBack)
        assertFalse(snapshot.canGoForward)
    }

    @Test
    fun indexDrivesBackForwardAvailability() {
        val snapshot = TabNavigationSnapshot(listOf("a", "b", "c"), currentIndex = 1)

        assertEquals("b", snapshot.currentUrl)
        assertTrue(snapshot.canGoBack)
        assertTrue(snapshot.canGoForward)
        assertFalse(snapshot.copy(currentIndex = 0).canGoBack)
        assertFalse(snapshot.copy(currentIndex = 2).canGoForward)
    }

    @Test
    fun trimmingDropsOldestAndShiftsIndex() {
        val snapshot = TabNavigationSnapshot(listOf("1", "2", "3", "4", "5"), currentIndex = 4)

        val trimmed = snapshot.trimmed(limit = 3)

        assertEquals(listOf("3", "4", "5"), trimmed.urls)
        // 索引必须同步平移：否则裁剪后「当前页」会指向 5 而不是原来的 5
        assertEquals(2, trimmed.currentIndex)
        assertEquals("5", trimmed.currentUrl)
    }

    @Test
    fun trimmingKeepsCurrentPageEvenInTheMiddle() {
        val snapshot = TabNavigationSnapshot(listOf("1", "2", "3", "4", "5"), currentIndex = 2)

        val trimmed = snapshot.trimmed(limit = 3)

        assertEquals("3", trimmed.currentUrl)
    }

    @Test
    fun trimmingIsNoOpWhenWithinLimitOrLimitIsNegative() {
        val snapshot = TabNavigationSnapshot(listOf("1", "2"), currentIndex = 1)

        assertEquals(snapshot, snapshot.trimmed(limit = 5))
        assertEquals(snapshot, snapshot.trimmed(limit = -1))
    }

    @Test
    fun outOfRangeIndexIsClampedInsteadOfCrashing() {
        // 平台可能给出越界索引（历史列表刚被清空的一瞬），这里必须容错
        val snapshot = TabNavigationSnapshot(listOf("1", "2"), currentIndex = 9).trimmed(limit = 1)

        assertEquals(listOf("2"), snapshot.urls)
        assertEquals(-1, snapshot.currentIndex)
        assertNull(snapshot.currentUrl)
    }
}
