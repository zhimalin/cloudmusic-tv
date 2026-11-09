package com.cloudmusic.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 网络传输层的最小抽象。
 *
 * core-network 只做 POST + form-urlencoded（weapi 的请求形态就是这样），
 * 这一层不关心加密、cookie 语义和业务 code；把 HTTP 栈换成别的实现（例如测试里的假引擎）
 * 不需要动 [NeteaseClient]。
 *
 * 实现允许抛 [IOException]（连接失败、超时等）：[NeteaseClient] 会把它包装成
 * [TransportException] 并按策略重试一次。
 */
interface HttpEngine {

    /** 以 `application/x-www-form-urlencoded` 发一个 POST，返回原始响应。 */
    suspend fun postForm(
        url: String,
        headers: Map<String, String>,
        formBody: String,
    ): HttpResult
}

/** 传输层拿到的原始响应，不做任何业务解释。 */
data class HttpResult(
    val statusCode: Int,
    val body: String,
    /** 响应里的 `Set-Cookie`，留给后续扫码登录换 cookie 用。 */
    val setCookies: List<String> = emptyList(),
)

/**
 * 基于 OkHttp 的 [HttpEngine] 实现。
 *
 * 用 `enqueue` + [suspendCancellableCoroutine] 桥接，而不是 `Dispatchers.IO` 里阻塞调用：
 * 协程被取消时会同时 `cancel()` 掉底层 Call，TV 上切页面不会留下悬挂请求。
 */
class OkHttpEngine(
    private val client: OkHttpClient = defaultClient(),
) : HttpEngine {

    override suspend fun postForm(
        url: String,
        headers: Map<String, String>,
        formBody: String,
    ): HttpResult = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
        headers.forEach { (name, value) -> builder.header(name, value) }

        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    HttpResult(
                        statusCode = it.code,
                        body = it.body?.string().orEmpty(),
                        setCookies = it.headers("Set-Cookie"),
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    /** 供调用方在退出时释放连接池/线程（可选，OkHttpClient 不 close 也能被 GC）。 */
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /** 与 tools/probe/probe.mjs 的 REQ_TIMEOUT 保持一致（15s）。 */
        const val TIMEOUT_SECONDS: Long = 15L

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
