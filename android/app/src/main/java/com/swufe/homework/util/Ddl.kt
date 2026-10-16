package com.swufe.homework.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 业务时区统一 Asia/Shanghai；接口时间带 +08:00（docs/workstreams.md 通用约定）。 */
object Ddl {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val API_FMT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun parse(iso: String?): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZONE) }.getOrNull() }

    /** 给接口用：2026-10-23T13:55:00+08:00。 */
    fun toApi(t: ZonedDateTime): String = t.withZoneSameInstant(ZONE).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().format(API_FMT)

    fun at(date: LocalDate, time: LocalTime): ZonedDateTime = ZonedDateTime.of(date, time, ZONE)

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    private val WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 10月23日 周五 13:55；跨年时带年份。 */
    fun display(t: ZonedDateTime, now: ZonedDateTime = now()): String {
        val wd = WEEK[t.dayOfWeek.value - 1]
        val hm = "%02d:%02d".format(t.hour, t.minute)
        val date = if (t.year == now.year) "${t.monthValue}月${t.dayOfMonth}日" else "${t.year}年${t.monthValue}月${t.dayOfMonth}日"
        return "$date $wd $hm"
    }

    fun display(iso: String?): String = parse(iso)?.let { display(it) } ?: (iso ?: "")

    /** 还剩 2 天 / 还剩 5 小时 / 还剩 20 分钟 / 已截止。 */
    fun remaining(t: ZonedDateTime, now: ZonedDateTime = now()): String {
        val d = Duration.between(now.toInstant(), t.toInstant())
        return when {
            d.isNegative || d.isZero -> "已截止"
            d.toDays() >= 1 -> "还剩 ${d.toDays()} 天"
            d.toHours() >= 1 -> "还剩 ${d.toHours()} 小时"
            else -> "还剩 ${d.toMinutes().coerceAtLeast(1)} 分钟"
        }
    }

    /** 本周一（Asia/Shanghai）。 */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    fun isPast(t: ZonedDateTime, now: Instant = Instant.now()): Boolean = !t.toInstant().isAfter(now)
}
