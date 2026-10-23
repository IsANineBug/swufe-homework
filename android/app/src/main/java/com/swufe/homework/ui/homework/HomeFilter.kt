package com.swufe.homework.ui.homework

import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.util.Ddl
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 首页顶部切换（requirements 十三.3、v2-workstreams WS11 第 1 条）。
 * [key] 用于 DataStore 记住上次选择，改了会丢失用户的选择，不要随意改。
 */
enum class HomeRange(val label: String, val key: String) {
    Today("今天", "today"),
    Tomorrow("明天", "tomorrow"),
    Week("本周", "week"),
    All("全部", "all");

    companion object {
        /** 默认「全部」。 */
        val Default = All
        fun fromKey(key: String?): HomeRange = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * 首页日期分组与标红的纯函数（不依赖 Android，单测见 HomeFilterTest）。
 * 一律按 Asia/Shanghai 的日历日计算，与设备时区无关。
 */
object HomeFilter {
    /** 「24 小时内截止」的阈值。 */
    val URGENT_WINDOW: Duration = Duration.ofHours(24)

    /**
     * [range] 对应的时间区间 [start, end)（左闭右开），All 返回 null（不限）。
     * - 今天：今天 00:00 到明天 00:00；
     * - 明天：明天 00:00 到后天 00:00；
     * - 本周：今天 00:00 到下周一 00:00，即「今天到本周日 23:59」（周日当天打开时只含周日）。
     */
    fun window(range: HomeRange, now: ZonedDateTime): Pair<Instant, Instant>? {
        val today = now.withZoneSameInstant(Ddl.ZONE).toLocalDate()
        fun startOf(d: java.time.LocalDate) = d.atStartOfDay(Ddl.ZONE).toInstant()
        return when (range) {
            HomeRange.Today -> startOf(today) to startOf(today.plusDays(1))
            HomeRange.Tomorrow -> startOf(today.plusDays(1)) to startOf(today.plusDays(2))
            HomeRange.Week -> {
                val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                startOf(today) to startOf(nextMonday)
            }
            HomeRange.All -> null
        }
    }

    /** DDL 是否落在 [range] 里。解析不了的 DDL 只在「全部」里出现。 */
    fun inRange(ddl: ZonedDateTime?, range: HomeRange, now: ZonedDateTime): Boolean {
        val w = window(range, now) ?: return true
        val t = ddl?.toInstant() ?: return false
        return !t.isBefore(w.first) && t.isBefore(w.second)
    }

    /**
     * 24 小时内截止：未完成，且距 DDL 还剩 (0, 24 小时]。已截止或已完成不标。
     * 例：还剩 23:59 标红，还剩 24:01 不标。
     */
    fun isUrgent(ddl: ZonedDateTime?, done: Boolean, now: ZonedDateTime): Boolean {
        if (done || ddl == null) return false
        val left = Duration.between(now.toInstant(), ddl.toInstant())
        return !left.isNegative && !left.isZero && left <= URGENT_WINDOW
    }

    fun isUrgent(a: AssignmentSummary, now: ZonedDateTime): Boolean = isUrgent(Ddl.parse(a.ddl), a.done, now)

    /** 按 [range] 筛选并按 DDL 升序（解析不了的排最后，同一时间按 id 稳定排序）。 */
    fun apply(items: List<AssignmentSummary>, range: HomeRange, now: ZonedDateTime): List<AssignmentSummary> =
        items.map { it to Ddl.parse(it.ddl) }
            .filter { (_, t) -> inRange(t, range, now) }
            .sortedWith(compareBy<Pair<AssignmentSummary, ZonedDateTime?>, Instant?>(nullsLast()) { it.second?.toInstant() }.thenBy { it.first.id })
            .map { it.first }

    /** 空分组的提示语。 */
    fun emptyMessage(range: HomeRange, showDone: Boolean): String {
        val what = if (showDone) "作业" else "待完成的作业"
        return when (range) {
            HomeRange.Today -> "今天没有截止的$what"
            HomeRange.Tomorrow -> "明天没有截止的$what"
            HomeRange.Week -> "本周没有截止的$what"
            HomeRange.All -> if (showDone) "还没有作业。\n去课表里点开一门课，可以发布作业。" else "没有待完成的作业。\n去课表里点开一门课，可以发布作业。"
        }
    }
}
