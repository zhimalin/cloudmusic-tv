package com.cloudmusic.core.network

/**
 * core-network 对外抛出的所有异常的基类。
 *
 * 调用方可以按需 catch 具体子类，也可以统一 catch [NeteaseException]。
 * 四类错误可区分，且都带上了排查播放失败需要的 code / message：
 *
 * | 类型 | 含义 | 携带的信息 |
 * |---|---|---|
 * | [TransportException] | DNS / 连接 / TLS / 超时等传输层失败 | 原始 IOException 作为 cause |
 * | [HttpException] | HTTP 状态码非 2xx | [HttpException.statusCode]、响应体片段 |
 * | [ApiException] | HTTP 2xx 但业务 `code != 200` | [ApiException.code]、`message` |
 * | [ParseException] | 响应体不是预期 JSON（例如网关错误页） | 响应体片段 |
 *
 * 注意：这里不会用 `runCatching { }.getOrNull()` 吞掉任何失败——所有失败都会以异常形式抛出，
 * 只有「服务端明确表示没有数据」（例如未登录时 `account()` 返回 null）才是 null。
 */
sealed class NeteaseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 传输层失败：请求没有拿到任何 HTTP 响应（DNS、连接、TLS、超时、连接被重置等）。 */
class TransportException(
    message: String,
    cause: Throwable? = null,
) : NeteaseException(message, cause)

/** HTTP 失败：拿到了响应，但状态码不在 200..299。 */
class HttpException(
    val statusCode: Int,
    val responseBody: String?,
    val apiPath: String? = null,
) : NeteaseException(
    buildString {
        append("HTTP 错误 ").append(statusCode)
        if (apiPath != null) append("（").append(apiPath).append("）")
        val snippet = responseBody?.snippet()
        if (!snippet.isNullOrBlank()) append("：").append(snippet)
    },
)

/** 业务失败：HTTP 2xx，但响应 JSON 里的 `code` 不是 200。 */
class ApiException(
    val code: Int,
    val apiMessage: String,
    val apiPath: String? = null,
) : NeteaseException(
    buildString {
        append("接口业务错误 code=").append(code)
        if (apiMessage.isNotBlank()) append(" message=").append(apiMessage)
        if (apiPath != null) append("（").append(apiPath).append("）")
    },
)

/** 解析失败：响应体不是合法 JSON，或字段和模型对不上。 */
class ParseException(
    val responseBody: String?,
    val apiPath: String? = null,
    cause: Throwable? = null,
) : NeteaseException(
    buildString {
        append("响应解析失败")
        if (apiPath != null) append("（").append(apiPath).append("）")
        val snippet = responseBody?.snippet()
        if (!snippet.isNullOrBlank()) append("：").append(snippet)
    },
    cause,
)

/** 日志/异常信息里只保留前 [maxLength] 个字符，避免把整页错误 HTML 塞进异常。 */
internal fun String.snippet(maxLength: Int = 300): String =
    if (length <= maxLength) this else take(maxLength) + "…（共 $length 字符）"
