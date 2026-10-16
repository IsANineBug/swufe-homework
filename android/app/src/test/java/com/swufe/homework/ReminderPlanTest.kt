package com.swufe.homework

import com.swufe.homework.util.Ddl
import com.swufe.homework.util.PlannedReminder
import com.swufe.homework.util.ReminderPlan
import com.swufe.homework.util.ReminderSlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class ReminderPlanTest {
    private fun t(s: String): ZonedDateTime = Ddl.parse(s)!!
    private fun i(s: String): Instant = t(s).toInstant()

    @Test fun threeRemindersAt9And12h() {
        // DDL 周五 13:55 → 周三 09:00、周四 09:00、周五 01:55
        val p = ReminderPlan.plan(t("2026-10-23T13:55:00+08:00"), i("2026-10-16T12:00:00+08:00"))
        assertEquals(listOf(ReminderSlot.D2, ReminderSlot.D1, ReminderSlot.H12), p.map { it.slot })
        assertEquals(i("2026-10-21T09:00:00+08:00"), p[0].at)
        assertEquals(i("2026-10-22T09:00:00+08:00"), p[1].at)
        assertEquals(i("2026-10-23T01:55:00+08:00"), p[2].at)
    }

    @Test fun pastOnesSkipped() {
        // 周四 10:00 才发布：前 2 天、前 1 天 09:00 都过了，只剩前 12 小时
        val p = ReminderPlan.plan(t("2026-10-23T13:55:00+08:00"), i("2026-10-22T10:00:00+08:00"))
        assertEquals(listOf(ReminderSlot.H12), p.map { it.slot })
    }

    @Test fun allPastGivesNothing() {
        assertTrue(ReminderPlan.plan(t("2026-10-23T13:55:00+08:00"), i("2026-10-23T08:00:00+08:00")).isEmpty())
        assertTrue(ReminderPlan.plan(t("2026-10-23T13:55:00+08:00"), i("2026-10-24T08:00:00+08:00")).isEmpty())
    }

    @Test fun lateNightAndEarlyMorningDdl() {
        // DDL 周五 21:30 → 周三 09:00、周四 09:00、周五 09:30
        val a = ReminderPlan.plan(t("2026-10-23T21:30:00+08:00"), i("2026-10-16T00:00:00+08:00"))
        assertEquals(listOf("2026-10-21T09:00", "2026-10-22T09:00", "2026-10-23T09:30"), a.map { it.at.atZone(Ddl.ZONE).toLocalDateTime().toString() })
        // DDL 周五 00:30 → 周三 09:00、周四 09:00、周四 12:30
        val b = ReminderPlan.plan(t("2026-10-23T00:30:00+08:00"), i("2026-10-16T00:00:00+08:00"))
        assertEquals(listOf("2026-10-21T09:00", "2026-10-22T09:00", "2026-10-22T12:30"), b.map { it.at.atZone(Ddl.ZONE).toLocalDateTime().toString() })
    }

    @Test fun closerThanOneHourMergedKeepingLater() {
        val x = listOf(
            PlannedReminder(ReminderSlot.D1, i("2026-10-22T09:00:00+08:00")),
            PlannedReminder(ReminderSlot.H12, i("2026-10-22T09:40:00+08:00")),
            PlannedReminder(ReminderSlot.D2, i("2026-10-21T09:00:00+08:00")),
        )
        val m = ReminderPlan.mergeClose(x)
        assertEquals(listOf(ReminderSlot.D2, ReminderSlot.H12), m.map { it.slot })
        // 恰好相隔 1 小时不合并
        val y = listOf(
            PlannedReminder(ReminderSlot.D1, i("2026-10-22T09:00:00+08:00")),
            PlannedReminder(ReminderSlot.H12, i("2026-10-22T10:00:00+08:00")),
        )
        assertEquals(2, ReminderPlan.mergeClose(y).size)
    }

    @Test fun aiTextAndTemplate() {
        val rt = Json.decodeFromString(JsonObject.serializer(), """{"2d":{"title":"概率论作业后天截止","body":"第三章习题，周五 13:55 前交到学习通"},"12h":"快截止了"}""")
        assertEquals("概率论作业后天截止" to "第三章习题，周五 13:55 前交到学习通", ReminderPlan.aiText(rt, ReminderSlot.D2))
        assertEquals("快截止了" to "", ReminderPlan.aiText(rt, ReminderSlot.H12))
        assertNull(ReminderPlan.aiText(rt, ReminderSlot.D1))
        assertNull(ReminderPlan.aiText(null, ReminderSlot.D1))

        val ddl = t("2026-10-23T13:55:00+08:00")
        val (title, body) = ReminderPlan.defaultText(ReminderSlot.D1, "第三章习题", "概率论与数理统计B", ddl, t("2026-10-22T09:00:00+08:00"))
        assertEquals("概率论与数理统计B作业明天截止", title)
        assertEquals("第三章习题，10月23日 周五 13:55 截止", body)
        assertEquals("概率论与数理统计B作业后天截止", ReminderPlan.defaultText(ReminderSlot.D2, "x", "概率论与数理统计B", ddl, t("2026-10-21T09:00:00+08:00")).first)
        assertEquals("概率论与数理统计B作业12 小时后截止", ReminderPlan.defaultText(ReminderSlot.H12, "x", "概率论与数理统计B", ddl, t("2026-10-23T01:55:00+08:00")).first)
    }
}
