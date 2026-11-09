package com.cloudmusic.core.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.cloudmusic.core.network.ApiException
import com.cloudmusic.core.network.Endpoints
import com.cloudmusic.core.network.HttpException
import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.ParseException
import com.cloudmusic.core.network.SongLevel
import com.cloudmusic.core.network.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放核心：ExoPlayer + 外部队列 + 地址解析 + 音频焦点。
 *
 * 队列/随机历史在 [PlaybackQueue]，地址降级在 [PlaybackUrlResolver]；
 * 这个类只负责把二者接起来，并把状态收敛成一个 [StateFlow]。
 *
 * ### 线程
 * 所有公开方法**必须在主线程调用**（内部会 `check`）。ExoPlayer 本身也要求主线程。
 *
 * ### 生命周期
 * 由 [PlaybackService] 通过 [PlaybackRuntime] 创建和释放。UI 不要自己 `release()`；
 * App 退到后台/划掉任务时 Service 会释放。若 Service 已被销毁，重新调用
 * [PlaybackRuntime.player] 拿新实例。
 *
 * ### 失败的可见性
 * - 地址解析失败 → `state.error.errorCodeName = URL_RESOLVE_FAILED` 或
 *   `URL_RESOLVE_API_*` / `URL_RESOLVE_HTTP_*` / `URL_RESOLVE_TRANSPORT` / `URL_RESOLVE_PARSE`；
 * - ExoPlayer 失败 → 原样保留 `PlaybackException.errorCodeName`；
 * - 音频焦点被拒绝 → `AUDIO_FOCUS_DENIED`。
 *
 * ### UI 契约（下一轮播放页按此接入）
 * ```
 * PlaybackService.start(context)                       // 1. 启动后台服务（Activity 前台时）
 * val player = PlaybackRuntime.player(context)         // 2. 进程内单例，和 Service 是同一个
 * player.play(songs.map { it.toTrack() }, startIndex)  // 3. 传队列并开始播放
 * val state by player.state.collectAsState()           // 4. 订阅状态
 * player.toggle() / next() / previous() / seekTo(ms)   // 5. 控制
 * player.setMode(PlaybackMode.SHUFFLE)                 //    切播放模式
 * // 6. 不调用 release()：Service 在 onTaskRemoved/onDestroy 里释放
 * ```
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlayer internal constructor(
    context: Context,
    client: NeteaseClient,
    initialLevel: SongLevel = SongLevel.EXHIGH,
) {

    private companion object {
        const val TAG = "MusicPlayer"
        const val TICK_INTERVAL_MS = 500L
        const val HTTP_TIMEOUT_MS = 15_000

        val MEDIA_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "MusicPlayer 只能在主线程调用（当前 looper=${Looper.myLooper()}）"
            }
        }
    }

    private val appContext = context.applicationContext
    private val resolver = PlaybackUrlResolver(client)
    private val queue = PlaybackQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    /** 对外唯一状态源。 */
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 默认音质；设置页改音质后由 UI/Service 更新，下一次取地址生效。 */
    var defaultLevel: SongLevel = initialLevel

    private val exoPlayer: ExoPlayer

    /** 给 MediaSession 用的 Player；对 UI 不暴露，避免两套控制入口。 */
    internal val player: Player

    private val audioFocus: AudioFocusController
    private val ticker: Job

    private var resolveJob: Job? = null
    private var loadToken = 0
    private var loadedTrackId: Long? = null
    private var pendingSeekMs = 0L
    private var resumeAfterFocusGain = false
    private var released = false

    /**
     * 必须声明在 `init` 之前：Kotlin 的初始化按声明顺序执行，`init` 里的
     * `exoPlayer.addListener(playerListener)` 需要这个字段已经赋值。
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                handleTrackEnded()
            } else {
                syncFromPlayer()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 不吞异常：保留 ExoPlayer 的 errorCodeName / errorCode，同时打完整堆栈。
            Log.e(TAG, "ExoPlayer 播放失败：${error.errorCodeName} (${error.errorCode})", error)
            loadedTrackId = null
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    error = PlaybackError.from(error),
                )
            }
        }
    }

    init {
        checkMainThread()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Endpoints.USER_AGENT)
            // 网易云 CDN 对部分 UA/Referer 更友好，和 core-network 的请求头保持一致。
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to Endpoints.REFERER,
                    "Origin" to Endpoints.ORIGIN,
                ),
            )
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)

        exoPlayer = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            // 第二个参数 false：关闭 ExoPlayer 自带的音频焦点处理，统一由 AudioFocusController 管。
            .setAudioAttributes(MEDIA_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ false)
            // 耳机拔出时暂停（电视上很少见，但符合系统行为）。
            .setHandleAudioBecomingNoisy(true)
            // 关屏后保持网络与 CPU，后台播放需要。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF

        audioFocus = AudioFocusController(
            appContext,
            object : AudioFocusController.Callbacks {
                override fun onFocusLost(transient: Boolean) = handleFocusLost(transient)
                override fun onFocusGained() = handleFocusGained()
            },
        )

        player = QueuePlayer(
            exoPlayer,
            object : QueuePlayer.Controls {
                override val hasNext: Boolean get() = queue.hasNext
                override val hasPrevious: Boolean get() = queue.hasPrevious

                override fun skipToNext() {
                    // 外部（通知/媒体键）的下一次优先交给 app 的 FM 钩子；
                    // 返回 false 或未注册时走原来的队列 next()，非 FM 行为不变。
                    if (PlaybackRuntime.onExternalNext?.invoke() == true) return
                    next()
                }

                override fun skipToPrevious() = previous()
            },
        )

        exoPlayer.addListener(playerListener)

        ticker = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                if (!released && (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING)) {
                    syncFromPlayer()
                }
            }
        }

        _state.value = PlaybackState(mode = queue.mode)
    }

    // ------------------------------------------------------------------ 对外控制

    /** 单曲播放：等价于用只含这一首的队列调用 [play]。 */
    fun play(track: Track) = play(listOf(track), 0)

    /**
     * 用新队列替换当前队列，并从 [startIndex] 开始播放。
     *
     * 空列表会被忽略（打 warning，不抛异常）——UI 不应该用空列表调用。
     * 该调用会清掉当前曲目并重新解析地址。
     */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        checkUsable()
        if (tracks.isEmpty()) {
            Log.w(TAG, "play() 收到空队列，忽略")
            return
        }
        queue.setQueue(tracks, startIndex)
        startCurrent(autoPlay = true)
    }

    /** 暂停并归还音频焦点。 */
    fun pause() {
        checkUsable()
        resumeAfterFocusGain = false
        audioFocus.abandon()
        exoPlayer.pause()
        syncFromPlayer()
    }

    /** 继续播放；已结束则从头开始，地址没解析成功则重新解析。 */
    fun resume() {
        checkUsable()
        val track = queue.current ?: return
        if (_state.value.error != null || loadedTrackId != track.id) {
            startCurrent(autoPlay = true)
            return
        }
        startPlaybackWithFocus()
    }

    /** 播放/暂停切换。 */
    fun toggle() {
        checkUsable()
        if (exoPlayer.playWhenReady || exoPlayer.isPlaying) pause() else resume()
    }

    /**
     * 手动下一首。**总是开始播放**（即使当前是暂停状态），和主流音乐 App 一致。
     * 顺序模式尾部回绕到第一首；随机模式见 [PlaybackQueue]。
     */
    fun next() {
        checkUsable()
        val next = queue.next(manual = true)
        if (next == null) {
            seekTo(0L)
            return
        }
        startCurrent(autoPlay = true)
    }

    /**
     * 手动上一首。**总是开始播放**。
     * 随机模式历史栈到头时回到当前曲目开头（不切歌）。
     */
    fun previous() {
        checkUsable()
        val previous = queue.previous()
        if (previous == null) {
            seekTo(0L)
            return
        }
        startCurrent(autoPlay = true)
    }

    /** 跳转到指定毫秒；地址还没解析完时先记住，解析完成后应用。 */
    fun seekTo(positionMs: Long) {
        checkUsable()
        val track = queue.current ?: return
        val duration = _state.value.durationMs.takeIf { it > 0L } ?: track.durationMs
        val target = positionMs.coerceAtLeast(0L).let {
            if (duration > 0L) it.coerceAtMost(duration) else it
        }
        if (loadedTrackId == track.id && exoPlayer.mediaItemCount > 0) {
            exoPlayer.seekTo(target)
            syncFromPlayer()
        } else {
            pendingSeekMs = target
            _state.update { it.copy(positionMs = target) }
        }
    }

    /** 切换播放模式；当前曲目不变。 */
    fun setMode(mode: PlaybackMode) {
        checkUsable()
        queue.setMode(mode)
        exoPlayer.repeatMode = if (mode == PlaybackMode.SINGLE_REPEAT) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        syncFromPlayer()
    }

    /**
     * 真正释放 ExoPlayer、音频焦点、协程。幂等。
     *
     * 只由 [PlaybackService] 调用；UI 不要调用。
     */
    fun release() {
        if (released) return
        checkMainThread()
        released = true
        resolveJob?.cancel()
        resolveJob = null
        audioFocus.abandon()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        ticker.cancel()
        scope.cancel()
        _state.value = PlaybackState()
    }

    // ------------------------------------------------------------------ 内部：切歌

    private fun startCurrent(autoPlay: Boolean) {
        val track = queue.current
        if (track == null) {
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            loadedTrackId = null
            pendingSeekMs = 0L
            _state.value = PlaybackState(mode = queue.mode)
            return
        }

        loadToken += 1
        val token = loadToken
        resolveJob?.cancel()
        resolveJob = null
        loadedTrackId = null
        pendingSeekMs = 0L
        resumeAfterFocusGain = false

        exoPlayer.pause()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        _state.update {
            it.copy(
                track = track,
                status = PlaybackStatus.BUFFERING,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationMs,
                bufferedPositionMs = 0L,
                bufferedPercent = 0,
                mode = queue.mode,
                queueIndex = queue.currentIndex,
                queueSize = queue.size,
                isTrialOnly = false,
                level = null,
                error = null,
            )
        }

        resolveJob = scope.launch {
            val resolved = try {
                resolver.resolve(track.id, defaultLevel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == loadToken) reportResolveFailure(track, error)
                return@launch
            }
            if (token != loadToken) return@launch
            loadResolved(track, resolved, autoPlay)
        }
    }

    private fun loadResolved(track: Track, resolved: ResolvedPlayback, autoPlay: Boolean) {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
        if (track.album.isNotBlank()) metadata.setAlbumTitle(track.album)
        track.coverUrl?.takeIf { it.isNotBlank() }?.let { metadata.setArtworkUri(Uri.parse(it)) }

        val item = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(resolved.url)
            .setMediaMetadata(metadata.build())
            .build()

        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        if (pendingSeekMs > 0L) {
            exoPlayer.seekTo(pendingSeekMs)
            pendingSeekMs = 0L
        }
        loadedTrackId = track.id

        _state.update {
            it.copy(
                status = PlaybackStatus.BUFFERING,
                isTrialOnly = resolved.isTrialOnly,
                level = resolved.resolvedLevel.value,
                error = null,
            )
        }

        if (autoPlay) startPlaybackWithFocus() else syncFromPlayer()
    }

    private fun startPlaybackWithFocus() {
        if (!audioFocus.request()) {
            Log.w(TAG, "音频焦点申请被拒绝，暂停等待")
            exoPlayer.pause()
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    error = PlaybackError(
                        errorCodeName = PlaybackError.AUDIO_FOCUS_DENIED,
                        message = "系统拒绝音频焦点（可能被通话或其他播放器占用）",
                    ),
                )
            }
            return
        }
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
        exoPlayer.play()
        syncFromPlayer()
    }

    // ------------------------------------------------------------------ 内部：状态同步

    private fun syncFromPlayer() {
        if (released) return
        val track = queue.current
        val duration = exoPlayer.duration.let { playerDuration ->
            if (playerDuration == C.TIME_UNSET || playerDuration <= 0L) {
                track?.durationMs ?: 0L
            } else {
                playerDuration
            }
        }
        val position = exoPlayer.currentPosition.coerceAtLeast(0L).let {
            if (duration > 0L) it.coerceAtMost(duration) else it
        }
        val status = when {
            _state.value.error != null -> PlaybackStatus.ERROR
            exoPlayer.playbackState == Player.STATE_IDLE ->
                if (track == null) PlaybackStatus.IDLE else PlaybackStatus.PAUSED

            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            exoPlayer.playbackState == Player.STATE_READY ->
                if (exoPlayer.isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED

            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> PlaybackStatus.IDLE
        }

        _state.update {
            it.copy(
                track = track,
                status = status,
                isPlaying = exoPlayer.isPlaying,
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                bufferedPercent = exoPlayer.bufferedPercentage.coerceIn(0, 100),
                mode = queue.mode,
                queueIndex = queue.currentIndex,
                queueSize = queue.size,
            )
        }
    }

    private fun handleTrackEnded() {
        if (released) return
        if (_state.value.error != null) {
            syncFromPlayer()
            return
        }

        // 理论上单曲循环由 ExoPlayer 的 REPEAT_MODE_ONE 处理，这里兜底。
        if (queue.mode == PlaybackMode.SINGLE_REPEAT) {
            exoPlayer.seekTo(0L)
            if (exoPlayer.playWhenReady) exoPlayer.play()
            syncFromPlayer()
            return
        }

        val next = queue.next(manual = false)
        if (next == null) {
            // 顺序模式播到队尾：停在最后一首，状态变为 ENDED。
            syncFromPlayer()
            return
        }
        startCurrent(autoPlay = true)
    }

    private fun handleFocusLost(transient: Boolean) {
        val wasActive = exoPlayer.playWhenReady || exoPlayer.isPlaying
        resumeAfterFocusGain = transient && wasActive
        if (exoPlayer.playWhenReady) exoPlayer.pause()
        syncFromPlayer()
    }

    private fun handleFocusGained() {
        if (!resumeAfterFocusGain) return
        resumeAfterFocusGain = false
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
        exoPlayer.play()
        syncFromPlayer()
    }

    private fun reportResolveFailure(track: Track, error: Exception) {
        val playbackError = when (error) {
            is PlaybackUrlException -> PlaybackError(
                errorCodeName = PlaybackError.URL_RESOLVE_FAILED,
                message = error.message,
                causeClass = error::class.simpleName,
            )

            is ApiException -> PlaybackError(
                errorCodeName = "URL_RESOLVE_API_${error.code}",
                errorCode = error.code,
                message = error.message,
                causeClass = error::class.simpleName,
            )

            is HttpException -> PlaybackError(
                errorCodeName = "URL_RESOLVE_HTTP_${error.statusCode}",
                errorCode = error.statusCode,
                message = error.message,
                causeClass = error::class.simpleName,
            )

            is TransportException -> PlaybackError(
                errorCodeName = "URL_RESOLVE_TRANSPORT",
                message = error.message,
                causeClass = error::class.simpleName,
            )

            is ParseException -> PlaybackError(
                errorCodeName = "URL_RESOLVE_PARSE",
                message = error.message,
                causeClass = error::class.simpleName,
            )

            else -> PlaybackError(
                errorCodeName = PlaybackError.URL_RESOLVE_FAILED,
                message = error.message,
                causeClass = error::class.simpleName,
            )
        }

        Log.e(TAG, "解析播放地址失败：songId=${track.id}, code=${playbackError.errorCodeName}", error)
        _state.update {
            it.copy(
                track = track,
                status = PlaybackStatus.ERROR,
                isPlaying = false,
                error = playbackError,
            )
        }
    }

    private fun checkUsable() {
        checkMainThread()
        check(!released) {
            "MusicPlayer 已 release，不能继续使用；请重新调用 PlaybackRuntime.player(context) 获取新实例"
        }
    }
}
