package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 私人 FM 的纯 Kotlin 状态容器。
 *
 * ### 接口实际行为与策略
 * `personalFm()` 真实请求 `/weapi/v1/radio/get`，一次返回 **3 首**（2026-11-08 实测）。
 * 因此采用「一次拿一批、批内顺序消费、批空再拿下一批」：
 * - 第一次 [load] 取 3 首，[FmUiState.Playing.song] 是第一首，剩余 2 首留在内部队列；
 * - [advance] 先消费内部队列，队列空了才再发一次 `radio/get`；
 * - [dislikeCurrent] 先调用 `radio/trash/add`，成功后才把当前歌移出并 [advance]；
 *   失败时当前歌和内部队列都不变。
 *
 * 播放器本身每次只拿当前一首（见 app 层），这样「不喜欢」可以立即换歌，也不会把
 * 已丢弃的歌曲留在播放器队列里。自动播完时由 app 观察 `PlaybackState.ENDED` 调 [advance]。
 *
 * [Mutex] 防止自动推进和用户同时按键导致同一批被消费两次。所有方法可在任意线程调用；
 * 状态本身通过 [StateFlow] 暴露为不可变快照。
 */
class FmState(private val repository: MusicRepository) {

    private val _state = MutableStateFlow<FmUiState>(FmUiState.Loading)
    val state: StateFlow<FmUiState> = _state.asStateFlow()

    /** 下一批/下一首，不含当前这首。 */
    private val upcoming = ArrayDeque<Song>()

    private val mutex = Mutex()

    /** 当前应播放的歌曲；非 Playing 状态为 null。 */
    val currentSong: Song?
        get() = (_state.value as? FmUiState.Playing)?.song

    /** 首次进入 FM 或显式重试：重新拉一批，替换旧批次。 */
    suspend fun load() {
        mutex.withLock { loadBatchLocked() }
    }

    /**
     * 切到 FM 的下一首。
     *
     * 批内还有歌就不发请求；最后一批用完后在这里请求新批次。
     * 非 Playing 状态调用会尝试重新加载。
     */
    suspend fun advance() {
        mutex.withLock { advanceLocked() }
    }

    /**
     * 不喜欢当前歌曲。
     *
     * 只有服务端确认成功后才推进；失败返回原始 [MusicResult]（当前歌保持不变）。
     * 返回的 `Success` 只代表垃圾桶接口成功；若随后补新批次失败，[state] 会变成 Error。
     */
    suspend fun dislikeCurrent(): MusicResult<Unit> = mutex.withLock {
        val current = currentSong
            ?: return@withLock MusicResult.BusinessError(
                code = 0,
                message = "当前没有正在播放的 FM 歌曲",
            )

        val result = repository.dislikeSong(current.id)
        if (result is MusicResult.Success) {
            advanceLocked()
        }
        result
    }

    private suspend fun advanceLocked() {
        if (_state.value !is FmUiState.Playing) {
            loadBatchLocked()
            return
        }

        val next = upcoming.removeFirstOrNull()
        if (next != null) {
            _state.value = FmUiState.Playing(
                song = next,
                queuedInBatch = upcoming.size,
            )
        } else {
            loadBatchLocked()
        }
    }

    private suspend fun loadBatchLocked() {
        _state.value = FmUiState.Loading

        when (val result = repository.personalFm()) {
            is MusicResult.Success -> {
                val songs = result.value.distinctBy { it.id }
                val first = songs.firstOrNull()
                if (first == null) {
                    upcoming.clear()
                    _state.value = FmUiState.Empty
                } else {
                    upcoming.clear()
                    upcoming.addAll(songs.drop(1))
                    _state.value = FmUiState.Playing(
                        song = first,
                        queuedInBatch = upcoming.size,
                    )
                }
            }

            MusicResult.NeedLogin -> {
                upcoming.clear()
                _state.value = FmUiState.NeedLogin
            }

            is MusicResult.NetworkError,
            is MusicResult.BusinessError,
            is MusicResult.ParseError -> {
                upcoming.clear()
                _state.value = FmUiState.Error(result.toUiError())
            }
        }
    }
}

/** 私人 FM 页面的公开状态。 */
sealed interface FmUiState {

    data object Loading : FmUiState

    data class Playing(
        val song: Song,
        /** 当前批次里还剩几首（不含 [song]）。 */
        val queuedInBatch: Int,
    ) : FmUiState

    data object Empty : FmUiState

    data object NeedLogin : FmUiState

    data class Error(val error: UiError) : FmUiState
}
