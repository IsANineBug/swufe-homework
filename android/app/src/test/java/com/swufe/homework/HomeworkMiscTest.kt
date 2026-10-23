package com.swufe.homework

import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.PointsPage
import com.swufe.homework.data.api.SyncResponse
import com.swufe.homework.ui.homework.DELETE_CONFIRM_SECONDS
import com.swufe.homework.ui.homework.DELETE_VOTE_WARNING
import com.swufe.homework.util.Ddl
import com.swufe.homework.util.RulesBlock
import com.swufe.homework.util.RulesMarkdown
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeworkMiscTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 单测工作目录是 android/app。 */
    private fun repoFile(path: String) = File("../../$path")

    @Test fun deleteVoteWarningMatchesRequirements() {
        val req = repoFile("docs/requirements.md").readText()
        assertTrue("弹窗文案须与 requirements.md 一致", req.contains("「$DELETE_VOTE_WARNING」"))
        assertEquals(3, DELETE_CONFIRM_SECONDS)
    }

    @Test fun pointsRulesAssetIsDocsCopy() {
        val docs = repoFile("docs/points-rules.md").readText()
        val asset = File("src/main/assets/points-rules.md").readText()
        assertEquals("app/src/main/assets/points-rules.md 须与 docs/points-rules.md 保持一致（复制过去）", docs, asset)
    }

    @Test fun rulesUpperPartOnly() {
        val upper = RulesMarkdown.upperPart(repoFile("docs/points-rules.md").readText())
        assertFalse(upper.contains("实现说明"))
        assertTrue(upper.contains("每人每天最多获得 30 分"))
        val blocks = RulesMarkdown.parse(upper)
        assertEquals(RulesBlock.Title("贡献值规则"), blocks.first())
        val table = blocks.filterIsInstance<RulesBlock.Table>().single()
        assertEquals(listOf("行为", "贡献值", "条件"), table.header)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("班级周榜" to true, "：每周一 00:00 重置。" to false), RulesMarkdown.spans("**班级周榜**：每周一 00:00 重置。"))
    }

    @Test fun ddlFormatting() {
        val t = Ddl.parse("2026-10-23T13:55:00+08:00")!!
        val now = Ddl.parse("2026-10-21T12:00:00+08:00")!!
        assertEquals("10月23日 周五 13:55", Ddl.display(t, now))
        assertEquals("还剩 2 天", Ddl.remaining(t, now))
        assertEquals("还剩 1 小时", Ddl.remaining(t, Ddl.parse("2026-10-23T12:30:00+08:00")!!))
        assertEquals("已截止", Ddl.remaining(t, Ddl.parse("2026-10-23T14:00:00+08:00")!!))
        assertEquals("2026-10-23T13:55:00+08:00", Ddl.toApi(t))
        // UTC 时间也按北京时间显示
        assertEquals("10月23日 周五 13:55", Ddl.display(Ddl.parse("2026-10-23T05:55:00Z")!!, now))
        assertEquals("2026-10-19", Ddl.weekStart(t.toLocalDate()).toString())
    }

    @Test fun parsesContractShapes() {
        val a = json.decodeFromString(
            Assignment.serializer(),
            """{"id":5,"class_id":1,"class_name":"概率论","title":"第三章","ddl":"2026-10-23T13:55:00+08:00",
               "ddl_uncertain":false,"ddl_conflict":true,"done":false,"source_count":2,"reminder_text":null,
               "first_poster_nickname":"小王","merged_from":[3],
               "sources":[{"id":9,"kind":"first","author_nickname":"小王","body":"3.1-3.8","stated_ddl":null,
                 "attachments":[{"id":1,"kind":"image","url":"/files/a.jpg?exp=1&sig=x","thumb_url":null,"bytes":1000}],
                 "created_at":"2026-10-16T10:00:00+08:00","thanks_count":2,"thanked_by_me":true,"voted_delete_by_me":false}],
               "pending_merges":[{"id":7,"from_id":5,"to_id":6,"status":"pending","votes":1,"needed_votes":3}]}""",
        )
        assertTrue(a.ddlConflict)
        assertTrue(a.sources.single().isFirst)
        assertEquals(7, a.pendingMerges.single().id)
        assertEquals(5, a.toSummary().id)

        val s = json.decodeFromString(SyncResponse.serializer(), """{"now":"2026-10-16T12:00:00+08:00","changed":[],"removed_ids":[1,2]}""")
        assertEquals(listOf(1, 2), s.removedIds)

        val p = json.decodeFromString(
            PointsPage.serializer(),
            """{"semester_total":13,"today_earned":3,"items":[{"delta":-10,"reason":"deleted_penalty","status":"settled","class_name":"概率论","assignment_title":null,"created_at":"2026-10-16T12:00:00+08:00"}],"next_cursor":null}""",
        )
        assertEquals(-10, p.items.single().delta)
    }
}
