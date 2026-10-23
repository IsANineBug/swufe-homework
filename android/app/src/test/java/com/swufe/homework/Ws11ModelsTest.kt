package com.swufe.homework

import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.AssignmentPatch
import com.swufe.homework.data.api.SourcePatch
import com.swufe.homework.ui.homework.MergeVoteState
import com.swufe.homework.ui.homework.canEditAssignment
import com.swufe.homework.ui.homework.mergeVoteState
import com.swufe.homework.data.api.MergeProposal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS11：v2 字段（is_mine / is_first_poster）、PATCH 请求体、合并赞同按钮状态。 */
class Ws11ModelsTest {
    /** 与 di/AppModule.json() 相同的配置。 */
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun enc(p: SourcePatch): JsonObject = json.parseToJsonElement(json.encodeToString(SourcePatch.serializer(), p)).jsonObject

    @Test fun clearingStatedDdlSendsExplicitNull() {
        // issue #8：清空「你看到的 DDL」必须显式下发 null，否则服务器保留旧值
        val o = enc(SourcePatch.full("正文", null, listOf(3)))
        assertTrue("stated_ddl 键必须存在", o.containsKey("stated_ddl"))
        assertEquals(JsonNull, o["stated_ddl"])
        assertEquals(JsonPrimitive("正文"), o["body"])
    }

    @Test fun settingStatedDdlSendsString() {
        val o = enc(SourcePatch.full("", "2026-10-25T23:59:00+08:00", emptyList()))
        assertEquals(JsonPrimitive("2026-10-25T23:59:00+08:00"), o["stated_ddl"])
        assertTrue(o.containsKey("attachment_ids"))
    }

    @Test fun omittedFieldsAreNotSent() {
        // 首发者编辑说明时不动 stated_ddl（由 PATCH /assignments 改 DDL 时服务器同步）
        val o = enc(SourcePatch(body = "新说明", attachmentIds = listOf(1, 2)))
        assertFalse(o.containsKey("stated_ddl"))
        assertEquals(setOf("body", "attachment_ids"), o.keys)
    }

    @Test fun assignmentPatchOnlySendsGivenFields() {
        val s = json.encodeToString(AssignmentPatch.serializer(), AssignmentPatch(title = "新标题"))
        assertEquals(setOf("title"), json.parseToJsonElement(s).jsonObject.keys)
    }

    @Test fun decodesV2Flags() {
        val a = json.decodeFromString(
            Assignment.serializer(),
            """{"id":1,"class_id":2,"title":"t","ddl":"2026-10-25T23:59:00+08:00","first_poster_nickname":"甲",
               "is_first_poster":true,"sources":[{"id":5,"kind":"first","author_nickname":"甲","is_mine":true},
               {"id":6,"kind":"supplement","author_nickname":"乙"}]}""",
        )
        assertTrue(a.isFirstPoster)
        assertTrue(a.sources[0].isMine)
        assertFalse("缺省按不是我的处理", a.sources[1].isMine)
    }

    @Test fun oldServerWithoutFlagsHidesEdit() {
        val a = json.decodeFromString(Assignment.serializer(), """{"id":1,"class_id":2,"title":"t","ddl":"x"}""")
        assertFalse(a.isFirstPoster)
    }

    // ---- 「编辑」入口：首发者看得到，非首发者看不到 ----
    @Test fun editVisibleOnlyForFirstPoster() {
        val base = Assignment(id = 1, classId = 2, title = "t", ddl = "2026-10-25T23:59:00+08:00", firstPosterNickname = "甲")
        assertTrue(canEditAssignment(base.copy(isFirstPoster = true), gone = false))
        assertFalse(canEditAssignment(base.copy(isFirstPoster = false), gone = false))
        // 昵称恰好相同也不算（issue #8：不再按昵称判断）
        assertFalse(canEditAssignment(base.copy(isFirstPoster = false, firstPosterNickname = "我"), gone = false))
        assertFalse(canEditAssignment(base.copy(isFirstPoster = true), gone = true))
        assertFalse(canEditAssignment(null, gone = false))
    }

    // ---- 合并提议：提议者自己的提议「赞同」禁用 ----
    private val p = MergeProposal(id = 7, fromId = 1, toId = 2, votes = 1)

    @Test fun proposerCannotVote() {
        assertEquals(MergeVoteState.Voted, mergeVoteState(p, votedLocally = setOf(7), viewingFrom = true, iAmFirstPoster = false))
        assertEquals(MergeVoteState.Voted, mergeVoteState(p, votedLocally = setOf(7), viewingFrom = false, iAmFirstPoster = false))
    }

    @Test fun othersCanVote() {
        assertEquals(MergeVoteState.CanVote, mergeVoteState(p, votedLocally = emptySet(), viewingFrom = false, iAmFirstPoster = false))
    }

    @Test fun firstPosterOfFromAccepts() {
        assertEquals(MergeVoteState.CanAccept, mergeVoteState(p, votedLocally = emptySet(), viewingFrom = true, iAmFirstPoster = true))
        // 首发者同意不看票数：即使本机记过赞同，也仍可「同意」
        assertEquals(MergeVoteState.CanAccept, mergeVoteState(p, votedLocally = setOf(7), viewingFrom = true, iAmFirstPoster = true))
    }
}
