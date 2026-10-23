package com.swufe.homework.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// WS5：作业、来源、合并、删除投票、积分、榜单。与 docs/api.yaml components.schemas 一一对应，字段名保持 snake_case。

@Serializable
data class AssignmentSummary(
    val id: Int,
    @SerialName("class_id") val classId: Int,
    @SerialName("class_name") val className: String = "",
    val title: String,
    val ddl: String,
    @SerialName("ddl_uncertain") val ddlUncertain: Boolean = false,
    @SerialName("ddl_conflict") val ddlConflict: Boolean = false,
    val done: Boolean = false,
    @SerialName("source_count") val sourceCount: Int = 1,
    /** 预生成提醒文案，键为 2d / 1d / 12h，值为 {title, body}（ai-contract.md §6）。 */
    @SerialName("reminder_text") val reminderText: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class Source(
    val id: Int,
    val kind: String = "supplement",
    @SerialName("author_nickname") val authorNickname: String = "",
    /** v2：是否我发的（服务器按用户 id 判断，不按昵称）。 */
    @SerialName("is_mine") val isMine: Boolean = false,
    val body: String = "",
    @SerialName("stated_ddl") val statedDdl: String? = null,
    val attachments: List<Attachment> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("thanks_count") val thanksCount: Int = 0,
    @SerialName("thanked_by_me") val thankedByMe: Boolean = false,
    @SerialName("voted_delete_by_me") val votedDeleteByMe: Boolean = false,
) {
    val isFirst: Boolean get() = kind == "first"
}

@Serializable
data class MergeProposal(
    val id: Int,
    @SerialName("from_id") val fromId: Int,
    @SerialName("to_id") val toId: Int,
    val status: String = "pending",
    val votes: Int = 0,
    @SerialName("needed_votes") val neededVotes: Int = 3,
)

@Serializable
data class Assignment(
    val id: Int,
    @SerialName("class_id") val classId: Int,
    @SerialName("class_name") val className: String = "",
    val title: String,
    val ddl: String,
    @SerialName("ddl_uncertain") val ddlUncertain: Boolean = false,
    @SerialName("ddl_conflict") val ddlConflict: Boolean = false,
    val done: Boolean = false,
    @SerialName("source_count") val sourceCount: Int = 1,
    @SerialName("reminder_text") val reminderText: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("first_poster_nickname") val firstPosterNickname: String = "",
    /** v2：我是否首发者（服务器按用户 id 判断），决定详情页是否显示「编辑」。 */
    @SerialName("is_first_poster") val isFirstPoster: Boolean = false,
    val sources: List<Source> = emptyList(),
    @SerialName("merged_from") val mergedFrom: List<Int> = emptyList(),
    @SerialName("pending_merges") val pendingMerges: List<MergeProposal> = emptyList(),
) {
    fun toSummary() = AssignmentSummary(
        id, classId, className, title, ddl, ddlUncertain, ddlConflict, done, sourceCount, reminderText, updatedAt,
    )
}

@Serializable
data class AssignmentCreate(
    val title: String,
    val ddl: String,
    @SerialName("ddl_uncertain") val ddlUncertain: Boolean = false,
    val body: String = "",
    @SerialName("attachment_ids") val attachmentIds: List<Int> = emptyList(),
    @SerialName("ai_job_id") val aiJobId: Int? = null,
)

@Serializable
data class AssignmentPatch(
    val title: String? = null,
    val ddl: String? = null,
    @SerialName("ddl_uncertain") val ddlUncertain: Boolean? = null,
)

@Serializable
data class SourceCreate(
    val body: String = "",
    @SerialName("stated_ddl") val statedDdl: String? = null,
    @SerialName("attachment_ids") val attachmentIds: List<Int> = emptyList(),
)

/**
 * PATCH /sources/{id} 的请求体（api.yaml SourceCreate，服务器「只改给出的字段」）。
 * 全局 Json 配置了 explicitNulls = false，Kotlin 的 null 会被省略，没法表达「清空」；
 * 所以 stated_ddl 用 JsonElement：null = 不改（不下发），[JsonNull] = 显式下发 null 清空，字符串 = 改成该时间。
 * 修 issue #8：编辑来源时清空「你看到的 DDL」要显式下发 null。
 */
@Serializable
data class SourcePatch(
    val body: String? = null,
    @SerialName("stated_ddl") val statedDdl: JsonElement? = null,
    @SerialName("attachment_ids") val attachmentIds: List<Int>? = null,
) {
    companion object {
        /** 编辑整条来源：正文、附件整体替换；ddl 为 null 时显式清空「你看到的 DDL」。 */
        fun full(body: String, ddl: String?, attachmentIds: List<Int>) =
            SourcePatch(body, ddl?.let { JsonPrimitive(it) } ?: JsonNull, attachmentIds)
    }
}

@Serializable
data class CandidatesRequest(val title: String, val ddl: String? = null)

@Serializable
data class SyncResponse(
    val now: String,
    val changed: List<AssignmentSummary> = emptyList(),
    @SerialName("removed_ids") val removedIds: List<Int> = emptyList(),
)

@Serializable
data class DeleteVoteResult(val votes: Int = 0, val threshold: Int = 3, val deleted: Boolean = false)

@Serializable
data class MergeProposalCreate(@SerialName("from_id") val fromId: Int, @SerialName("to_id") val toId: Int)

@Serializable
data class LeaderboardRep(@SerialName("display_name") val displayName: String)

@Serializable
data class LeaderboardRow(
    val rank: Int,
    @SerialName("display_name") val displayName: String,
    val points: Int,
    @SerialName("is_me") val isMe: Boolean = false,
)

@Serializable
data class Leaderboard(val rep: LeaderboardRep? = null, val rows: List<LeaderboardRow> = emptyList())

@Serializable
data class PointsEntry(
    val delta: Int,
    val reason: String,
    val status: String,
    @SerialName("class_name") val className: String = "",
    @SerialName("assignment_title") val assignmentTitle: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PointsPage(
    @SerialName("semester_total") val semesterTotal: Int = 0,
    @SerialName("today_earned") val todayEarned: Int = 0,
    val items: List<PointsEntry> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** ai-contract.md §1 parse_assignment 输出。 */
@Serializable
data class AiAssignmentDraft(
    val title: String? = null,
    val requirements: String? = null,
    val ddl: String? = null,
    @SerialName("ddl_source") val ddlSource: String? = null,
    @SerialName("submit_method") val submitMethod: String? = null,
    val uncertain: List<String> = emptyList(),
)

/** ai-contract.md §3 dedupe_review 输出。 */
@Serializable
data class AiDedupeMatch(val id: Int, val same: Boolean = false, val confidence: Double = 0.0, val reason: String = "")

@Serializable
data class AiDedupeOutput(val matches: List<AiDedupeMatch> = emptyList())

/** ai-contract.md §5 spam_check 输出。 */
@Serializable
data class AiSpamOutput(val suspicious: Boolean = false, val category: String? = null, val reason: String = "")
