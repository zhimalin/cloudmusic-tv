package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State container for the "我的音乐" landing page.
 *
 * Plain Kotlin on purpose: no ViewModel, no Android Context, so the whole class
 * is exercised by JVM tests. The composable owns the coroutine scope and calls
 * [load]; this class only owns the state.
 *
 * @param knownUid pass a cached uid to skip the account request. When omitted,
 * [load] resolves the uid from [MusicRepository.account].
 */
class LibraryState(
    private val repository: MusicRepository,
    private val knownUid: Long? = null,
) {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)

    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.value = LibraryUiState.Loading

        val uid = knownUid ?: resolveUid() ?: return

        when (val result = repository.playlists(uid)) {
            is MusicResult.Success -> {
                val sections = result.value
                _state.value = if (sections.isEmpty) {
                    LibraryUiState.Empty
                } else {
                    LibraryUiState.Ready(
                        uid = uid,
                        liked = sections.liked,
                        created = sections.created,
                        subscribed = sections.subscribed,
                    )
                }
            }

            MusicResult.NeedLogin -> _state.value = LibraryUiState.NeedLogin
            is MusicResult.NetworkError,
            is MusicResult.BusinessError,
            is MusicResult.ParseError -> _state.value = LibraryUiState.Error(result.toUiError())
        }
    }

    /** Returns the uid, or stores NeedLogin/Error and returns null. */
    private suspend fun resolveUid(): Long? = when (val result = repository.account()) {
        is MusicResult.Success -> result.value.id
        MusicResult.NeedLogin -> {
            _state.value = LibraryUiState.NeedLogin
            null
        }

        is MusicResult.NetworkError,
        is MusicResult.BusinessError,
        is MusicResult.ParseError -> {
            _state.value = LibraryUiState.Error(result.toUiError())
            null
        }
    }
}

/** Public states of the "我的音乐" page. */
sealed interface LibraryUiState {

    /** Request in flight; the screen shows gray skeleton blocks. */
    data object Loading : LibraryUiState

    data class Ready(
        val uid: Long,
        val liked: List<Playlist>,
        val created: List<Playlist>,
        val subscribed: List<Playlist>,
    ) : LibraryUiState

    /** The account works, but all three playlist buckets are empty. */
    data object Empty : LibraryUiState

    data object NeedLogin : LibraryUiState

    data class Error(val error: UiError) : LibraryUiState
}
