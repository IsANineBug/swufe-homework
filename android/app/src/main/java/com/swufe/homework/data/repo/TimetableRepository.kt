package com.swufe.homework.data.repo

import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Period
import com.swufe.homework.data.api.SlotInput
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.TimetableImportRequest
import com.swufe.homework.data.api.TimetableImportResult
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.local.PeriodEntity
import com.swufe.homework.data.local.TimetableDao
import com.swufe.homework.data.local.toPeriod
import com.swufe.homework.data.local.toSlot
import com.swufe.homework.util.ImageCompressor
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 西财作息（requirements.md §四），/meta 拿不到时兜底。 */
val DEFAULT_PERIODS = listOf(
    "08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00",
    "13:00" to "13:45", "13:55" to "14:40", "14:50" to "15:35", "15:45" to "16:30",
    "16:40" to "17:25", "18:30" to "19:15", "19:25" to "20:10", "20:20" to "21:05",
).mapIndexed { i, (s, e) -> Period(i + 1, s, e) }

@Singleton
class TimetableRepository @Inject constructor(
    private val api: SwufeApi,
    private val dao: TimetableDao,
    private val compressor: ImageCompressor,
) {
    val classes: Flow<List<ClassInfo>> = combine(dao.classes(), dao.slots()) { cs, ss ->
        val byClass = ss.groupBy { it.classId }
        cs.map { c -> ClassInfo(c.id, c.name, c.teacher, byClass[c.id].orEmpty().map { it.toSlot() }, c.memberCount) }
    }

    val periods: Flow<List<Period>> = dao.periods().map { p -> if (p.size == 12) p.map { it.toPeriod() } else DEFAULT_PERIODS }

    suspend fun refreshMeta(): Result<Unit> = apiCall {
        val m = api.meta()
        if (m.periods.isNotEmpty()) dao.insertPeriods(m.periods.map { PeriodEntity(it.no, it.start, it.end) })
    }

    suspend fun refreshClasses(): Result<Unit> = apiCall { dao.replaceAll(api.myClasses()) }

    suspend fun classDetail(id: Int): Result<ClassInfo> = apiCall { api.classInfo(id) }

    suspend fun cachedClass(id: Int): ClassInfo? =
        dao.classById(id)?.let { c -> ClassInfo(c.id, c.name, c.teacher, dao.slotsOf(id).map { it.toSlot() }, c.memberCount) }

    suspend fun leave(id: Int): Result<Unit> = apiCall {
        val r = api.leaveClass(id)
        if (!r.isSuccessful && r.code() != 404) throw retrofit2.HttpException(r)
        dao.removeClass(id)
    }

    suspend fun search(q: String): Result<List<ClassInfo>> = apiCall { api.searchClasses(q.trim()) }

    suspend fun aiOnline(): Boolean = apiCall { api.aiStatus().online }.getOrDefault(false)

    /** 导入结果和缓存刷新分开：导入成功后即使刷新 /me/classes 失败，也按成功返回，避免用户重复提交。 */
    suspend fun import(slots: List<SlotInput>): Result<TimetableImportResult> =
        apiCall { api.importTimetable(TimetableImportRequest(slots)) }
            .onSuccess { refreshClasses() }

    /**
     * 压缩并上传课表截图，返回 attachment_ids。识别本身交给 [AiJobTracker]（parse_timetable，只传 attachment_ids，
     * issue #4 裁决 6：服务器校验归属后自行签名生成 image_urls），AI 只出草稿，不入库。
     */
    suspend fun uploadScreenshots(images: List<Uri>): Result<List<Int>> = apiCall {
        require(images.size in 1..2)
        images.map { uri ->
            val bytes = compressor.compress(uri)
            val part = MultipartBody.Part.createFormData(
                "file", "timetable.jpg", bytes.toRequestBody("image/jpeg".toMediaType()),
            )
            api.upload(part).id
        }
    }
}
