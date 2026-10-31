package com.swufe.homework.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * 游客浏览（docs/api.yaml GET /guest/assignments，security: []）：不登录随机看一批匿名作业。
 * 路径在 [PUBLIC_PATHS] 里：不带 Bearer，401 也不会触发 refresh。
 */
interface GuestApi {
    @GET("guest/assignments")
    suspend fun sample(): List<GuestAssignment>
}

/** api.yaml components.schemas.GuestAssignment：作者只有代号（同学A），没有用户 id、附件链接、完成状态。 */
@Serializable
data class GuestAssignment(
    val id: Int,
    @SerialName("class_name") val className: String = "",
    val title: String,
    val ddl: String,
    @SerialName("ddl_uncertain") val ddlUncertain: Boolean = false,
    @SerialName("ddl_conflict") val ddlConflict: Boolean = false,
    @SerialName("source_count") val sourceCount: Int = 1,
    @SerialName("first_poster") val firstPoster: String = "",
    val sources: List<GuestSource> = emptyList(),
) {
    /** 复用 [com.swufe.homework.ui.homework.AssignmentCard]：classId 用 0（游客进不了班级页），done 恒为 false。 */
    fun toSummary(): AssignmentSummary = AssignmentSummary(
        id = id, classId = 0, className = className, title = title, ddl = ddl,
        ddlUncertain = ddlUncertain, ddlConflict = ddlConflict, sourceCount = sourceCount,
    )
}

@Serializable
data class GuestSource(
    val kind: String = "supplement",
    val author: String = "",
    val body: String = "",
    @SerialName("stated_ddl") val statedDdl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("attachment_count") val attachmentCount: Int = 0,
) {
    val isFirst: Boolean get() = kind == "first"
}
