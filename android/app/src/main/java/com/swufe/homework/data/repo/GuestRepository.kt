package com.swufe.homework.data.repo

import com.swufe.homework.data.api.GuestApi
import com.swufe.homework.data.api.GuestAssignment
import com.swufe.homework.data.api.apiCall
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游客浏览：只读、不存 token、不落库。最近一次抽到的样例留在内存里，详情页直接从这里取
 * （服务器没有游客按 id 取详情的接口）。进程被回收后样例就没了，详情页会提示回列表换一批。
 */
@Singleton
class GuestRepository @Inject constructor(retrofit: Retrofit) {
    private val api = retrofit.create(GuestApi::class.java)

    @Volatile
    var last: List<GuestAssignment> = emptyList()
        private set

    suspend fun sample(): Result<List<GuestAssignment>> =
        apiCall { api.sample() }.onSuccess { last = it }

    fun find(id: Int): GuestAssignment? = last.firstOrNull { it.id == id }
}
