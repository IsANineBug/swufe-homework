package com.swufe.homework.data.repo

import com.swufe.homework.data.api.LoginRequest
import com.swufe.homework.data.api.RecoverRequest
import com.swufe.homework.data.api.RegisterRequest
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.local.AppDatabase
import com.swufe.homework.data.local.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: SwufeApi,
    private val tokens: TokenStore,
    private val db: AppDatabase,
) {
    val loggedIn = tokens.loggedIn

    suspend fun campusCheck(): Result<Boolean> = apiCall { api.campusCheck().campus }

    /** 成功返回恢复码（仅此一次），调用方必须立即展示给用户。 */
    suspend fun register(nickname: String, password: String, inviteCode: String?): Result<String> = apiCall {
        val t = api.register(RegisterRequest(nickname.trim(), password, inviteCode?.trim()?.ifEmpty { null }))
        tokens.save(t)
        t.recoveryCode ?: ""
    }

    suspend fun login(nickname: String, password: String): Result<Unit> = apiCall {
        tokens.save(api.login(LoginRequest(nickname.trim(), password)))
    }

    /** 成功返回新恢复码。找回后需再登录。 */
    suspend fun recover(nickname: String, code: String, newPassword: String): Result<String> = apiCall {
        api.recover(RecoverRequest(nickname.trim(), normalizeRecoveryCode(code), newPassword)).newRecoveryCode
    }

    suspend fun logout() {
        runCatching { api.logout() }
        tokens.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    suspend fun clearLocal() {
        tokens.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    companion object {
        /** 用户可能输入小写、空格或不带短横线，统一成 XXXX-XXXX-XXXX-XXXX。 */
        fun normalizeRecoveryCode(raw: String): String {
            val s = raw.uppercase().filter { it.isLetterOrDigit() }
            return if (s.length == 16) s.chunked(4).joinToString("-") else raw.trim().uppercase()
        }
    }
}
