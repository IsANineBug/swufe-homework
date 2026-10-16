package com.swufe.homework

import com.swufe.homework.data.api.AiSlot
import com.swufe.homework.util.SlotDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotDraftTest {
    @Test fun normalizeRoom() {
        assertEquals("经世楼G101", SlotDraft.normalizeRoom("@经世楼 Ｇ１０１"))
        assertEquals("通博楼B204", SlotDraft.normalizeRoom("通博楼　B204"))
    }

    @Test fun fromAiClampsAndFlags() {
        val d = SlotDraft.fromAi(AiSlot(name = "形势与政策III", room = null, weekday = 3, start = 4, end = 4, truncated = true))
        assertTrue(d.truncated)
        assertEquals(4, d.start); assertEquals(4, d.end)
        assertEquals("教室不能为空", d.validate())
    }

    @Test fun fromAiMissingNameIsHighlighted() {
        val d = SlotDraft.fromAi(AiSlot(name = null, room = "经世楼G101", weekday = 9, start = 13, end = 2))
        assertTrue(d.truncated)
        assertEquals(5, d.weekday)
        assertEquals(12, d.start); assertEquals(12, d.end)
    }

    @Test fun startCutOffNeedsReview() {
        val d = SlotDraft.fromAi(AiSlot("概率论与数理统计B", "经世楼G101", 1, 6, 7, false, startVisible = false))
        assertTrue(d.needsReview)
        assertNull(d.validate())
    }

    @Test fun validation() {
        assertNotNull(SlotDraft(name = "高数", room = "A101", weekday = 1, start = 5, end = 3).validate())
        assertNotNull(SlotDraft(name = " ", room = "A101").validate())
        assertNull(SlotDraft(name = "高数", room = "A101", weekday = 5, start = 11, end = 12).validate())
    }

    @Test fun toInputTrims() {
        val i = SlotDraft(name = " 概率论 ", room = "@经世楼G101", weekday = 1, start = 6, end = 7, teacher = " ").toInput()
        assertEquals("概率论", i.name)
        assertEquals("经世楼G101", i.room)
        assertNull(i.teacher)
    }
}
