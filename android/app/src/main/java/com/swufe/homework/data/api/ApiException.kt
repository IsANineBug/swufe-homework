package com.swufe.homework.data.api

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** 统一的错误：code 来自服务端 Error.code，网络问题为 network。message 可直接展示。 */
class ApiException(val code: String, override val message: String, val http: Int = 0) : Exception(message)

private val errJson = Json { ignoreUnknownKeys = true }

fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is HttpException -> {
        val raw = response()?.errorBody()?.string()
        val parsed = raw?.let { runCatching { errJson.decodeFromString(ApiError.serializer(), it) }.getOrNull() }
        if (parsed != null) ApiException(parsed.code, parsed.message, code())
        else ApiException("http_${code()}", "服务器出错了（${code()}），请稍后再试", code())
    }
    is javax.net.ssl.SSLException -> ApiException("tls", "无法建立安全连接：服务器证书与 App 内置的不一致")
    is IOException -> ApiException("network", "网络连接失败，请检查网络")
    else -> ApiException("unknown", this.message ?: "出错了")
}

/** 在 ViewModel 里用：把任何异常统一成 Result<ApiException>。 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e.toApiException())
    }

val Throwable.userMessage: String get() = (this as? ApiException)?.message ?: toApiException().message
