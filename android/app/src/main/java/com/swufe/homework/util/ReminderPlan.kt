package com.swufe.homework.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** 三次提醒（requirements §十、workstreams WS5 第 6 条）。key 与 reminder_text 的键一致。 */
enum class ReminderSlot(val key: String, val index: Int) {
    D2("2d", 0), D1("1d", 1), H12("12h", 2);

    companion object {
        fun of(key: String?): ReminderSlot? = entries.firstOrNull { it.key == key }
    }
}

data class PlannedReminder(val slot: ReminderSlot, val at: Instant)

object ReminderPlan {
    private val NINE: LocalTime = LocalTime.of(9, 0)
    val MERGE_WITHIN: Duration = Duration.ofHours(1)

    /**
     * DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时（日期按 Asia/Shanghai 日历日）。
     * 已经过了的跳过；相隔不足 1 小时的合并为一次（保留更靠近 DDL 的那次及其文案）。
     */
    fun plan(ddl: ZonedDateTime, now: Instant): List<PlannedReminder> {
        val d = ddl.withZoneSameInstant(Ddl.ZONE)
        val raw = listOf(
            PlannedReminder(ReminderSlot.D2, Ddl.at(d.toLocalDate().minusDays(2), NINE).toInstant()),
            PlannedReminder(ReminderSlot.D1, Ddl.at(d.toLocalDate().minusDays(1), NINE).toInstant()),
            PlannedReminder(ReminderSlot.H12, d.minusHours(12).toInstant()),
        ).filter { it.at.isAfter(now) && it.at.isBefore(d.toInstant()) }
        return mergeClose(raw)
    }

    /**
     * 相隔不足 1 小时的合并为一次，保留较晚（更靠近 DDL）的那次。
     * 按西财作息，三次提醒彼此至少相隔约 3 小时，正常不会触发；保留以防规则调整（如改成前 2 小时）。
     */
    fun mergeClose(list: List<PlannedReminder>): List<PlannedReminder> {
        val out = ArrayList<PlannedReminder>(list.size)
        for (r in list.sortedBy { it.at }) {
            val last = out.lastOrNull()
            if (last != null && Duration.between(last.at, r.at) < MERGE_WITHIN) out[out.size - 1] = r else out += r
        }
        return out
    }

    /** reminder_text[slot] = {title, body}；也兼容直接给一段字符串。拿不到返回 null，用内置模板。 */
    fun aiText(reminderText: JsonObject?, slot: ReminderSlot): Pair<String, String>? {
        val v = reminderText?.get(slot.key) ?: return null
        return when (v) {
            is JsonObject -> {
                val t = (v["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val b = (v["body"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (t.isEmpty() && b.isEmpty()) null else t.ifEmpty { b } to b
            }
            is JsonPrimitive -> v.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { it to "" }
            else -> null
        }
    }

    /** 内置模板：概率论作业后天截止 / 第三章习题，10月23日 周五 13:55 截止。 */
    fun defaultText(slot: ReminderSlot, title: String, className: String, ddl: ZonedDateTime, now: ZonedDateTime): Pair<String, String> {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), ddl.withZoneSameInstant(Ddl.ZONE).toLocalDate())
        val whenText = when {
            slot == ReminderSlot.H12 -> "12 小时后截止"
            days <= 0L -> "今天截止"
            days == 1L -> "明天截止"
            days == 2L -> "后天截止"
            else -> "${days} 天后截止"
        }
        val course = className.ifBlank { "作业" }
        return "${course}作业$whenText" to "$title，${Ddl.display(ddl, now)} 截止"
    }
}
