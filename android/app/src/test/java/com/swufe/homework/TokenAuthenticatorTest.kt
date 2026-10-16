package com.swufe.homework

import com.swufe.homework.data.api.AuthHeaderInterceptor
import com.swufe.homework.data.api.AuthRefreshApi
import com.swufe.homework.data.api.SessionEvents
import com.swufe.homework.data.api.TokenAuthenticator
import com.swufe.homework.data.api.Tokens
import com.swufe.homework.data.local.TokenSource
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    private class FakeTokens(var access: String?, var refresh: String?) : TokenSource {
        var cleared = 0
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override suspend fun save(t: Tokens) { access = t.accessToken; refresh = t.refreshToken }
        override suspend fun clear() { cleared++; access = null; refresh = null }
    }

    private lateinit var server: MockWebServer
    private val refreshCalls = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    private fun client(tokens: FakeTokens, session: SessionEvents = SessionEvents()): OkHttpClient {
        val refreshApi = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(AuthRefreshApi::class.java)
        return OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, { refreshApi }, session))
            .build()
    }

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().use { it.code }

    /** /me：带 new-access 才 200；/auth/refresh 按 [refreshResponse] 回。 */
    private fun dispatch(refreshResponse: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> { refreshCalls.incrementAndGet(); refreshResponse() }
                else -> if (request.getHeader("Authorization") == "Bearer new-access") MockResponse().setBody("{}")
                else MockResponse().setResponseCode(401)
            }
        }
    }

    private val okRefresh = {
        Thread.sleep(200) // 让并发请求都撞上 401
        MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":900}""")
    }

    @Test fun refreshesOnceAndRetries() {
        dispatch(okRefresh)
        val t = FakeTokens("old-access", "old-refresh")
        assertEquals(200, get(client(t)))
        assertEquals(1, refreshCalls.get())
        assertEquals("new-refresh", t.refresh)
    }

    @Test fun concurrent401sRefreshOnlyOnce() {
        dispatch(okRefresh)
        val t = FakeTokens("old-access", "old-refresh")
        val c = client(t)
        val pool = Executors.newFixedThreadPool(5)
        val done = CountDownLatch(5)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(5) { pool.execute { codes += get(c); done.countDown() } }
        done.await(10, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(List(5) { 200 }, codes)
        assertEquals(1, refreshCalls.get())
    }

    @Test fun refresh401ClearsAndExpires() {
        dispatch { MockResponse().setResponseCode(401).setBody("""{"code":"bad_refresh_token","message":"请重新登录"}""") }
        val t = FakeTokens("old-access", "old-refresh")
        val session = SessionEvents()
        assertEquals(401, get(client(t, session)))
        assertEquals(1, t.cleared)
        assertNull(t.refresh)
    }

    @Test fun refreshServerErrorKeepsTokens() {
        dispatch { MockResponse().setResponseCode(503) }
        val t = FakeTokens("old-access", "old-refresh")
        assertEquals(401, get(client(t)))
        assertEquals(0, t.cleared)
        assertEquals("old-refresh", t.refresh)
    }

    @Test fun refreshNetworkErrorKeepsTokens() {
        dispatch { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START) }
        val t = FakeTokens("old-access", "old-refresh")
        assertEquals(401, get(client(t)))
        assertEquals(0, t.cleared)
        assertEquals("old-refresh", t.refresh)
    }

    @Test fun second401DoesNotLoop() {
        // refresh 成功，但服务器仍然 401：只续期一次，不循环
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/v1/auth/refresh") {
                    refreshCalls.incrementAndGet()
                    MockResponse().setBody("""{"access_token":"a${refreshCalls.get()}","refresh_token":"r","expires_in":900}""")
                } else MockResponse().setResponseCode(401)
        }
        val t = FakeTokens("old-access", "old-refresh")
        assertEquals(401, get(client(t)))
        assertEquals(1, refreshCalls.get())
    }

    @Test fun publicPathsNeverRefresh() {
        dispatch(okRefresh)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/api/v1/auth/refresh") { refreshCalls.incrementAndGet(); MockResponse() }
                else MockResponse().setResponseCode(401)
        }
        val t = FakeTokens("old-access", "old-refresh")
        val code = client(t).newCall(Request.Builder().url(server.url("/api/v1/auth/login")).post(ByteArray(0).toRequestBody()).build()).execute().use { it.code }
        assertEquals(401, code)
        assertEquals(0, refreshCalls.get())
    }
}
