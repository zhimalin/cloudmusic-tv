package com.cloudmusic.core.player

import androidx.media3.common.PlaybackException

/** 播放器的高层状态；[PlaybackState.status] 使用它。 */
enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/**
 * 播放器对外暴露的完整状态快照。
 *
 * UI 直接 `collect` [MusicPlayer.state] 即可驱动播放页；所有字段都是不可变值，
 * 每次变更整体替换，避免 UI 读到半更新状态。
 */
data class PlaybackState(
    val track: Track? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    /** ExoPlayer 的 `isPlaying`（缓冲/暂停时为 false）。 */
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val bufferedPercent: Int = 0,
    val mode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    /** 当前曲目在 [PlaybackQueue] 里的下标；没有队列时为 -1。 */
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    /** 当前地址是否只有试听片段，UI 应显示「试听」。 */
    val isTrialOnly: Boolean = false,
    /** 实际拿到的音质档位（`standard` / `higher` / `exhigh` / `lossless`）。 */
    val level: String? = null,
    /** 最近一次失败；成功开始新曲目时清空。 */
    val error: PlaybackError? = null,
) {
    /** 0f..1f 的播放进度；时长未知时为 0。 */
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 播放失败信息。
 *
 * [errorCodeName] 的取值：
 * - ExoPlayer 播放/解码/网络失败：原样保留 `PlaybackException.errorCodeName`
 *   （例如 `ERROR_CODE_IO_BAD_HTTP_STATUS`），[errorCode] 同时保留整型码；
 * - 地址解析失败：`URL_RESOLVE_FAILED`，或按异常类型细分
 *   （`URL_RESOLVE_API_<code>` / `URL_RESOLVE_HTTP_<status>` / `URL_RESOLVE_TRANSPORT` / `URL_RESOLVE_PARSE`）；
 * - 音频焦点被拒绝：`AUDIO_FOCUS_DENIED`。
 *
 * 不吞异常：异常同时会以 `Log.e` 打到 logcat，`message` / `causeClass` 保留原始信息。
 */
data class PlaybackError(
    val errorCodeName: String,
    val errorCode: Int? = null,
    val message: String? = null,
    val causeClass: String? = null,
) {
    companion object {
        const val URL_RESOLVE_FAILED = "URL_RESOLVE_FAILED"
        const val AUDIO_FOCUS_DENIED = "AUDIO_FOCUS_DENIED"

        /** ExoPlayer 的 [PlaybackException] → 对外的 [PlaybackError]，errorCodeName 原样保留。 */
        fun from(exception: PlaybackException): PlaybackError = PlaybackError(
            errorCodeName = exception.errorCodeName,
            errorCode = exception.errorCode,
            message = exception.message,
            causeClass = exception.cause?.javaClass?.name,
        )
    }
}
