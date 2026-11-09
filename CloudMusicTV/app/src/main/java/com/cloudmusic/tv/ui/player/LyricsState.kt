package com.cloudmusic.tv.ui.player

import com.cloudmusic.core.data.music.MusicRepository
import com.cloudmusic.core.data.music.MusicResult
import com.cloudmusic.core.player.LrcLine
import com.cloudmusic.core.player.LrcParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放页歌词的四种可见状态。
 *
 * [Ready.lines] 已经过滤掉「只有时间戳、没有文字」的空行（间奏标记）：
 * 播放页是固定 8 行布局，空行会把可视区撑成空白；过滤后高亮逻辑也更直观。
 */
sealed interface LyricsUiState {

    data object Idle : LyricsUiState

    data object Loading : LyricsUiState

    data class Ready(
        val songId: Long,
        val lines: List<LrcLine>,
    ) : LyricsUiState

    /** 接口成功但确实没有歌词（纯音乐 / 没有 LRC）。 */
    data class Empty(
        val songId: Long,
        val reason: String? = null,
    ) : LyricsUiState

    /** 网络 / 业务错误；播放页显示「暂无歌词」+ 错误码，不阻断播放。 */
    data class Failed(
        val songId: Long,
        val code: String,
        val message: String,
    ) : LyricsUiState
}

/**
 * 歌词加载器：仓库层拿 [com.cloudmusic.core.network.Lyric]，用 core-player 的
 * [LrcParser] 解析成 [LrcLine]。
 *
 * 缓存策略：只缓存**最近一首**的最终结果（含「确实没有歌词」）。电视内存只有 2GB，
 * 不做 LRU / 不缓存整张专辑；同一首歌反复进出播放页不会重复请求。
 *
 * 必须在主线程（Compose 的 LaunchedEffect）里调用；仓库调用本身是挂起的。
 */
class LyricsState(private val repository: MusicRepository) {

    private val _state = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private var loadToken = 0
    private var cachedSongId: Long? = null
    private var cachedState: LyricsUiState? = null

    suspend fun load(songId: Long, force: Boolean = false) {
        if (songId <= 0L) {
            _state.value = LyricsUiState.Empty(songId, "无效的歌曲 id")
            return
        }

        val cached = cachedState
        if (!force && cachedSongId == songId && cached != null) {
            _state.value = cached
            return
        }

        val current = _state.value
        if (!force && current is LyricsUiState.Ready && current.songId == songId) return

        val token = ++loadToken
        _state.value = LyricsUiState.Loading

        val next = when (val result = repository.lyric(songId)) {
            is MusicResult.Success -> {
                val lines = LrcParser.parse(result.value)
                    .filter { it.text.isNotBlank() || !it.translation.isNullOrBlank() }
                if (lines.isEmpty()) {
                    LyricsUiState.Empty(songId, "接口没有返回可显示的歌词")
                } else {
                    LyricsUiState.Ready(songId, lines)
                }
            }

            MusicResult.NeedLogin -> LyricsUiState.Failed(
                songId = songId,
                code = "NEED_LOGIN",
                message = "登录状态已失效，歌词不可用",
            )

            is MusicResult.NetworkError -> LyricsUiState.Failed(songId, result.code, result.message)
            is MusicResult.BusinessError -> LyricsUiState.Failed(songId, "API_${result.code}", result.message)
            is MusicResult.ParseError -> LyricsUiState.Failed(songId, "PARSE", result.message)
        }

        // 切歌很快时旧请求可能后到：丢弃过期结果，不覆盖新歌的歌词。
        if (token != loadToken) return

        // 失败不缓存：网络恢复后重新进播放页应该能再试一次。
        // 「确实没有歌词」和成功结果才缓存。
        if (next is LyricsUiState.Ready || next is LyricsUiState.Empty) {
            cachedSongId = songId
            cachedState = next
        }
        _state.value = next
    }
}
