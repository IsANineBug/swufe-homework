package com.swufe.homework.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---- v2（WS11）：管理员接口模型，与 docs/api.yaml 的 /admin/* 与 AdminUser / AdminClass / AdminReport / Sanction 一一对应 ----
//
// 隐私底线（requirements.md 十三.8）：这里的模型只声明 api.yaml 列出的公开字段。
// 不含、也不能加：密码、恢复码、token、某人加入了哪些班、个人课表、完成情况、提醒、IP。
// Json 配置 ignoreUnknownKeys = true，服务器即使多给了字段也会在解码时丢掉，不会进入界面（Ws11AdminTest 有快照测试）。

/** api.yaml Sanction。kind：mute（禁言）/ post_ban（全站禁发）/ disable（封号）；ends_at = null 表示永久（仅 disable）。 */
@Serializable
data class Sanction(
    val id: Long,
    val kind: String,
    val reason: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("lifted_at") val liftedAt: String? = null,
)

/** api.yaml AdminUser：管理员能看到的用户信息，只有这些字段。 */
@Serializable
data class AdminUser(
    val id: Int,
    val nickname: String,
    val role: String = "user",
    @SerialName("registered_via") val registeredVia: String = "invite",
    @SerialName("inviter_nickname") val inviterNickname: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val disabled: Boolean = false,
    @SerialName("contribution_total") val contributionTotal: Int = 0,
    @SerialName("deleted_content_30d") val deletedContent30d: Int = 0,
    @SerialName("reports_against_open") val reportsAgainstOpen: Int = 0,
    val sanctions: List<Sanction> = emptyList(),
) {
    val isAdmin: Boolean get() = role == "admin"
}

/** api.yaml AdminClass。 */
@Serializable
data class AdminClass(
    val id: Int,
    val name: String,
    val teacher: String? = null,
    @SerialName("semester_name") val semesterName: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("assignment_count") val assignmentCount: Int = 0,
    @SerialName("slot_count") val slotCount: Int = 0,
    @SerialName("created_by_nickname") val createdByNickname: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val hidden: Boolean = false,
)

/** AdminReport.reporters 的一项：举报人昵称、理由、时间（举报人身份只有管理员看得到）。 */
@Serializable
data class ReportReporter(
    val nickname: String = "",
    val reason: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * api.yaml AdminReport。[target] 的形状随 target_kind 不同（见 /admin/reports 说明），
 * 按需用 [sourceTarget] / [messageTarget] / [classTarget] / [userTarget] 解出；对象已被硬删时为 null。
 */
@Serializable
data class AdminReport(
    val id: Long,
    @SerialName("target_kind") val targetKind: String,
    @SerialName("target_id") val targetId: Long,
    val target: JsonElement? = null,
    val reporters: List<ReportReporter> = emptyList(),
    @SerialName("handled_at") val handledAt: String? = null,
    val resolution: String? = null,
)

/** 被举报的作业来源：正文、附件缩略图、作者昵称、班级名。 */
@Serializable
data class ReportSourceTarget(
    val body: String = "",
    @SerialName("thumb_urls") val thumbUrls: List<String> = emptyList(),
    @SerialName("author_nickname") val authorNickname: String = "",
    @SerialName("class_name") val className: String = "",
)

/** 被举报消息前后的上下文消息（各 5 条，按 id 升序，不含被举报的这条）。 */
@Serializable
data class ReportContextMessage(
    val id: Long,
    @SerialName("author_nickname") val authorNickname: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

/** 被举报的频道消息：正文、作者昵称、班级名、时间与上下文。管理员只能通过举报看到这些，不能浏览频道。 */
@Serializable
data class ReportMessageTarget(
    val body: String = "",
    @SerialName("author_nickname") val authorNickname: String = "",
    @SerialName("class_name") val className: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val context: List<ReportContextMessage> = emptyList(),
)

/** 被举报的班级：班名、成员数、作业数、创建者昵称。 */
@Serializable
data class ReportClassTarget(
    val name: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("assignment_count") val assignmentCount: Int = 0,
    @SerialName("created_by_nickname") val createdByNickname: String = "",
)

/** GET /admin/class-merges 的一项（班级自动/手动合并记录，含已撤销）。 */
@Serializable
data class ClassMergeRecord(
    val id: Long,
    @SerialName("from_class_id") val fromClassId: Int = 0,
    @SerialName("from_name") val fromName: String = "",
    @SerialName("into_class_id") val intoClassId: Int = 0,
    @SerialName("into_name") val intoName: String = "",
    @SerialName("triggered_by_nickname") val triggeredByNickname: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("undone_at") val undoneAt: String? = null,
)

/** GET /admin/deleted 的一项：普通 Source 的公开字段 + assignment_id、deleted_at、deleted_reason。 */
@Serializable
data class AdminDeletedSource(
    val id: Int,
    val kind: String = "supplement",
    @SerialName("author_nickname") val authorNickname: String = "",
    val body: String = "",
    @SerialName("stated_ddl") val statedDdl: String? = null,
    val attachments: List<Attachment> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("assignment_id") val assignmentId: Int? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("deleted_reason") val deletedReason: String? = null,
)

/** GET /admin/actions 的一项（操作日志）。payload 存改前/改后，服务器保证不含秘密。 */
@Serializable
data class AdminAction(
    val id: Long,
    val action: String = "",
    @SerialName("target_kind") val targetKind: String = "",
    @SerialName("target_id") val targetId: Long = 0,
    val reason: String? = null,
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ---- 请求体 ----

@Serializable
data class ResolveReportRequest(val resolution: String)

/** 只有 reason 的请求体（隐藏班级、删除消息）。 */
@Serializable
data class ReasonRequest(val reason: String)

/** PATCH /admin/classes/{id}：改班名/教师。teacher 不给就不改。 */
@Serializable
data class AdminClassPatch(
    val name: String? = null,
    val teacher: String? = null,
    val reason: String? = null,
)

@Serializable
data class ClassMergeRequest(
    @SerialName("from_class_id") val fromClassId: Int,
    @SerialName("into_class_id") val intoClassId: Int,
    val reason: String? = null,
)

@Serializable
data class ClassMergeResult(@SerialName("merge_id") val mergeId: Long? = null)

@Serializable
data class AdminSourceDelete(val reason: String, val penalize: Boolean = true)

@Serializable
data class RestoreRequest(@SerialName("lift_ban") val liftBan: Boolean = true)

@Serializable
data class NicknameReset(val nickname: String, val reason: String)

/** POST /admin/users/{id}/sanctions。days：mute/post_ban 必填 1–365；disable 为 null 表示永久。 */
@Serializable
data class SanctionCreate(val kind: String, val days: Int? = null, val reason: String)

@Serializable
data class PointsAdjust(
    @SerialName("class_id") val classId: Int,
    val delta: Int,
    val reason: String,
)

@Serializable
data class AnnouncementCreate(
    val title: String,
    val body: String,
    @SerialName("effective_at") val effectiveAt: String,
)

@Serializable
data class CreatedId(val id: Long? = null)
