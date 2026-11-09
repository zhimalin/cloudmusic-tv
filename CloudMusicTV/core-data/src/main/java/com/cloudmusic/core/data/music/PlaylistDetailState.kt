package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Playlist
import com.cloudmusic.core.network.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State container for one playlist detail page.
 *
 * The network layer can return 500+ tracks in one response. We keep that list
 * exactly once (no per-row copies) and let `LazyColumn` virtualize composition;
 * [Ready.songs] is a read-only reference to the repository's list.
 *
 * For the special "我喜欢的音乐" playlist, [load] uses [MusicRepository.likedSongs]
 * when [uid] is known; every other playlist (and the fallback) uses
 * [MusicRepository.playlistTracks].
 */
class PlaylistDetailState(
    private val repository: MusicRepository,
    val playlist: Playlist,
    private val uid: Long? = null,
) {

    private val _state = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)

    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.value = PlaylistDetailUiState.Loading

        val result = if (playlist.isLikedPlaylist && uid != null) {
            repository.likedSongs(uid)
        } else {
            repository.playlistTracks(playlist.id)
        }

        _state.value = when (result) {
            is MusicResult.Success -> {
                if (result.value.isEmpty()) {
                    PlaylistDetailUiState.Empty(playlist)
                } else {
                    PlaylistDetailUiState.Ready(playlist, result.value)
                }
            }

            MusicResult.NeedLogin -> PlaylistDetailUiState.NeedLogin
            is MusicResult.NetworkError,
            is MusicResult.BusinessError,
            is MusicResult.ParseError -> PlaylistDetailUiState.Error(result.toUiError())
        }
    }
}

/** Public states of the playlist detail page. */
sealed interface PlaylistDetailUiState {

    data object Loading : PlaylistDetailUiState

    data class Ready(
        val playlist: Playlist,
        val songs: List<Song>,
    ) : PlaylistDetailUiState

    data class Empty(val playlist: Playlist) : PlaylistDetailUiState

    data object NeedLogin : PlaylistDetailUiState

    data class Error(val error: UiError) : PlaylistDetailUiState
}
