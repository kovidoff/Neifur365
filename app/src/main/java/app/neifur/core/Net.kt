package app.neifur.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val USER_AGENT = "Neifur/0.1 (Android)"

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val req = chain.request()
        chain.proceed(
            if (req.header("User-Agent") != null) req
            else req.newBuilder().header("User-Agent", USER_AGENT).build()
        )
    }
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** Reads the body as text and closes the response. Throws on non-2xx. */
suspend fun Call.awaitText(): String {
    val res = await()
    res.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw HttpError(it.code, body.take(300))
        return body
    }
}

class HttpError(val code: Int, val bodySnippet: String) : IOException("HTTP $code")

/** Human-readable message for the UI. */
fun Throwable.userMessage(): String = when (this) {
    is HttpError -> when (code) {
        401, 403 -> "Доступ запрещён ($code)"
        429 -> "Слишком много запросов, попробуйте чуть позже"
        in 500..599 -> "Сервис временно недоступен ($code)"
        else -> "Ошибка сервера ($code)"
    }
    is java.net.UnknownHostException, is java.net.ConnectException -> "Нет соединения с сетью"
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Сервер не отвечает"
    is ApiError -> message ?: "Ошибка"
    else -> message ?: "Что-то пошло не так"
}

open class ApiError(message: String) : Exception(message)
