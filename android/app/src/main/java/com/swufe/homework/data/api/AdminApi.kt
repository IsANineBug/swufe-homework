package com.swufe.homework.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * v2（WS11）：docs/api.yaml 的管理员接口（tag admin），服务器要求 role=admin，其他人 403。
 * 与 [SwufeApi] / [HomeworkApi] 共用同一个 Retrofit（证书固定 + Bearer + 401 续期）。
 *
 * 刻意**没有**任何浏览频道的接口：管理员只能在举报（/admin/reports）里看到被举报消息与前后各 5 条上下文。
 * 写操作返回 204 的用 Response<Unit>，调用方用 [orThrow] 转异常。
 */
interface AdminApi {
    // ---- 举报 ----
    @GET("admin/reports")
    suspend fun reports(@Query("status") status: String = "open"): List<AdminReport>

    @POST("admin/reports/{report_id}/resolve")
    suspend fun resolveReport(@Path("report_id") id: Long, @Body body: ResolveReportRequest): Response<Unit>

    // ---- 班级 ----
    @GET("admin/classes")
    suspend fun classes(
        @Query("q") q: String? = null,
        @Query("filter") filter: String = "all",
        @Query("include_hidden") includeHidden: Boolean = false,
        @Query("page") page: Int = 1,
    ): List<AdminClass>

    @PATCH("admin/classes/{class_id}")
    suspend fun patchClass(@Path("class_id") id: Int, @Body body: AdminClassPatch): AdminClass

    @POST("admin/classes/{class_id}/hide")
    suspend fun hideClass(@Path("class_id") id: Int, @Body body: ReasonRequest): Response<Unit>

    @POST("admin/classes/{class_id}/unhide")
    suspend fun unhideClass(@Path("class_id") id: Int): Response<Unit>

    @POST("admin/classes/merge")
    suspend fun mergeClasses(@Body body: ClassMergeRequest): ClassMergeResult

    @GET("admin/class-merges")
    suspend fun classMerges(): List<ClassMergeRecord>

    @POST("admin/class-merges/{merge_id}/revert")
    suspend fun revertClassMerge(@Path("merge_id") id: Long): Response<Unit>

    // ---- 内容 ----
    @HTTP(method = "DELETE", path = "admin/sources/{source_id}", hasBody = true)
    suspend fun deleteSource(@Path("source_id") id: Long, @Body body: AdminSourceDelete): Response<Unit>

    @HTTP(method = "DELETE", path = "admin/messages/{message_id}", hasBody = true)
    suspend fun deleteMessage(@Path("message_id") id: Long, @Body body: ReasonRequest): Response<Unit>

    @GET("admin/deleted")
    suspend fun deleted(): List<AdminDeletedSource>

    @POST("admin/sources/{source_id}/restore")
    suspend fun restoreSource(@Path("source_id") id: Int, @Body body: RestoreRequest): Response<Unit>

    // ---- 用户 ----
    @GET("admin/users")
    suspend fun users(@Query("q") q: String): List<AdminUser>

    @GET("admin/users/{user_id}")
    suspend fun user(@Path("user_id") id: Int): AdminUser

    @PATCH("admin/users/{user_id}")
    suspend fun resetNickname(@Path("user_id") id: Int, @Body body: NicknameReset): AdminUser

    @POST("admin/users/{user_id}/sanctions")
    suspend fun sanction(@Path("user_id") id: Int, @Body body: SanctionCreate): Sanction

    @POST("admin/sanctions/{sanction_id}/lift")
    suspend fun liftSanction(@Path("sanction_id") id: Long): Response<Unit>

    @POST("admin/users/{user_id}/points")
    suspend fun adjustPoints(@Path("user_id") id: Int, @Body body: PointsAdjust): Response<Unit>

    // ---- 公告、日志 ----
    @POST("admin/announcements")
    suspend fun announce(@Body body: AnnouncementCreate): CreatedId

    @GET("admin/actions")
    suspend fun actions(): List<AdminAction>
}
