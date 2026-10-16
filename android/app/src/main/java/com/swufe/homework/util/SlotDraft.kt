package com.swufe.homework.util

import com.swufe.homework.data.api.AiSlot
import com.swufe.homework.data.api.SlotInput

/**
 * 确认页上的一条可编辑时段。字段用可空/字符串，方便用户逐条改；提交前 [validate]。
 * truncated：课名在截图里被截断（高亮提醒核对）；startVisible=false：课块上沿被裁掉，起节可能不准。
 */
data class SlotDraft(
    val key: Long = nextKey(),
    val name: String = "",
    val room: String = "",
    val weekday: Int = 1,
    val start: Int = 1,
    val end: Int = 2,
    val truncated: Boolean = false,
    val startVisible: Boolean = true,
    val teacher: String? = null,
) {
    /** 返回错误文案；null 表示可提交。 */
    fun validate(): String? = when {
        name.isBlank() -> "课名不能为空"
        room.isBlank() -> "教室不能为空"
        weekday !in 1..5 -> "星期只能是周一到周五"
        start !in 1..12 || end !in 1..12 -> "节次应在 1–12 之间"
        start > end -> "起节不能晚于止节"
        else -> null
    }

    val needsReview: Boolean get() = truncated || !startVisible

    fun toInput() = SlotInput(
        name = name.trim(),
        room = normalizeRoom(room),
        weekday = weekday,
        start = start,
        end = end,
        truncated = truncated,
        teacher = teacher?.trim()?.ifEmpty { null },
    )

    companion object {
        private var counter = 0L
        @Synchronized private fun nextKey() = ++counter

        fun fromAi(s: AiSlot): SlotDraft {
            val start = (s.start ?: 1).coerceIn(1, 12)
            val end = (s.end ?: start).coerceIn(start, 12)
            return SlotDraft(
                name = s.name.orEmpty(),
                room = s.room.orEmpty(),
                weekday = (s.weekday ?: 1).coerceIn(1, 5),
                start = start,
                end = end,
                // 课名或教室缺失时同样高亮，让用户补全
                truncated = s.truncated || s.name.isNullOrBlank(),
                startVisible = s.startVisible,
            )
        }

        fun fromInput(s: SlotInput) = SlotDraft(
            name = s.name, room = s.room, weekday = s.weekday, start = s.start, end = s.end,
            truncated = s.truncated, teacher = s.teacher,
        )

        /** 与服务器规则一致的轻量规范化：去 @、全角转半角、去空格。服务器仍会再做一次。 */
        fun normalizeRoom(raw: String): String = toHalfWidth(raw).replace("@", "").replace(" ", "").trim()

        fun toHalfWidth(s: String): String = buildString(s.length) {
            for (c in s) append(
                when (c) {
                    '\u3000' -> ' '
                    in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                    else -> c
                },
            )
        }
    }
}

val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五")
fun weekdayName(d: Int) = WEEKDAY_NAMES.getOrElse(d - 1) { "周$d" }
