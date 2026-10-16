package com.swufe.homework

import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.PinnedTrust
import com.swufe.homework.data.api.Slot
import com.swufe.homework.data.repo.AuthRepository
import com.swufe.homework.data.repo.DEFAULT_PERIODS
import com.swufe.homework.ui.timetable.buildBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class MiscTest {
    @Test fun recoveryCodeNormalized() {
        assertEquals("ABCD-EFGH-1234-5678", AuthRepository.normalizeRecoveryCode("abcd efgh 1234 5678"))
        assertEquals("ABCD-EFGH-1234-5678", AuthRepository.normalizeRecoveryCode("ABCD-EFGH-1234-5678"))
        assertEquals("ABCDEFGH1234567", AuthRepository.normalizeRecoveryCode("abcdefgh1234567"))
    }

    @Test fun pinsParsed() {
        assertEquals(listOf("AAA=", "BBB="), PinnedTrust.parsePins(" sha256/AAA= , BBB=,"))
        assertEquals(emptyList<String>(), PinnedTrust.parsePins(""))
    }

    @Test fun defaultPeriodsMatchRequirements() {
        assertEquals(12, DEFAULT_PERIODS.size)
        assertEquals("08:30", DEFAULT_PERIODS[0].start)
        assertEquals("18:30", DEFAULT_PERIODS[9].start)
        assertEquals("21:05", DEFAULT_PERIODS[11].end)
    }

    @Test fun blocksOneClassTwoSlots() {
        // 概率论 周一 + 周五 同一个班，课表上两个色块
        val c = ClassInfo(1, "概率论与数理统计B", null, listOf(Slot("经世楼G101", 5, 5, 6), Slot("经世楼G101", 1, 6, 7)), 40)
        val bad = ClassInfo(2, "坏数据", null, listOf(Slot("X", 6, 1, 2), Slot("X", 1, 3, 2)), 1)
        val b = buildBlocks(listOf(c, bad))
        assertEquals(2, b.size)
        assertEquals(1, b[0].weekday)
        assertEquals(5, b[1].weekday)
    }
}

class ReservedNicknameTest {
    @Test fun reserved() {
        org.junit.Assert.assertTrue(AuthRepository.isReservedNickname("管理员"))
        org.junit.Assert.assertTrue(AuthRepository.isReservedNickname(" ＡＤＭＩＮ "))
        org.junit.Assert.assertTrue(AuthRepository.isReservedNickname("官 方"))
        org.junit.Assert.assertFalse(AuthRepository.isReservedNickname("管理员小王"))
    }
}
