package com.swufe.homework.di

import android.content.Context
import androidx.room.Room
import com.swufe.homework.BuildConfig
import com.swufe.homework.data.api.AuthHeaderInterceptor
import com.swufe.homework.data.api.AuthRefreshApi
import com.swufe.homework.data.api.GitHubApi
import com.swufe.homework.data.api.PinnedTrust
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.TokenAuthenticator
import com.swufe.homework.data.local.AppDatabase
import com.swufe.homework.data.local.TimetableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class ServerBase
@Qualifier annotation class ServerAuthed

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 带证书固定、但不带鉴权的基础客户端。 */
    @Provides @Singleton @ServerBase
    fun baseClient(): OkHttpClient {
        val host = URI(BuildConfig.SERVER_URL).host ?: "127.0.0.1"
        val tls = PinnedTrust.build(BuildConfig.CERT_PIN, host)
        return OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .apply { tls.hostnameVerifier?.let { hostnameVerifier(it) } }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton @ServerAuthed
    fun authedClient(
        @ServerBase base: OkHttpClient,
        header: AuthHeaderInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder().addInterceptor(header).authenticator(authenticator).build()

    private fun retrofit(client: OkHttpClient, json: Json, baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    /** 服务器 Retrofit：WS5 的接口 interface 也从这里 create。 */
    @Provides @Singleton
    fun serverRetrofit(@ServerAuthed client: OkHttpClient, json: Json): Retrofit =
        retrofit(client, json, BuildConfig.SERVER_URL)

    @Provides @Singleton
    fun swufeApi(r: Retrofit): SwufeApi = r.create(SwufeApi::class.java)

    @Provides @Singleton
    fun refreshApi(@ServerBase client: OkHttpClient, json: Json): AuthRefreshApi =
        retrofit(client, json, BuildConfig.SERVER_URL).create(AuthRefreshApi::class.java)

    @Provides @Singleton
    fun gitHubApi(json: Json): GitHubApi =
        retrofit(
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
            json, "https://api.github.com/",
        ).create(GitHubApi::class.java)

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "swufe.db").build()

    @Provides
    fun timetableDao(db: AppDatabase): TimetableDao = db.timetableDao()
}
