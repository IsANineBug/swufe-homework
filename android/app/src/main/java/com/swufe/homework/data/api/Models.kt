package com.swufe.homework.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// 与 docs/api.yaml components.schemas 一一对应。字段名保持 snake_case。

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    @SerialName("recovery_code") val recoveryCode: String? = null,
)

@Serializable
data class CampusCheck(val campus: Boolean)

@Serializable
data class RegisterRequest(
    val nickname: String,
    val password: String,
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable
data class LoginRequest(val nickname: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class LogoutRequest(
    @SerialName("refresh_token") val refreshToken: String,
    val all: Boolean = false,
)

@Serializable
data class RecoverRequest(
    val nickname: String,
    @SerialName("recovery_code") val recoveryCode: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class RecoverResponse(@SerialName("new_recovery_code") val newRecoveryCode: String)

@Serializable
data class Semester(
    val id: Int,
    val name: String,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("ends_on") val endsOn: String? = null,
    val archived: Boolean = false,
)

@Serializable
data class Period(val no: Int, val start: String, val end: String)

@Serializable
data class Meta(
    val semester: Semester? = null,
    val periods: List<Period> = emptyList(),
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class Me(
    val id: Int,
    val nickname: String,
    val role: String = "user",
    @SerialName("registered_via") val registeredVia: String = "invite",
    @SerialName("leaderboard_anonymous") val leaderboardAnonymous: Boolean = false,
    @SerialName("invites_remaining") val invitesRemaining: Int = 0,
) {
    val isAdmin: Boolean get() = role == "admin"
}

@Serializable
data class MePatch(
    val nickname: String? = null,
    @SerialName("leaderboard_anonymous") val leaderboardAnonymous: Boolean? = null,
)

@Serializable
data class InviteCode(
    val code: String,
    @SerialName("used_by_nickname") val usedByNickname: String? = null,
    @SerialName("used_at") val usedAt: String? = null,
)

@Serializable
data class Invites(val remaining: Int = 0, val codes: List<InviteCode> = emptyList())

@Serializable
data class NewInvite(val code: String)

@Serializable
data class Notice(
    val id: Int,
    val kind: String,
    val payload: JsonObject? = null,
    @SerialName("created_at") val createdAt: String,
    val read: Boolean = false,
)

@Serializable
data class SlotInput(
    val name: String,
    val room: String,
    val weekday: Int,
    val start: Int,
    val end: Int,
    val truncated: Boolean = false,
    val teacher: String? = null,
)

@Serializable
data class Slot(val room: String, val weekday: Int, val start: Int, val end: Int)

@Serializable
data class ClassInfo(
    val id: Int,
    val name: String,
    val teacher: String? = null,
    val slots: List<Slot> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 0,
)

@Serializable
data class TimetableImportRequest(val slots: List<SlotInput>)

@Serializable
data class MergedPair(val from: Int, val into: Int)

@Serializable
data class ImportConflict(
    val slot: SlotInput,
    @SerialName("existing_class") val existingClass: ClassInfo,
)

@Serializable
data class TimetableImportResult(
    val joined: List<ClassInfo> = emptyList(),
    val created: List<ClassInfo> = emptyList(),
    val merged: List<MergedPair> = emptyList(),
    val conflicts: List<ImportConflict> = emptyList(),
)

@Serializable
data class Attachment(
    val id: Int,
    val kind: String,
    val url: String,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    val bytes: Long = 0,
)

@Serializable
data class AiStatus(val online: Boolean, @SerialName("last_seen") val lastSeen: String? = null)

@Serializable
data class AiJobRequest(
    val kind: String,
    @SerialName("class_id") val classId: Int? = null,
    val input: JsonObject,
)

@Serializable
data class AiJob(
    val id: Int,
    val kind: String,
    val status: String,
    val output: JsonObject? = null,
    val error: String? = null,
)

/** ai-contract.md §2 parse_timetable 输出里的一条。 */
@Serializable
data class AiSlot(
    val name: String? = null,
    val room: String? = null,
    val weekday: Int? = null,
    val start: Int? = null,
    val end: Int? = null,
    val truncated: Boolean = false,
    @SerialName("start_visible") val startVisible: Boolean = true,
)

@Serializable
data class AiTimetableOutput(val slots: List<AiSlot> = emptyList())

/** GitHub Releases latest（只取用到的字段）。 */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)
