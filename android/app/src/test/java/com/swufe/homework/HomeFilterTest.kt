package com.swufe.homework

import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.ui.homework.HomeFilter
import com.swufe.homework.ui.homework.HomeRange
import com.swufe.homework.util.Ddl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** WS11 第 1 条：首页「今天 / 明天 / 本周 / 全部」分组与 24 小时标红（Asia/Shanghai）。 */
class HomeFilterTest {
    private fun sh(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, Ddl.ZONE)

    // 2026-10-21 是周三
    private val wedNoon = sh(2026, 10, 21, 12, 0)

    private fun a(id: Int, ddl: ZonedDateTime, done: Boolean = false) =
        AssignmentSummary(id = id, classId = 1, title = "t$id", ddl = Ddl.toApi(ddl), done = done)

    // ---- 今天 / 明天：23:59 与次日 00:01 边界 ----
    @Test fun todayIncludesUntil2359() {
        assertTrue(HomeFilter.inRange(sh(2026, 10, 21, 0, 0), HomeRange.Today, wedNoon))
        assertTrue(HomeFilter.inRange(sh(2026, 10, 21, 23, 59), HomeRange.Today, wedNoon))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 22, 0, 1), HomeRange.Today, wedNoon))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 22, 0, 0), HomeRange.Today, wedNoon))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 20, 23, 59), HomeRange.Today, wedNoon))
    }

    @Test fun tomorrowIsNextCalendarDay() {
        assertFalse(HomeFilter.inRange(sh(2026, 10, 21, 23, 59), HomeRange.Tomorrow, wedNoon))
        assertTrue(HomeFilter.inRange(sh(2026, 10, 22, 0, 1), HomeRange.Tomorrow, wedNoon))
        assertTrue(HomeFilter.inRange(sh(2026, 10, 22, 23, 59), HomeRange.Tomorrow, wedNoon))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 23, 0, 1), HomeRange.Tomorrow, wedNoon))
    }

    // ---- 本周 = 今天到本周日 23:59 ----
    @Test fun weekRunsToSunday2359() {
        assertTrue(HomeFilter.inRange(sh(2026, 10, 21, 8, 0), HomeRange.Week, wedNoon))
        assertTrue(HomeFilter.inRange(sh(2026, 10, 25, 23, 59), HomeRange.Week, wedNoon)) // 周日 23:59
        assertFalse(HomeFilter.inRange(sh(2026, 10, 26, 0, 1), HomeRange.Week, wedNoon))  // 下周一 00:01
        assertFalse(HomeFilter.inRange(sh(2026, 10, 26, 0, 0), HomeRange.Week, wedNoon))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 20, 23, 59), HomeRange.Week, wedNoon)) // 本周二（今天之前）
    }

    @Test fun weekOnSundayIsOnlySunday() {
        val sun = sh(2026, 10, 25, 22, 0)
        assertTrue(HomeFilter.inRange(sh(2026, 10, 25, 23, 59), HomeRange.Week, sun))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 26, 0, 1), HomeRange.Week, sun))
    }

    @Test fun weekOnMondayRunsWholeWeek() {
        val mon = sh(2026, 10, 26, 0, 0)
        assertTrue(HomeFilter.inRange(sh(2026, 11, 1, 23, 59), HomeRange.Week, mon))
        assertFalse(HomeFilter.inRange(sh(2026, 11, 2, 0, 1), HomeRange.Week, mon))
    }

    @Test fun usesShanghaiNotDeviceZone() {
        // 设备在 UTC：上海已是 10-22 01:00（UTC 10-21 17:00），「今天」应是上海的 10-22
        val nowUtc = ZonedDateTime.of(2026, 10, 21, 17, 0, 0, 0, ZoneId.of("UTC"))
        assertTrue(HomeFilter.inRange(sh(2026, 10, 22, 23, 59), HomeRange.Today, nowUtc))
        assertFalse(HomeFilter.inRange(sh(2026, 10, 21, 23, 59), HomeRange.Today, nowUtc))
    }

    @Test fun allIncludesEverything() {
        assertNull(HomeFilter.window(HomeRange.All, wedNoon))
        assertTrue(HomeFilter.inRange(sh(2027, 1, 1, 0, 0), HomeRange.All, wedNoon))
        assertTrue(HomeFilter.inRange(null, HomeRange.All, wedNoon))
        assertFalse(HomeFilter.inRange(null, HomeRange.Today, wedNoon))
    }

    // ---- 24 小时标红：还剩 23:59 标、24:01 不标 ----
    @Test fun urgentBoundary() {
        assertTrue(HomeFilter.isUrgent(wedNoon.plusHours(23).plusMinutes(59), false, wedNoon))
        assertTrue(HomeFilter.isUrgent(wedNoon.plusHours(24), false, wedNoon))
        assertFalse(HomeFilter.isUrgent(wedNoon.plusHours(24).plusMinutes(1), false, wedNoon))
        assertTrue(HomeFilter.isUrgent(wedNoon.plusMinutes(1), false, wedNoon))
    }

    @Test fun notUrgentWhenDoneOrPast() {
        assertFalse(HomeFilter.isUrgent(wedNoon.plusHours(1), true, wedNoon))
        assertFalse(HomeFilter.isUrgent(wedNoon, false, wedNoon))
        assertFalse(HomeFilter.isUrgent(wedNoon.minusMinutes(1), false, wedNoon))
        assertFalse(HomeFilter.isUrgent(null, false, wedNoon))
    }

    // ---- 筛选 + DDL 升序 ----
    @Test fun applyFiltersAndSortsAscending() {
        val items = listOf(
            a(1, sh(2026, 10, 27, 9, 0)),   // 下周二
            a(2, sh(2026, 10, 21, 23, 59)), // 今天 23:59
            a(3, sh(2026, 10, 22, 0, 1)),   // 明天 00:01
            a(4, sh(2026, 10, 25, 23, 59)), // 周日 23:59
            a(5, sh(2026, 10, 21, 18, 0)),  // 今天 18:00
        )
        assertEquals(listOf(5, 2, 3, 4, 1), HomeFilter.apply(items, HomeRange.All, wedNoon).map { it.id })
        assertEquals(listOf(5, 2), HomeFilter.apply(items, HomeRange.Today, wedNoon).map { it.id })
        assertEquals(listOf(3), HomeFilter.apply(items, HomeRange.Tomorrow, wedNoon).map { it.id })
        assertEquals(listOf(5, 2, 3, 4), HomeFilter.apply(items, HomeRange.Week, wedNoon).map { it.id })
    }

    @Test fun unparsableDdlSortsLastAndOnlyInAll() {
        val items = listOf(
            AssignmentSummary(id = 9, classId = 1, title = "bad", ddl = "not-a-date"),
            a(1, sh(2026, 10, 21, 20, 0)),
        )
        assertEquals(listOf(1, 9), HomeFilter.apply(items, HomeRange.All, wedNoon).map { it.id })
        assertEquals(listOf(1), HomeFilter.apply(items, HomeRange.Today, wedNoon).map { it.id })
    }

    @Test fun emptyGroupMessages() {
        assertTrue(HomeFilter.emptyMessage(HomeRange.Today, false).contains("今天"))
        assertTrue(HomeFilter.emptyMessage(HomeRange.Week, true).contains("本周"))
    }

    @Test fun rangeKeyRoundTripAndDefault() {
        HomeRange.entries.forEach { assertEquals(it, HomeRange.fromKey(it.key)) }
        assertEquals(HomeRange.All, HomeRange.fromKey(null))
        assertEquals(HomeRange.All, HomeRange.fromKey("garbage"))
        assertEquals(listOf("今天", "明天", "本周", "全部"), HomeRange.entries.map { it.label })
    }
}
