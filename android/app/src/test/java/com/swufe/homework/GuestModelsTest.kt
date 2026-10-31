package com.swufe.homework

import com.swufe.homework.data.api.GuestAssignment
import com.swufe.homework.data.api.PUBLIC_PATHS
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 游客浏览：GET /guest/assignments 的解析、转成卡片用的 AssignmentSummary、公开路径。 */
class GuestModelsTest {
    /** 与 di/AppModule.json() 相同的配置。 */
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val sample = """
        [{"id":7,"class_name":"概率论","title":"第三章课后习题","ddl":"2026-11-03T23:59:00+08:00",
          "ddl_uncertain":true,"ddl_conflict":false,"source_count":4,"first_poster":"同学A",
          "sources":[
            {"kind":"first","author":"同学A","body":"第 3 章习题 1–10","stated_ddl":null,"created_at":"2026-10-30T20:00:00+08:00","attachment_count":2},
            {"kind":"supplement","author":"同学B","body":"老师说第 7 题不用做","stated_ddl":"2026-11-04T23:59:00+08:00","created_at":"2026-10-31T09:00:00+08:00","attachment_count":0}
          ]}]
    """.trimIndent()

    @Test fun decodesSample() {
        val list = json.decodeFromString(ListSerializer(GuestAssignment.serializer()), sample)
        assertEquals(1, list.size)
        val g = list[0]
        assertEquals("同学A", g.firstPoster)
        assertEquals(2, g.sources.size)
        assertTrue(g.sources[0].isFirst)
        assertFalse(g.sources[1].isFirst)
        assertEquals(2, g.sources[0].attachmentCount)
        assertEquals("2026-11-04T23:59:00+08:00", g.sources[1].statedDdl)
    }

    @Test fun toSummaryIsReadOnlyCard() {
        val g = json.decodeFromString(ListSerializer(GuestAssignment.serializer()), sample)[0]
        val s = g.toSummary()
        assertEquals(7, s.id)
        assertEquals("概率论", s.className)
        assertEquals("第三章课后习题", s.title)
        assertEquals(4, s.sourceCount)
        assertTrue(s.ddlUncertain)
        assertFalse(s.done)
    }

    @Test fun guestPathIsPublic() = assertTrue("guest/assignments" in PUBLIC_PATHS)
}
