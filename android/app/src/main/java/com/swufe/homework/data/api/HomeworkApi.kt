package com.swufe.homework.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * docs/api.yaml 中 WS5 用到的接口：作业、来源、完成状态、谢谢、删除投票、合并、榜单、积分、同步。
 * 上传、AI 复用 [SwufeApi]。与 SwufeApi 共用同一个 Retrofit（证书固定 + Bearer + 401 续期）。
 */
interface HomeworkApi {
    @GET("feed")
    suspend fun feed(@Query("include_done") includeDone: Boolean = false): List<AssignmentSummary>

    @GET("sync")
    suspend fun sync(@Query("since") since: String? = null): SyncResponse

    @GET("classes/{class_id}/assignments")
    suspend fun classAssignments(
        @Path("class_id") classId: Int,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): List<AssignmentSummary>

    @POST("classes/{class_id}/assignments")
    suspend fun createAssignment(@Path("class_id") classId: Int, @Body body: AssignmentCreate): Assignment

    @POST("classes/{class_id}/assignments/candidates")
    suspend fun candidates(@Path("class_id") classId: Int, @Body body: CandidatesRequest): List<AssignmentSummary>

    @GET("classes/{class_id}/leaderboard")
    suspend fun leaderboard(@Path("class_id") classId: Int, @Query("scope") scope: String): Leaderboard

    @GET("assignments/{assignment_id}")
    suspend fun assignment(@Path("assignment_id") id: Int): Assignment

    @PATCH("assignments/{assignment_id}")
    suspend fun patchAssignment(@Path("assignment_id") id: Int, @Body body: AssignmentPatch): Assignment

    @POST("assignments/{assignment_id}/sources")
    suspend fun addSource(@Path("assignment_id") id: Int, @Body body: SourceCreate): Source

    @PUT("assignments/{assignment_id}/done")
    suspend fun markDone(@Path("assignment_id") id: Int): Response<Unit>

    @DELETE("assignments/{assignment_id}/done")
    suspend fun unmarkDone(@Path("assignment_id") id: Int): Response<Unit>

    @PATCH("sources/{source_id}")
    suspend fun editSource(@Path("source_id") id: Int, @Body body: SourceCreate): Source

    /** 只改给出的字段；可显式清空 stated_ddl（见 [SourcePatch]）。 */
    @PATCH("sources/{source_id}")
    suspend fun patchSource(@Path("source_id") id: Int, @Body body: SourcePatch): Source

    @DELETE("sources/{source_id}")
    suspend fun deleteSource(@Path("source_id") id: Int): Response<Unit>

    @POST("sources/{source_id}/thanks")
    suspend fun thank(@Path("source_id") id: Int): Response<Unit>

    @POST("sources/{source_id}/delete-votes")
    suspend fun voteDelete(@Path("source_id") id: Int): DeleteVoteResult

    @POST("merge-proposals")
    suspend fun proposeMerge(@Body body: MergeProposalCreate): MergeProposal

    @POST("merge-proposals/{proposal_id}/accept")
    suspend fun acceptMerge(@Path("proposal_id") id: Int): MergeProposal

    @POST("merge-proposals/{proposal_id}/votes")
    suspend fun voteMerge(@Path("proposal_id") id: Int): MergeProposal

    @GET("me/points")
    suspend fun points(@Query("cursor") cursor: String? = null): PointsPage
}

/** 204 / 2xx 以外的 Response<Unit> 转成异常，交给 apiCall 统一处理。 */
fun Response<Unit>.orThrow() {
    if (!isSuccessful) throw retrofit2.HttpException(this)
}
