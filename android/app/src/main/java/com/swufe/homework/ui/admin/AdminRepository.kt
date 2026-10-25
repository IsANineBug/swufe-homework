package com.swufe.homework.ui.admin

import com.swufe.homework.data.api.AdminAction
import com.swufe.homework.data.api.AdminApi
import com.swufe.homework.data.api.AdminClass
import com.swufe.homework.data.api.AdminClassPatch
import com.swufe.homework.data.api.AdminDeletedSource
import com.swufe.homework.data.api.AdminReport
import com.swufe.homework.data.api.AdminSourceDelete
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.AnnouncementCreate
import com.swufe.homework.data.api.ClassMergeRecord
import com.swufe.homework.data.api.ClassMergeRequest
import com.swufe.homework.data.api.NicknameReset
import com.swufe.homework.data.api.PointsAdjust
import com.swufe.homework.data.api.ReasonRequest
import com.swufe.homework.data.api.ResolveReportRequest
import com.swufe.homework.data.api.RestoreRequest
import com.swufe.homework.data.api.Sanction
import com.swufe.homework.data.api.SanctionCreate
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.api.orThrow
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理员接口（api.yaml tag admin）。放在 ui/admin 里，不改 di/AppModule：用注入的服务器 Retrofit 自己 create。
 * 所有方法返回 Result（失败为 ApiException，message 可直接展示）。服务器对非管理员一律 403。
 */
@Singleton
class AdminRepository internal constructor(private val api: AdminApi) {
    @Inject constructor(retrofit: Retrofit) : this(retrofit.create(AdminApi::class.java))

    suspend fun reports(status: String): Result<List<AdminReport>> = apiCall { api.reports(status) }
    suspend fun resolve(reportId: Long, resolution: String): Result<Unit> =
        apiCall { api.resolveReport(reportId, ResolveReportRequest(resolution)).orThrow() }

    suspend fun classes(q: String?, suspicious: Boolean, includeHidden: Boolean, page: Int = 1): Result<List<AdminClass>> =
        apiCall { api.classes(q?.trim()?.ifEmpty { null }, if (suspicious) "suspicious" else "all", includeHidden, page) }
    suspend fun patchClass(id: Int, name: String, teacher: String?, reason: String): Result<AdminClass> =
        apiCall { api.patchClass(id, AdminClassPatch(name = name.trim(), teacher = teacher?.trim(), reason = reason.trim())) }
    suspend fun hideClass(id: Int, reason: String): Result<Unit> = apiCall { api.hideClass(id, ReasonRequest(reason.trim())).orThrow() }
    suspend fun unhideClass(id: Int): Result<Unit> = apiCall { api.unhideClass(id).orThrow() }
    suspend fun mergeClasses(from: Int, into: Int, reason: String): Result<Long?> =
        apiCall { api.mergeClasses(ClassMergeRequest(from, into, reason.trim())).mergeId }
    suspend fun classMerges(): Result<List<ClassMergeRecord>> = apiCall { api.classMerges() }
    suspend fun revertClassMerge(id: Long): Result<Unit> = apiCall { api.revertClassMerge(id).orThrow() }

    suspend fun deleteSource(id: Long, reason: String, penalize: Boolean): Result<Unit> =
        apiCall { api.deleteSource(id, AdminSourceDelete(reason.trim(), penalize)).orThrow() }
    suspend fun deleteMessage(id: Long, reason: String): Result<Unit> =
        apiCall { api.deleteMessage(id, ReasonRequest(reason.trim())).orThrow() }
    suspend fun deleted(): Result<List<AdminDeletedSource>> = apiCall { api.deleted() }
    suspend fun restore(id: Int, liftBan: Boolean): Result<Unit> = apiCall { api.restoreSource(id, RestoreRequest(liftBan)).orThrow() }

    suspend fun users(q: String): Result<List<AdminUser>> = apiCall { api.users(q.trim()) }
    suspend fun user(id: Int): Result<AdminUser> = apiCall { api.user(id) }
    suspend fun resetNickname(id: Int, nickname: String, reason: String): Result<AdminUser> =
        apiCall { api.resetNickname(id, NicknameReset(nickname.trim(), reason.trim())) }
    suspend fun sanction(userId: Int, req: SanctionCreate): Result<Sanction> = apiCall { api.sanction(userId, req) }
    suspend fun lift(sanctionId: Long): Result<Unit> = apiCall { api.liftSanction(sanctionId).orThrow() }
    suspend fun adjustPoints(userId: Int, classId: Int, delta: Int, reason: String): Result<Unit> =
        apiCall { api.adjustPoints(userId, PointsAdjust(classId, delta, reason.trim())).orThrow() }

    suspend fun announce(title: String, body: String, effectiveAt: String): Result<Long?> =
        apiCall { api.announce(AnnouncementCreate(title.trim(), body.trim(), effectiveAt)).id }
    suspend fun actions(): Result<List<AdminAction>> = apiCall { api.actions() }
}
