package com.cloudmusic.core.data.music

/**
 * UI-facing result of one repository call.
 *
 * The network layer's four exception types plus the "not logged in" case are
 * mapped here explicitly. No call uses `runCatching { }.getOrNull()`: every
 * failure keeps its [Throwable] in [NetworkError.cause] / [ParseError.cause],
 * and every state keeps the resulting [UiError].
 */
sealed interface MusicResult<out T> {

    /** The call succeeded and produced [value]. */
    data class Success<T>(val value: T) : MusicResult<T>

    /**
     * The account endpoint returned no profile, or the API rejected the call
     * with business code 301 (login required).
     */
    data object NeedLogin : MusicResult<Nothing>

    /**
     * Transport or HTTP failure.
     *
     * [code] is `NETWORK` for a transport failure (DNS / TLS / timeout / reset)
     * or `HTTP_<status>` for a non-2xx response, so the UI always has a stable
     * code to show.
     */
    data class NetworkError(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : MusicResult<Nothing>

    /** HTTP 2xx, but the API returned `code != 200` and it was not a login error. */
    data class BusinessError(
        val code: Int,
        val message: String,
        val apiPath: String? = null,
        val cause: Throwable? = null,
    ) : MusicResult<Nothing>

    /** The response body did not match the expected JSON shape. */
    data class ParseError(
        val message: String,
        val cause: Throwable? = null,
    ) : MusicResult<Nothing>
}

/**
 * A compact, displayable failure kept by the state containers.
 *
 * [cause] is intentionally retained for logcat/diagnostics; screens only render
 * [code] and [message].
 */
data class UiError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)

internal fun MusicResult<*>.toUiError(): UiError = when (this) {
    is MusicResult.Success, MusicResult.NeedLogin ->
        error("Success/NeedLogin is not a failure")

    is MusicResult.NetworkError -> UiError(code = code, message = message, cause = cause)
    is MusicResult.BusinessError -> UiError(
        code = "API_$code",
        message = message.ifBlank { "接口返回错误码 $code" },
        cause = cause,
    )
    is MusicResult.ParseError -> UiError(code = "PARSE", message = message, cause = cause)
}
