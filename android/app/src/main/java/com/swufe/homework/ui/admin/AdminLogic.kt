package com.swufe.homework.ui.admin

import com.swufe.homework.data.api.AdminReport
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.Me
import com.swufe.homework.data.api.ReportClassTarget
import com.swufe.homework.data.api.ReportMessageTarget
import com.swufe.homework.data.api.ReportSourceTarget
import com.swufe.homework.data.api.Sanction
import com.swufe.homework.data.api.SanctionCreate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// 管理页面的纯逻辑（无 Android 依赖，Ws11AdminTest 覆盖）。

// ---------- 入口与路由拦截 ----------

/** 「我的」页是否显示「管理」入口：只有 /me.role=admin。还没拿到 /me 时不显示。 */
fun showAdminEntry(me: Me?): Boolean = me?.isAdmin == true

enum class AdminAccess { Checking, Allowed, Denied }

/**
 * 管理路由的拦截：进入时总会重新拉一次 /me（[fresh]），以服务器最新的 role 为准；
 * 拉取前先用缓存（[cached]）决定能否先显示，但缓存说不是管理员时直接拒绝；
 * 拉取失败（[freshFailed]）且缓存也没有时拒绝，不放行。
 */
fun adminAccess(cached: Me?, fresh: Me?, freshFailed: Boolean): AdminAccess = when {
    fresh != null -> if (fresh.isAdmin) AdminAccess.Allowed else AdminAccess.Denied
    cached != null && !cached.isAdmin -> AdminAccess.Denied
    freshFailed -> if (cached?.isAdmin == true) AdminAccess.Allowed else AdminAccess.Denied
    cached?.isAdmin == true -> AdminAccess.Allowed
    else -> AdminAccess.Checking
}

// ---------- 原因 ----------

const val ADMIN_REASON_MAX = 200

/** 管理操作的原因：去掉首尾空白后 1–200 字（按字符计，表情算 1 个）。为空不能确认。 */
fun adminReasonValid(reason: String): Boolean = reasonLength(reason) in 1..ADMIN_REASON_MAX

fun reasonLength(reason: String): Int = reason.trim().let { it.codePointCount(0, it.length) }

// ---------- 处罚 ----------

enum class SanctionKind(val api: String, val label: String, val verb: String) {
    Mute("mute", "禁言", "禁言（不能在班级频道发消息）"),
    PostBan("post_ban", "禁发", "全站禁发（不能发作业、补充）"),
    Disable("disable", "封号", "封号（立即退出登录、不能再登录）"),
    ;

    companion object {
        fun of(api: String): SanctionKind? = entries.firstOrNull { it.api == api }
    }
}

/** 天数选项；null = 永久（只有封号可选）。 */
fun sanctionDayOptions(kind: SanctionKind): List<Int?> =
    listOf(1, 3, 7, 30, 90, 365) + if (kind == SanctionKind.Disable) listOf(null) else emptyList()

fun daysLabel(days: Int?): String = if (days == null) "永久" else "$days 天"

/**
 * 组装处罚请求；不合法返回 null（界面据此禁用确认）：原因必填；禁言、禁发必须给 1–365 天；封号可以不给天数（永久）。
 */
fun buildSanction(kind: SanctionKind, days: Int?, reason: String): SanctionCreate? {
    if (!adminReasonValid(reason)) return null
    if (days != null && days !in 1..365) return null
    if (days == null && kind != SanctionKind.Disable) return null
    return SanctionCreate(kind.api, days, reason.trim())
}

/** 处罚是否仍在生效（未解除且未到期）。 */
fun sanctionActive(s: Sanction, now: ZonedDateTime = com.swufe.homework.util.Ddl.now()): Boolean {
    if (s.liftedAt != null) return false
    val end = s.endsAt?.let { com.swufe.homework.util.Ddl.parse(it) } ?: return true
    return end.isAfter(now)
}

fun sanctionLabel(kind: String): String = SanctionKind.of(kind)?.label ?: kind

// ---------- 贡献值 ----------

const val POINTS_DELTA_MAX = 100

/** 调整贡献值：必须选班、delta 在 −100..100 且非 0、原因必填。 */
fun pointsAdjustValid(classId: Int?, delta: Int?, reason: String): Boolean =
    classId != null && delta != null && delta != 0 && delta in -POINTS_DELTA_MAX..POINTS_DELTA_MAX && adminReasonValid(reason)

// ---------- 公告 ----------

const val ANNOUNCE_MIN_DAYS = 7L

/** 公告生效时间：现在 + [days] 天，向上取整到整点（保证 ≥ 7 天，服务器不足 7 天会 400 effective_too_soon）。 */
fun announcementEffectiveAt(now: ZonedDateTime, days: Long): ZonedDateTime {
    val d = days.coerceAtLeast(ANNOUNCE_MIN_DAYS)
    val t = now.plusDays(d)
    val hour = t.truncatedTo(ChronoUnit.HOURS)
    return if (hour == t) hour else hour.plusHours(1)
}

// ---------- 举报 ----------

enum class ReportAction(val label: String) {
    DeleteContent("删除内容"),
    HideClass("隐藏班级"),
    Punish("处罚用户"),
    Dismiss("驳回"),
}

/** 每种举报对象可以直接做的处置。已处理的举报不再给操作。 */
fun reportActions(r: AdminReport): List<ReportAction> {
    if (r.handledAt != null) return emptyList()
    return when (r.targetKind) {
        "source", "message" ->
            if (r.target == null) listOf(ReportAction.Dismiss) // 对象已被硬删
            else listOf(ReportAction.DeleteContent, ReportAction.HideClass, ReportAction.Punish, ReportAction.Dismiss)
        "class" -> listOf(ReportAction.HideClass, ReportAction.Dismiss)
        "user" -> listOf(ReportAction.Punish, ReportAction.Dismiss)
        else -> listOf(ReportAction.Dismiss)
    }
}

/** 处置后自动 resolve 的结果：驳回 = dismissed，其余 = removed。 */
fun resolutionFor(a: ReportAction): String = if (a == ReportAction.Dismiss) "dismissed" else "removed"

fun reportKindLabel(kind: String): String = when (kind) {
    "source" -> "作业内容"
    "message" -> "频道消息"
    "class" -> "班级"
    "user" -> "用户"
    "merge_proposal" -> "作业合并"
    "announcement" -> "公告"
    else -> kind
}

fun resolutionLabel(r: String?): String = when (r) {
    "removed" -> "已处置"
    "dismissed" -> "已驳回"
    else -> "未处理"
}

private val targetJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private fun <T> AdminReport.decode(kind: String, s: KSerializer<T>): T? =
    if (targetKind != kind) null else (target as? JsonObject)?.let { runCatching { targetJson.decodeFromJsonElement(s, it) }.getOrNull() }

fun AdminReport.sourceTarget(): ReportSourceTarget? = decode("source", ReportSourceTarget.serializer())
fun AdminReport.messageTarget(): ReportMessageTarget? = decode("message", ReportMessageTarget.serializer())
fun AdminReport.classTarget(): ReportClassTarget? = decode("class", ReportClassTarget.serializer())
fun AdminReport.userTarget(): AdminUser? = decode("user", AdminUser.serializer())

/** 被举报内容的作者昵称（处罚用户时按昵称查人；昵称全站唯一）。 */
fun AdminReport.authorNickname(): String? = sourceTarget()?.authorNickname ?: messageTarget()?.authorNickname

/** 被举报内容所在的班级名（隐藏班级时按班名查班）。 */
fun AdminReport.className(): String? = sourceTarget()?.className ?: messageTarget()?.className

/** 按昵称查到的用户里取昵称完全一致的那个（/admin/users 是模糊搜）。 */
fun exactUser(users: List<AdminUser>, nickname: String): AdminUser? = users.firstOrNull { it.nickname == nickname }

// ---------- 操作日志 ----------

fun actionLabel(action: String): String = when (action) {
    "report_resolve" -> "处理举报"
    "class_update" -> "改班名"
    "class_hide" -> "隐藏班级"
    "class_unhide" -> "恢复班级"
    "class_merge" -> "合并班级"
    "class_merge_revert" -> "撤销班级合并"
    "merge_revert" -> "撤销作业合并"
    "source_delete" -> "删除作业内容"
    "source_restore" -> "恢复作业内容"
    "message_delete" -> "删除频道消息"
    "user_nickname_reset" -> "改昵称"
    "sanction_add" -> "处罚"
    "sanction_lift" -> "解除处罚"
    "points_adjust" -> "调整贡献值"
    "announcement_create" -> "发布公告"
    else -> action
}
