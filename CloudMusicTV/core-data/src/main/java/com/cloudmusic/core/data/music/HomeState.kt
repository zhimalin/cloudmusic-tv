package com.cloudmusic.core.data.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * 首页状态：每日推荐摘要 + 最近播放。
 *
 * 首页是「轻内容型」，所以不把 30 首每日推荐全部保留在 HomeState 里：
 * 只留日期、数量和前三首的「歌名 · 歌手」用于卡片摘要；点卡片切换到达每日推荐 tab。
 * 最近播放来自 [RecentPlayStore]，最多展示 5 条（存储本身保留 20 条）。
 *
 * 该类和视图、Android 存储实现都解耦，JVM 测试里用 fake RecentPlayStore。
 */
class HomeState(
    private val repository: MusicRepository,
    private val recentPlayStore: RecentPlayStore,
    private val dateProvider: () -> LocalDate = { LocalDate.now() },
) {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val mutex = Mutex()

    suspend fun load() {
        mutex.withLock {
            _state.value = HomeUiState.Loading
            when (val result = repository.dailySongs()) {
                is MusicResult.Success -> {
                    val songs = result.value
                    _state.value = HomeUiState.Ready(
                        daily = HomeDailySummary(
                            date = dateProvider(),
                            count = songs.size,
                            preview = songs.take(PREVIEW_COUNT).map {
                                "${it.name} · ${it.artistText}"
                            },
                        ),
                        recent = recentPlayStore.load().take(MAX_RECENT),
                    )
                }

                MusicResult.NeedLogin -> _state.value = HomeUiState.NeedLogin
                is MusicResult.NetworkError,
                is MusicResult.BusinessError,
                is MusicResult.ParseError -> {
                    _state.value = HomeUiState.Error(result.toUiError())
                }
            }
        }
    }

    /**
     * 播放器切歌后调用，把最近播放同步到首页；不重新请求每日推荐。
     * 非 Ready 状态直接忽略（下次 [load] 会重新读盘）。
     */
    fun refreshRecent() {
        val current = _state.value as? HomeUiState.Ready ?: return
        _state.value = current.copy(
            recent = recentPlayStore.load().take(MAX_RECENT),
        )
    }

    private companion object {
        const val PREVIEW_COUNT = 3
        const val MAX_RECENT = 5
    }
}

data class HomeDailySummary(
    val date: LocalDate,
    val count: Int,
    /** 卡片上的前三首摘要，例如「歌名 · 歌手」。 */
    val preview: List<String>,
)

sealed interface HomeUiState {

    data object Loading : HomeUiState

    data class Ready(
        val daily: HomeDailySummary,
        val recent: List<RecentPlay>,
    ) : HomeUiState

    data object NeedLogin : HomeUiState

    data class Error(val error: UiError) : HomeUiState
}
