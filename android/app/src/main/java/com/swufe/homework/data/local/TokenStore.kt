package com.swufe.homework.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swufe.homework.data.api.Tokens
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authStore by preferencesDataStore(name = "auth")

/**
 * access / refresh token 存 DataStore。内存里再缓存一份，供 OkHttp 拦截器同步读取。
 * 注意：恢复码从不落盘。
 */
@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kAccess = stringPreferencesKey("access_token")
    private val kRefresh = stringPreferencesKey("refresh_token")

    @Volatile private var cache: Pair<String?, String?>? = null

    val loggedIn: Flow<Boolean> = ctx.authStore.data.map { !it[kRefresh].isNullOrEmpty() }

    private fun load(): Pair<String?, String?> =
        cache ?: runBlocking {
            val p = ctx.authStore.data.first()
            (p[kAccess] to p[kRefresh]).also { cache = it }
        }

    fun accessToken(): String? = load().first
    fun refreshToken(): String? = load().second

    suspend fun save(t: Tokens) {
        cache = t.accessToken to t.refreshToken
        ctx.authStore.edit {
            it[kAccess] = t.accessToken
            it[kRefresh] = t.refreshToken
        }
    }

    suspend fun clear() {
        cache = null to null
        ctx.authStore.edit { it.clear() }
    }
}
