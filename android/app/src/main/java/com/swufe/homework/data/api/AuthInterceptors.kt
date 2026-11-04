package com.swufe.homework.data.api

import com.swufe.homework.data.local.TokenSource
import retrofit2.HttpException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** 无需登录的接口（与 api.yaml 中 security: [] 一致）。游客浏览 guest/assignments 也在这里：不带 token、不续期。 */
internal val PUBLIC_PATHS = listOf("auth/campus-check", "auth/register", "auth/login", "auth/refresh", "auth/recover", "meta", "guest/assignments")

private fun Request.isPublic(): Boolean {
    val p = url.encodedPath
    return PUBLIC_PATHS.any { p.endsWith("/$it") }
}

class AuthHeaderInterceptor @Inject constructor(private val tokens: TokenSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val at = tokens.accessToken()
        if (req.isPublic() || at.isNullOrEmpty() || req.header("Authorization") != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $at").build())
    }
}

/** 会话失效（refresh 也失败）时通知 UI 回到登录页。 */
@Singleton
class SessionEvents @Inject constructor() {
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired
    fun emitExpired() { _expired.tryEmit(Unit) }
}

/**
 * 401 时用 refresh token 续期一次并重放请求。并发的 401 串行化，避免同一个 refresh token 被用两次
 * （服务端每次 refresh 都轮换，旧的立即作废）。
 * 只有 refresh 本身返回 401（refresh token 失效）才清空登录态；网络错误、超时、5xx 保留 token，
 * 原请求以错误结束，下次再试。
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokens: TokenSource,
    private val refreshApi: Provider<AuthRefreshApi>,
    private val session: SessionEvents,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val req = response.request
        if (req.isPublic() || responseCount(response) >= 2) return null
        val used = req.header("Authorization")?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = tokens.accessToken()
            // 别的线程已经续期过了，直接用新的
            if (!current.isNullOrEmpty() && current != used) return req.withToken(current)
            val rt = tokens.refreshToken() ?: return null.also { session.emitExpired() }
            val fresh = try {
                runBlocking { refreshApi.get().refresh(RefreshRequest(rt)) }
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    runBlocking { tokens.clear() }
                    session.emitExpired()
                }
                return null
            } catch (e: Exception) {
                // 网络抖动等：保留 token，不登出
                return null
            }
            runBlocking { tokens.save(fresh) }
            return req.withToken(fresh.accessToken)
        }
    }

    private fun Request.withToken(t: String) = newBuilder().header("Authorization", "Bearer $t").build()

    private fun responseCount(r: Response): Int {
        var n = 1
        var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }
}
