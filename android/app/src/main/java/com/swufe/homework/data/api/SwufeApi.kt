package com.swufe.homework.data.api

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * docs/api.yaml 中 WS4 用到的接口（账号、我的、课表、AI、上传）。
 * WS5 的作业/社区接口请另建 interface，复用同一个 Retrofit 实例（见 di/NetworkModule）。
 */
interface SwufeApi {
    // ---- auth ----
    @GET("auth/campus-check")
    suspend fun campusCheck(): CampusCheck

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Tokens

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Tokens

    @POST("auth/recover")
    suspend fun recover(@Body body: RecoverRequest): RecoverResponse

    /** 带本机 refresh_token 只注销本机（issue #4 裁决 5）。 */
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    // ---- me ----
    @GET("meta")
    suspend fun meta(): Meta

    @GET("me")
    suspend fun me(): Me

    @PATCH("me")
    suspend fun patchMe(@Body body: MePatch): Me

    @GET("me/invites")
    suspend fun invites(): Invites

    @POST("me/invites")
    suspend fun createInvite(): NewInvite

    @GET("me/notices")
    suspend fun notices(): List<Notice>

    // ---- timetable ----
    @GET("me/classes")
    suspend fun myClasses(): List<ClassInfo>

    @DELETE("me/classes/{class_id}")
    suspend fun leaveClass(@Path("class_id") classId: Int): Response<Unit>

    @POST("timetable/import")
    suspend fun importTimetable(@Body body: TimetableImportRequest): TimetableImportResult

    @GET("classes/search")
    suspend fun searchClasses(@Query("q") q: String): List<ClassInfo>

    @GET("classes/{class_id}")
    suspend fun classInfo(@Path("class_id") classId: Int): ClassInfo

    // ---- uploads / ai ----
    @Multipart
    @POST("uploads")
    suspend fun upload(@Part file: MultipartBody.Part): Attachment

    @GET("ai/status")
    suspend fun aiStatus(): AiStatus

    @POST("ai/jobs")
    suspend fun createAiJob(@Body body: AiJobRequest): AiJob

    @GET("ai/jobs/{job_id}")
    suspend fun aiJob(@Path("job_id") jobId: Int): AiJob
}

/** 续期专用：不挂 Authenticator，避免递归。 */
interface AuthRefreshApi {
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Tokens
}

/** 检查更新：直连 api.github.com，不走服务器证书固定。 */
interface GitHubApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(@Path("owner") owner: String, @Path("repo") repo: String): GhRelease
}
