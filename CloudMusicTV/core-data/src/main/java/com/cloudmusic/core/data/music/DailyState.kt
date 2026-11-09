package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * State container for 每日推荐.
 *
 * [dateProvider] is injectable so tests can assert a stable date; production
 * uses the local date at load time. The API is date-scoped, so the date stored
 * in [DailyUiState.Ready] is the date the list was fetched for, not a value
 * recomputed on every recomposition.
 */
class DailyState(
    private val repository: MusicRepository,
    private val dateProvider: () -> LocalDate = { LocalDate.now() },
) {

    private val _state = MutableStateFlow<DailyUiState>(DailyUiState.Loading)

    val state: StateFlow<DailyUiState> = _state.asStateFlow()

    suspend fun load() {
        val date = dateProvider()
        _state.value = DailyUiState.Loading

        when (val result = repository.dailySongs()) {
            is MusicResult.Success -> {
                _state.value = if (result.value.isEmpty()) {
                    DailyUiState.Empty(date)
                } else {
                    DailyUiState.Ready(date, result.value)
                }
            }

            MusicResult.NeedLogin -> _state.value = DailyUiState.NeedLogin
            is MusicResult.NetworkError,
            is MusicResult.BusinessError,
            is MusicResult.ParseError -> _state.value = DailyUiState.Error(result.toUiError())
        }
    }
}

/** Public states of 每日推荐. */
sealed interface DailyUiState {

    data object Loading : DailyUiState

    data class Ready(
        val date: LocalDate,
        val songs: List<Song>,
    ) : DailyUiState

    data class Empty(val date: LocalDate) : DailyUiState

    data object NeedLogin : DailyUiState

    data class Error(val error: UiError) : DailyUiState
}
