package com.swufe.homework.data.repo

import com.swufe.homework.data.api.LoginRequest
import com.swufe.homework.data.api.LogoutRequest
import com.swufe.homework.data.api.Tokens
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
    private val reminders: ReminderSync,
) {
    val loggedIn = tokens.loggedIn

    suspend fun campusCheck(): Result<Boolean> = apiCall { api.campusCheck().campus }

    /**
     * 注册成功后的 token 先只放内存，用户在恢复码页勾选「已保存」后才落盘（[confirmRegistration]）。
     * 这样 App 在恢复码页被杀时，下次启动不会直接进首页而绕过恢复码确认；恢复码本身从不落盘。
     */
    @Volatile private var pendingTokens: Tokens? = null

    /** 成功返回恢复码（仅此一次），调用方必须立即展示给用户。 */
    suspend fun register(nickname: String, password: String, inviteCode: String?): Result<String> = apiCall {
        val t = api.register(RegisterRequest(nickname.trim(), password, inviteCode?.trim()?.ifEmpty { null }))
        pendingTokens = t
        t.recoveryCode ?: ""
    }

    /** 用户确认已保存恢复码。返回 false 表示进程被回收过、内存里的 token 已丢失，需要重新登录。 */
    suspend fun confirmRegistration(): Boolean {
        val t = pendingTokens ?: return false
        tokens.save(t)
        pendingTokens = null
        return true
    }

    suspend fun login(nickname: String, password: String): Result<Unit> = apiCall {
        tokens.save(api.login(LoginRequest(nickname.trim(), password)))
    }

    /** 成功返回新恢复码。找回后需再登录。 */
    suspend fun recover(nickname: String, code: String, newPassword: String): Result<String> = apiCall {
        api.recover(RecoverRequest(nickname.trim(), normalizeRecoveryCode(code), newPassword)).newRecoveryCode
    }

    /** 只注销本机：带上本机 refresh_token（issue #4 裁决 5）。 */
    suspend fun logout() {
        tokens.refreshToken()?.let { rt -> runCatching { api.logout(LogoutRequest(rt)) } }
        reminders.reset() // 本地提醒随登录态一起清掉（WS5）
        tokens.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    suspend fun clearLocal() {
        reminders.reset()
        tokens.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    companion object {
        /** 保留昵称（issue #4 裁决 14）。服务端同样校验，这里只为提前提示。 */
        private val RESERVED = setOf("管理员", "admin", "官方", "系统")

        fun isReservedNickname(raw: String): Boolean {
            val n = com.swufe.homework.util.SlotDraft.toHalfWidth(raw).filterNot { it.isWhitespace() }.lowercase()
            return n in RESERVED
        }

        /** 用户可能输入小写、空格或不带短横线，统一成 XXXX-XXXX-XXXX-XXXX。 */
        fun normalizeRecoveryCode(raw: String): String {
            val s = raw.uppercase().filter { it.isLetterOrDigit() }
            return if (s.length == 16) s.chunked(4).joinToString("-") else raw.trim().uppercase()
        }
    }
}
